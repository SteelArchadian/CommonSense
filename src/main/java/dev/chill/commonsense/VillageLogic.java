package dev.chill.commonsense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Villagers, golems and guards finding their way home.
 * <ul>
 *   <li>Villagers keep their bed in a saved record; vanilla forgets it after a minute of being unable to reach it.</li>
 *   <li>Stranded village mobs walk home in stages (waypoints toward home), instead of giving up when home is out of path range.</li>
 *   <li>Villagers lost underground, far from their bed, with no player leading them for 60 s, teleport back to the village bell
 *       (or their bed) onto open, safe ground.</li>
 *   <li>A villager led away by a player (lead, boat, minecart, or walked along) drops its old village as home, so stolen villagers stay put.
 *       Only a bed it claims afterwards counts. Cured zombie villagers have no bed, so nothing happens until they claim one.</li>
 * </ul>
 */
public final class VillageLogic {

    private VillageLogic() {
    }

    public static boolean isVillageMob(Mob mob) {
        return mob instanceof Villager || mob.getType().is(CommonSense.VILLAGE_MOBS);
    }

    public static VillageMemory memory(Mob mob) {
        return mob.getData(CommonSense.VILLAGE);
    }

    /** Called every 40 ticks for village mobs on the server. */
    public static void tick(Mob mob) {
        ServerLevel level = (ServerLevel) mob.level();
        VillageMemory mem = memory(mob);
        long now = level.getGameTime();

        if (isBeingLed(mob)) {
            mem.lastLedTick = now;
        }
        if (level.isVillage(mob.blockPosition())) {
            mem.anchor = GlobalPos.of(level.dimension(), mob.blockPosition());
        }
        if (mob instanceof Villager villager) {
            trackBed(villager, level, mem, now);
        }

        BlockPos home = homeFor(mob, level, mem);
        if (home == null) {
            mem.lostSinceTick = -1;
            return;
        }
        double distSq = mob.blockPosition().distSqr(home);

        if (mob instanceof Villager villager && Config.VILLAGER_TELEPORT_HOME.get()) {
            if (isLost(villager, level, mem, distSq, now)) {
                if (mem.lostSinceTick < 0) {
                    mem.lostSinceTick = now;
                } else if (now - mem.lostSinceTick >= Config.LOST_SECONDS.get() * 20L) {
                    if (teleportHome(villager, level, mem)) {
                        mem.lostSinceTick = -1;
                        return;
                    }
                }
            } else {
                mem.lostSinceTick = -1;
            }
        }

        if (Config.VILLAGE_HOMING.get() && distSq > 48 * 48 && now - mem.lastHomingTick >= 100 && canHome(mob, level, mem, now)) {
            walkTowardHome(mob, home);
            mem.lastHomingTick = now;
        }
    }

    /** The player a villager is following because they hold a lure, or null. */
    @Nullable
    public static Player lureHolder(Villager villager) {
        if (!Config.VILLAGER_LURE_FOLLOW.get() || villager.isSleeping() || villager.isTrading() || villager.isPassenger() || villager.isLeashed()) {
            return null;
        }
        Brain<Villager> brain = villager.getBrain();
        if (brain.isActive(Activity.PANIC) || brain.isActive(Activity.HIDE) || brain.isActive(Activity.RAID) || brain.isActive(Activity.PRE_RAID)) {
            return null;
        }
        double range = Config.LURE_FOLLOW_RANGE.get();
        Player best = null;
        double bestSq = range * range;
        for (Player player : villager.level().players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double d = player.distanceToSqr(villager);
            if (d <= bestSq && (player.getMainHandItem().is(CommonSense.VILLAGER_LURES) || player.getOffhandItem().is(CommonSense.VILLAGER_LURES))) {
                best = player;
                bestSq = d;
            }
        }
        return best;
    }

    /** How long a villager is kept from drifting back to the player who put the lure away (long enough to walk out of view). */
    private static final long LURE_COOLDOWN_TICKS = 100;

    /** True while tickLure should run every tick instead of every few ticks. */
    public static boolean inLureCooldown(Villager villager) {
        VillageMemory mem = villager.getExistingDataOrNull(CommonSense.VILLAGE);
        return mem != null && mem.lureCooldownUntil > villager.level().getGameTime();
    }

    private static void dropTargetsOn(Brain<Villager> brain, java.util.UUID playerId) {
        brain.getMemory(MemoryModuleType.WALK_TARGET).ifPresent(t -> {
            if (t.getTarget() instanceof EntityTracker e && e.getEntity().getUUID().equals(playerId)) {
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            }
        });
        brain.getMemory(MemoryModuleType.LOOK_TARGET).ifPresent(t -> {
            if (t instanceof EntityTracker e && e.getEntity().getUUID().equals(playerId)) {
                brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
        });
        brain.getMemory(MemoryModuleType.INTERACTION_TARGET).ifPresent(t -> {
            if (t.getUUID().equals(playerId)) {
                brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET);
            }
        });
    }

    /** Every few ticks: walk after a lure holder; stop close by, and stop entirely once the lure is put away. */
    public static void tickLure(Villager villager) {
        VillageMemory mem = memory(villager);
        Brain<Villager> brain = villager.getBrain();
        Player player = lureHolder(villager);
        long now = villager.level().getGameTime();
        if (player == null) {
            if (mem.lured) {
                mem.lured = false;
                mem.lureCooldownUntil = now + LURE_COOLDOWN_TICKS;
            }
            if (now < mem.lureCooldownUntil && mem.lurePlayer != null) {
                // Vanilla idle behaviour looks at a nearby player and strolls toward whatever it looks at, so a villager that
                // just stopped following would drift straight back. For a few seconds, drop only targets aimed at that player.
                dropTargetsOn(brain, mem.lurePlayer);
            }
            return;
        }
        mem.lurePlayer = player.getUUID();
        mem.lureCooldownUntil = -1;
        mem.lastLedTick = now;
        brain.setMemoryWithExpiry(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true), 20L);
        if (villager.distanceTo(player) > 2.5F) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(player, false), 0.7F, 2));
        } else {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        mem.lured = true;
    }

    /**
     * Lead, vehicle, a player escorting nearby, or a player holding a villager lure (other mods' follow mechanics,
     * e.g. emerald blocks) all count as being led. While led, this mod never steers or teleports the mob.
     */
    static boolean isBeingLed(Mob mob) {
        if (mob.isLeashed() || mob.isPassenger()) {
            return true;
        }
        double escort = Config.ESCORT_RADIUS.get();
        double lure = Config.LURE_RADIUS.get();
        for (Player player : mob.level().players()) {
            if (player.isSpectator()) {
                continue;
            }
            double d = player.distanceToSqr(mob);
            if (d <= escort * escort) {
                return true;
            }
            if (d <= lure * lure && (player.getMainHandItem().is(CommonSense.VILLAGER_LURES) || player.getOffhandItem().is(CommonSense.VILLAGER_LURES))) {
                return true;
            }
        }
        return false;
    }

    private static void trackBed(Villager villager, ServerLevel level, VillageMemory mem, long now) {
        Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
        if (home.isPresent() && !home.get().equals(mem.bed)) {
            // A new bed, claimed by the villager itself: that's home now (this is also how a moved villager settles in).
            mem.bed = home.get();
            mem.bedAbandoned = false;
        }
        if (mem.bed == null || mem.bed.dimension() != level.dimension()) {
            return;
        }
        // The bed only stops counting when it's actually gone.
        if (level.isLoaded(mem.bed.pos()) && !level.getPoiManager().existsAtPosition(PoiTypes.HOME, mem.bed.pos())) {
            mem.bed = null;
            mem.bedAbandoned = false;
            return;
        }
        double dist = villager.blockPosition().distSqr(mem.bed.pos());
        if (now - mem.lastLedTick < 40 && dist > 32 * 32) {
            mem.bedAbandoned = true;
        } else if (dist < 16 * 16) {
            mem.bedAbandoned = false;
        }
    }

    @Nullable
    private static BlockPos homeFor(Mob mob, ServerLevel level, VillageMemory mem) {
        if (mob instanceof Villager) {
            return mem.bed != null && !mem.bedAbandoned && mem.bed.dimension() == level.dimension() ? mem.bed.pos() : null;
        }
        return mem.anchor != null && mem.anchor.dimension() == level.dimension() ? mem.anchor.pos() : null;
    }

    private static boolean isLost(Villager villager, ServerLevel level, VillageMemory mem, double distSq, long now) {
        if (distSq <= 48 * 48 || now - mem.lastLedTick < Config.LOST_SECONDS.get() * 20L) {
            return false;
        }
        // Working villagers (trading halls, iron farms) are never "lost".
        Optional<GlobalPos> job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (job.isPresent() && job.get().dimension() == level.dimension() && job.get().pos().distSqr(villager.blockPosition()) < 16 * 16) {
            return false;
        }
        BlockPos pos = villager.blockPosition();
        boolean underground = !level.canSeeSky(pos.above()) && pos.getY() < level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - 6;
        return underground;
    }

    private static boolean canHome(Mob mob, ServerLevel level, VillageMemory mem, long now) {
        if (now - mem.lastLedTick < 600 || mob.isLeashed() || mob.isPassenger() || mob.getTarget() != null) {
            return false;
        }
        // Never take over movement another mod or behaviour is driving toward a player (follow/lure mechanics).
        for (Player player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(mob) < 48 * 48) {
                var path = mob.getNavigation().getPath();
                if (path != null && !path.isDone() && path.getTarget().distSqr(player.blockPosition()) < 8 * 8) {
                    return false;
                }
                var walk = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
                if (walk.isPresent() && walk.get().getTarget().currentBlockPosition().distSqr(player.blockPosition()) < 8 * 8) {
                    return false;
                }
            }
        }
        // Don't fight a path that's already taking it home; replace idle wandering that isn't.
        var path = mob.getNavigation().getPath();
        BlockPos home = homeFor(mob, level, mem);
        if (path != null && !path.isDone() && home != null) {
            return path.getTarget().distSqr(home) >= mob.blockPosition().distSqr(home) - 16;
        }
        return true;
    }

    /** Extended pathing: home may be far beyond normal path range, so walk toward it one reachable stretch at a time. */
    private static void walkTowardHome(Mob mob, BlockPos home) {
        if (!(mob instanceof PathfinderMob pmob)) {
            return;
        }
        Vec3 here = mob.position();
        Vec3 homeVec = Vec3.atBottomCenterOf(home);
        Vec3 dir = homeVec.subtract(here).multiply(1, 0, 1);
        double len = dir.length();
        if (len < 1.0) {
            return;
        }
        Vec3 step = null;
        // Straight toward home first (snapped onto solid ground), shorter stretches if that spot is no good.
        for (double reach : new double[]{20, 14, 8}) {
            Vec3 p = here.add(dir.scale(Math.min(reach, len) / len));
            BlockPos base = BlockPos.containing(p.x, here.y, p.z);
            for (int dy = 4; dy >= -8 && step == null; dy--) {
                if (Companions.isSafeStandPos(mob, base.above(dy))) {
                    step = Vec3.atBottomCenterOf(base.above(dy));
                }
            }
            if (step != null) {
                break;
            }
        }
        if (step == null) {
            step = LandRandomPos.getPosTowards(pmob, 16, 7, homeVec);
        }
        if (step == null) {
            return;
        }
        if (mob instanceof Villager villager) {
            Brain<Villager> brain = villager.getBrain();
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(step, 0.6F, 2));
        } else {
            mob.getNavigation().setMaxVisitedNodesMultiplier(2.0F);
            mob.getNavigation().moveTo(step.x, step.y, step.z, 0.7);
        }
    }

    private static boolean teleportHome(Villager villager, ServerLevel level, VillageMemory mem) {
        BlockPos bed = mem.bedPos();
        if (bed == null || !level.isLoaded(bed)) {
            return false;
        }
        BlockPos center = bed;
        Optional<GlobalPos> meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
        if (meeting.isPresent() && meeting.get().dimension() == level.dimension() && meeting.get().pos().distSqr(bed) < 64 * 64) {
            center = meeting.get().pos();
        } else {
            Optional<BlockPos> bell = level.getPoiManager().findClosest(t -> t.is(PoiTypes.MEETING), bed, 48, PoiManager.Occupancy.ANY);
            if (bell.isPresent()) {
                center = bell.get();
            }
        }
        Vec3 spot = findRoomySpot(villager, center);
        if (spot == null) {
            spot = findRoomySpot(villager, bed);
        }
        if (spot == null) {
            return false;
        }
        Companions.teleportSafely(villager, spot);
        Brain<Villager> brain = villager.getBrain();
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        brain.eraseMemory(MemoryModuleType.PATH);
        // Vanilla may have released the bed while the villager was stuck; take it back if nobody else has.
        if (brain.getMemory(MemoryModuleType.HOME).isEmpty()) {
            level.getPoiManager().take(t -> t.is(PoiTypes.HOME), (t, p) -> p.equals(bed), bed, 1)
                    .ifPresent(p -> brain.setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), p)));
        }
        return true;
    }

    /** Safe ground with open space around it, so the villager can actually move (not wedged into a corner or a block). */
    @Nullable
    static Vec3 findRoomySpot(Mob mob, BlockPos center) {
        for (int i = 0; i < 64; i++) {
            int r = 1 + i / 8;
            int dx = mob.getRandom().nextIntBetweenInclusive(-r, r);
            int dz = mob.getRandom().nextIntBetweenInclusive(-r, r);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (Companions.isSafeStandPos(mob, pos) && openNeighbours(mob, pos) >= 3) {
                    return Vec3.atBottomCenterOf(pos);
                }
            }
        }
        return null;
    }

    private static int openNeighbours(Mob mob, BlockPos pos) {
        int open = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            if (Companions.isSafeStandPos(mob, n) || Companions.isSafeStandPos(mob, n.below()) || Companions.isSafeStandPos(mob, n.above())) {
                open++;
            }
        }
        return open;
    }
}
