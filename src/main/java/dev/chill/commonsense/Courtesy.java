package dev.chill.commonsense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Everyday sense for mobs that know better than monsters: keep a body's width plus a little slack away from things that hurt
 * (so a bump or a turn doesn't put them in the cactus), pat out small fires, and take turns at a doorway.
 */
public final class Courtesy {

    /** How much slack beyond its own body a careful mob keeps from a hazard (a bump, a turn, a misstep). */
    private static final double SLACK = 0.25;
    private static final int FIRE_RANGE = 8;
    private static final int DOOR_NEAR = 3;

    /** Dev load test switch: time {@link #tick} into {@link #profNanos}. */
    public static boolean profile;
    public static long profNanos;
    private static final Map<Mob, FireJob> FIRE_JOBS = new WeakHashMap<>();
    private static final Map<Level, Map<Long, DoorClaim>> DOORS = new WeakHashMap<>();
    private static final Map<Mob, Yield> YIELDS = new WeakHashMap<>();

    private Courtesy() {
    }

    /** Mobs that look after themselves: everything but monsters, plus monsters smart enough to use doors (illagers). */
    public static boolean careful(Mob mob) {
        if (mob.getType().is(CommonSense.EXCLUDED) || !(mob instanceof PathfinderMob pmob) || !(mob.getNavigation() instanceof GroundPathNavigation)) {
            return false;
        }
        return Companions.isCompanion(mob) || VillageLogic.isVillageMob(mob) || !(mob instanceof Enemy) || HostileWits.smart(pmob);
    }

    /** Mobs that understand fire and doors well enough to deal with them. */
    public static boolean clever(Mob mob) {
        return mob instanceof PathfinderMob pmob && !mob.getType().is(CommonSense.EXCLUDED) && HostileWits.smart(pmob);
    }

    static void tick(Mob mob, int phase) {
        // a mob standing still can't walk or be shoved into anything: look every 10 ticks instead of every 2
        if (Config.HAZARD_AVOIDANCE.get() && phase % 2 == 0 && (phase % 10 == 0 || mob.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4)
                && !mob.fireImmune() && careful(mob)) {
            keepClear(mob);
        }
        if (Config.FIRE_PATTING.get()) {
            FireJob job = FIRE_JOBS.get(mob);
            if (job != null) {
                tickFire(mob, job);
            } else if (phase % fireScan(mob) == 0 && clever(mob)) {
                findFire(mob);
            }
        }
        if (Config.DOOR_TURNS.get()) {
            Yield yield = YIELDS.get(mob);
            if (yield != null) {
                tickYield(mob, yield);
            } else if (phase % 2 == 0) {
                checkDoor(mob);
            }
        }
    }

    // ------------------------------------------------------------------ hazard clearance

    /** Edge away from any hazard within body half-width plus slack: covers bumps, turns, wide bodies and being shoved. */
    private static void keepClear(Mob mob) {
        if (mob.isPassenger() || mob.isSleeping() || mob.getControllingPassenger() instanceof Player || !mob.isAlive()) {
            return;
        }
        Level level = mob.level();
        AABB body = mob.getBoundingBox();
        double half = mob.getBbWidth() / 2.0;
        double reachH = half + SLACK;
        boolean cliffs = mob.onGround();
        double reach = cliffs ? half + CLIFF_CLEAR : reachH;
        double cx = (body.minX + body.maxX) / 2.0;
        double cz = (body.minZ + body.maxZ) / 2.0;
        int x0 = net.minecraft.util.Mth.floor(cx - reach);
        int x1 = net.minecraft.util.Mth.floor(cx + reach);
        int z0 = net.minecraft.util.Mth.floor(cz - reach);
        int z1 = net.minecraft.util.Mth.floor(cz + reach);
        int y0 = net.minecraft.util.Mth.floor(body.minY + 0.01);
        int y1 = net.minecraft.util.Mth.floor(Math.min(body.maxY, body.minY + 2.0) - 0.01);
        double px = 0.0;
        double pz = 0.0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean found = false;
                for (int y = y0; y <= y1; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !hurts(mob, pos, state)) {
                        continue;
                    }
                    found = true;
                    if (push(mob, cx, cz, x, z, reachH, px, pz)) {
                        double[] p = PUSH;
                        px += p[0];
                        pz += p[1];
                    }
                    break;
                }
                if (!found && cliffs && edgeNear(cx, cz, x, z, reach) && dropsOff(level, x, y0, z)) {
                    if (push(mob, cx, cz, x, z, reach, px, pz)) {
                        double[] p = PUSH;
                        px += p[0];
                        pz += p[1];
                    }
                }
            }
        }
        double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0E-3) {
            return;   // boxed in evenly on both sides (a one-wide gap it chose to take): stay centred
        }
        double ux = px / len;
        double uz = pz / len;
        double push = Math.min(0.12, 0.05 + 0.05 * len);
        Vec3 v = mob.getDeltaMovement();
        double toward = v.x * ux + v.z * uz;
        if (toward < 0.0) {   // grazing or strolling into it: cancel the part of its step that heads for the hazard
            v = new Vec3(v.x - ux * toward, v.y, v.z - uz * toward);
        }
        mob.setDeltaMovement(v.x + ux * push, v.y, v.z + uz * push);
    }

    /** Keep a body this far (blocks) from the lip of a drop of {@link #CLIFF_DROP} or more; shorter drops are cut as close as ever. */
    private static final double CLIFF_CLEAR = 0.5;
    private static final int CLIFF_DROP = 4;
    private static final double[] PUSH = new double[2];

    private static boolean edgeNear(double cx, double cz, int x, int z, double reach) {
        double dx = cx - Math.max(x, Math.min(cx, x + 1.0));
        double dz = cz - Math.max(z, Math.min(cz, z + 1.0));
        return dx * dx + dz * dz < reach * reach;
    }

    /** Fills {@link #PUSH} with the weighted direction away from block column (x, z); false if it's out of reach. */
    private static boolean push(Mob mob, double cx, double cz, int x, int z, double reach, double px, double pz) {
        double dx = cx - Math.max(x, Math.min(cx, x + 1.0));
        double dz = cz - Math.max(z, Math.min(cz, z + 1.0));
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d >= reach) {
            return false;
        }
        if (d < 1.0E-3) {   // already standing in its column: head out the way it's nearest to leaving
            dx = cx - (x + 0.5);
            dz = cz - (z + 0.5);
            d = Math.max(1.0E-3, Math.sqrt(dx * dx + dz * dz));
        }
        double weight = (reach - Math.min(d, reach)) / reach + 0.2;
        PUSH[0] = dx / d * weight;
        PUSH[1] = dz / d * weight;
        return true;
    }

    /** Open air beside the mob's feet with nothing to land on (or lava) within {@link #CLIFF_DROP} blocks below. */
    private static boolean dropsOff(Level level, int x, int feetY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, feetY, z);
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;   // unloaded, or a wall rather than an edge
        }
        for (int k = 1; k <= CLIFF_DROP; k++) {
            pos.setY(feetY - k);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
            if (!state.getCollisionShape(level, pos).isEmpty() || state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hurts(Mob mob, BlockPos pos, BlockState state) {
        // the damage type comes from the block and, for open ones like grass, the block under it (grass over magma burns):
        // remember it per pair of states
        long key = ((long) Block.getId(state) << 32) | (Block.getId(mob.level().getBlockState(pos.below())) & 0xFFFFFFFFL);
        Boolean known = HURTS.get(key);
        if (known == null) {
            PathType type = WalkNodeEvaluator.getPathTypeStatic(mob, pos);
            known = type == PathType.DAMAGE_FIRE || type == PathType.DAMAGE_OTHER || type == PathType.LAVA
                    || type == PathType.DAMAGE_CAUTIOUS || type == PathType.POWDER_SNOW;
            if (HURTS.size() < 65536) {
                HURTS.put(key, known);
            }
        }
        return known;
    }

    private static final Map<Long, Boolean> HURTS = new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------------------------ fire patting

    private static final class FireJob {
        @Nullable
        BlockPos fire;
        long until;
        int pats;
        int nextPat;
        int nextWalk;
    }

    /** Dev traces: what this mob's fire job is up to. */
    public static String fireDebug(Mob mob) {
        FireJob job = FIRE_JOBS.get(mob);
        return job == null ? "nojob busy=" + busy(mob) : "fire=" + job.fire + " pats=" + job.pats + " left=" + (job.until - mob.level().getGameTime())
                + " nav=" + (mob.getNavigation().isDone() ? "idle" : "moving");
    }

    /** Guards are on top of fires, clerics quick, everyone else slow to notice. */
    private static int fireScan(Mob mob) {
        return switch (fireRole(mob)) {
            case 2 -> 5;
            case 1 -> 15;
            default -> 30;
        };
    }

    /** 0 plain, 1 cleric, 2 guard (a village mob that isn't a villager or golem). */
    private static int fireRole(Mob mob) {
        if (mob instanceof Villager v) {
            return v.getVillagerData().getProfession() == VillagerProfession.CLERIC ? 1 : 0;
        }
        if (mob.getTags().contains(GUARD_TAG)) return 2;   // any mob can be marked a guard by scoreboard tag
        return VillageLogic.isVillageMob(mob) && !(mob instanceof net.minecraft.world.entity.animal.IronGolem) ? 2 : 0;
    }

    private static int fireRange(Mob mob) {
        return switch (fireRole(mob)) {
            case 2 -> 20;
            case 1 -> 14;
            default -> FIRE_RANGE;
        };
    }

    /** How far a helper stands off and throws water from. */
    private static double throwRange(Mob mob) {
        return fireRole(mob) == 1 ? 10 : 6;
    }

    public static final String GUARD_TAG = "commonsense_guard";
    public static final String SPLASH_TAG = "commonsense_water";

    /** A helper's water bottle landed: put out fire blocks and burning mobs within the configured radius of the impact. */
    public static void splashLanded(net.minecraft.world.entity.projectile.ThrownPotion potion, Vec3 at) {
        Level level = potion.level();
        int r = Config.SPLASH_RADIUS.get();
        BlockPos c = BlockPos.containing(at);
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -1, -r), c.offset(r, 1, r))) {
            if (pos.distSqr(c) <= r * r + 1 && pattable(level, pos)) {
                level.removeBlock(pos, false);
                level.levelEvent(null, 1009, pos, 0);
            }
        }
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(c).inflate(r, 1.5, r))) {
            if (e.isOnFire() && e.distanceToSqr(at) <= (r + 0.5) * (r + 0.5)) {
                e.clearFire();
            }
        }
    }

    private static void throwWater(Mob mob, Vec3 center) {
        ThrownPotion potion = new ThrownPotion(mob.level(), mob);
        potion.setItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER));
        double dx = center.x - mob.getX();
        double dz = center.z - mob.getZ();
        double flat = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        // fast, flat lob: horizontal speed 1.1 (+5% for drag), just enough lift to cancel the 0.05 gravity over the flight
        double vh = 1.1;
        double t = flat / (vh * 0.95);
        double vy = (center.y - 0.1 - potion.getY() + 0.025 * t * (t + 1.0)) / t;
        potion.shoot(dx / flat * vh, vy, dz / flat * vh, (float) Math.sqrt(vh * vh + vy * vy), 0.0F);
        potion.addTag(SPLASH_TAG);
        mob.level().addFreshEntity(potion);
    }

    private static boolean busy(Mob mob) {
        return mob.getTarget() != null || mob.isBaby() || mob.isSleeping() || mob.isPassenger() || mob.isLeashed() || mob.isOnFire();
    }

    private static boolean pattable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // fire on netherrack, magma or bedrock burns for ever on purpose: that's someone's fireplace, leave it
        return state.is(BlockTags.FIRE) && !level.getBlockState(pos.below()).is(level.dimensionType().infiniburn());
    }

    private static void findFire(Mob mob) {
        if (busy(mob)) {
            return;
        }
        Level level = mob.level();
        BlockPos at = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int range = fireRange(mob);
        BlockPos lo = at.offset(-range, -1, -range);
        BlockPos hi = at.offset(range, 1, range);
        if (!level.hasChunksAt(lo, hi)) {
            return;   // near unloaded ground: never load chunks for this
        }
        for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
            if (!level.getBlockState(pos).is(BlockTags.FIRE) || !pattable(level, pos)) {
                continue;
            }
            double d = pos.distSqr(at);
            if (d < bestDist) {
                best = pos.immutable();
                bestDist = d;
            }
        }
        if (best != null && mob instanceof Enemy && level instanceof ServerLevel sl && sl.isVillage(best)) {
            return;   // raiders don't put out a village's fire
        }
        if (best != null && fireRole(mob) == 0 && mob instanceof Villager && mob.getRandom().nextFloat() < 0.25F) {
            // plain villagers are slow to help: mostly they stand there or back away
            if (mob.distanceToSqr(Vec3.atCenterOf(best)) < 25 && mob instanceof PathfinderMob pmob && mob.getRandom().nextBoolean()) {
                Vec3 away = LandRandomPos.getPosAway(pmob, 8, 4, Vec3.atCenterOf(best));
                if (away != null) {
                    mob.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
                }
            }
            return;
        }
        if (best != null) {
            FireJob job = new FireJob();
            job.fire = best;
            job.until = level.getGameTime() + 300;
            FIRE_JOBS.put(mob, job);
        }
    }

    private static void tickFire(Mob mob, FireJob job) {
        Level level = mob.level();
        if (job.fire == null) {   // resting after giving up on one it couldn't get to
            if (level.getGameTime() >= job.until) {
                FIRE_JOBS.remove(mob);
            }
            return;
        }
        if (!pattable(level, job.fire)) {
            FIRE_JOBS.remove(mob);
            return;
        }
        if (busy(mob)) {
            return;   // a fight or a lead comes first; the job keeps until its time runs out, so a passing glance doesn't cancel it
        }
        if (level.getGameTime() >= job.until) {
            job.fire = null;
            job.until = level.getGameTime() + 400;
            return;
        }
        Vec3 center = Vec3.atBottomCenterOf(job.fire);
        double flat = Math.hypot(mob.getX() - center.x, mob.getZ() - center.z);
        mob.getLookControl().setLookAt(center.x, center.y + 0.3, center.z);
        if (flat <= throwRange(mob) && Math.abs(mob.getY() - center.y) <= 2.5) {
            stopWalking(mob);
            if (--job.nextPat <= 0) {
                job.nextPat = 20;
                mob.swing(InteractionHand.MAIN_HAND);
                if (level instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.SMOKE, center.x, center.y + 0.3, center.z, 4, 0.2, 0.1, 0.2, 0.01);
                    server.playSound(null, job.fire, SoundEvents.WOOL_STEP, SoundSource.NEUTRAL, 0.6F, 0.8F + mob.getRandom().nextFloat() * 0.3F);
                }
                throwWater(mob, center);
                if (++job.pats >= 4) {   // a miss or a fire on a wall: finish it by hand
                    level.removeBlock(job.fire, false);
                    level.levelEvent(null, 1009, job.fire, 0);   // the fizz of a fire going out
                    FIRE_JOBS.remove(mob);
                }
            }
        } else if (--job.nextWalk <= 0 || (job.nextWalk < 15 && mob.getNavigation().isDone())) {
            // goal mobs (pillagers...) get pulled off by their own stroll goals: ask again as soon as they've stopped
            job.nextWalk = 20;
            walkTo(mob, job.fire, 1);
        }
    }

    // ------------------------------------------------------------------ door turns

    private static final class DoorClaim {
        int mobId;
        long since;
        long seen;
    }

    private static final class Yield {
        BlockPos door;
        long until;
        long giveUp;
        @Nullable
        WalkTarget saved;
    }

    /** The door (lower half) among the next few steps of this mob's path, if it's close to it. */
    @Nullable
    private static BlockPos doorAhead(Mob mob) {
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return null;
        }
        Level level = mob.level();
        int end = Math.min(path.getNodeCount(), path.getNextNodeIndex() + DOOR_NEAR);
        for (int i = Math.max(0, path.getNextNodeIndex() - 1); i < end; i++) {
            Node node = path.getNode(i);
            BlockPos pos = node.asBlockPos();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && mob.position().distanceToSqr(Vec3.atBottomCenterOf(pos)) <= 2.5 * 2.5) {
                return pos;
            }
        }
        return null;
    }

    private static void checkDoor(Mob mob) {
        if (!(mob.getNavigation() instanceof GroundPathNavigation) || mob.isPassenger()) {
            return;
        }
        BlockPos door = doorAhead(mob);
        if (door == null) {
            return;
        }
        Level level = mob.level();
        long now = level.getGameTime();
        Map<Long, DoorClaim> claims = DOORS.computeIfAbsent(level, l -> new HashMap<>());
        if (claims.size() > 256) {
            claims.values().removeIf(c -> now - c.seen > 40);
        }
        DoorClaim claim = claims.get(door.asLong());
        Entity holder = claim == null ? null : level.getEntity(claim.mobId);
        // the doorway is free when nobody holds it, the holder has gone through (stopped refreshing), or has hogged it too long
        if (claim == null || claim.mobId == mob.getId() || !(holder instanceof Mob other) || !other.isAlive()
                || now - claim.seen > 8 || now - claim.since > 100) {
            if (claim == null || claim.mobId != mob.getId()) {
                claim = new DoorClaim();
                claim.mobId = mob.getId();
                claim.since = now;
                claims.put(door.asLong(), claim);
            }
            claim.seen = now;
            return;
        }
        if (other.getTarget() == mob || mob.getTarget() == other) {
            return;   // no manners between hunter and hunted
        }
        yieldTo(mob, door);
    }

    /** Step back from the doorway on our own side and wait for the other one to come through. */
    private static void yieldTo(Mob mob, BlockPos door) {
        Yield yield = new Yield();
        yield.door = door.immutable();
        long now = mob.level().getGameTime();
        yield.until = now + 10;
        yield.giveUp = now + 100;
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            Optional<WalkTarget> walk = brain.getMemory(MemoryModuleType.WALK_TARGET);
            yield.saved = walk.orElse(null);
        }
        YIELDS.put(mob, yield);
        Vec3 doorAt = Vec3.atBottomCenterOf(door);
        Vec3 away = mob.position().subtract(doorAt).multiply(1, 0, 1);
        away = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
        Vec3 back = mob.position().add(away.scale(1.5));
        stopWalking(mob);
        walkTo(mob, BlockPos.containing(back), 0);
    }

    private static void tickYield(Mob mob, Yield yield) {
        Level level = mob.level();
        long now = level.getGameTime();
        Map<Long, DoorClaim> claims = DOORS.get(level);
        DoorClaim claim = claims == null ? null : claims.get(yield.door.asLong());
        boolean held = claim != null && claim.mobId != mob.getId() && now - claim.seen <= 8 && level.getEntity(claim.mobId) instanceof Mob other && other.isAlive();
        if (held && now < yield.giveUp) {
            yield.until = now + 10;
        }
        if (now < yield.until) {
            mob.getLookControl().setLookAt(Vec3.atCenterOf(yield.door));
            return;
        }
        // our turn: take the doorway and carry on where we were going
        YIELDS.remove(mob);
        if (claims != null) {
            DoorClaim mine = new DoorClaim();
            mine.mobId = mob.getId();
            mine.since = now;
            mine.seen = now;
            claims.put(yield.door.asLong(), mine);
        }
        Brain<?> brain = mob.getBrain();
        if (yield.saved != null && brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, yield.saved);
        }
    }

    // ------------------------------------------------------------------ moving brain and goal mobs alike

    private static void walkTo(Mob mob, BlockPos pos, int closeEnough) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, 0.6F, closeEnough));
        } else {
            Path path = mob.getNavigation().createPath(pos, closeEnough);
            if (path != null) {
                mob.getNavigation().moveTo(path, 0.8);
            }
        }
    }

    private static void stopWalking(Mob mob) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        mob.getNavigation().stop();
    }
}
