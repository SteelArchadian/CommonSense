package dev.chill.commonsense;

import dev.chill.commonsense.mixin.MobGoalsAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Running away smartly. Small mobs (chickens, rabbits, pigs, babies...) that are being attacked duck into a hole too small for
 * the attacker, thick ground foliage or a hollow log, unless the attacker is small too. Villagers (and baby villagers) under
 * attack run for a door and put it between themselves and the enemy. Holes are found by shape (fits me, not a big mob), so
 * any mod's burrow or hollow log works with no per-mod code.
 */
public final class Hide {

    private static final int RANGE = 10;
    private static final int MAX_TICKS = 300;
    private static final double SMALL = 0.9;
    private static final double PLAYER_W = 0.6;
    private static final double PLAYER_H = 1.8;

    private Hide() {
    }

    private static boolean small(LivingEntity e) {
        return e.getBbWidth() <= SMALL && e.getBbHeight() <= SMALL;
    }

    @Nullable
    private static LivingEntity attacker(Mob mob) {
        LivingEntity hurt = mob.getLastHurtByMob();
        return hurt != null && hurt.isAlive() && !hurt.isSpectator() ? hurt : null;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob) || mob instanceof Enemy || mob instanceof Villager) {
            return;
        }
        GoalSelector goals = ((MobGoalsAccessor) mob).commonsense$goalSelector();
        if (goals.getAvailableGoals().isEmpty()) {
            return;   // brain-driven
        }
        for (WrappedGoal wrapped : goals.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof HideGoal) {
                return;
            }
        }
        goals.addGoal(-1, new HideGoal(mob));   // outranks the herd bolt (priority 0)
    }

    /** A cell that fits {@code mob}, is enclosed so a person-sized mob can't follow, and (unless it is foliage) the attacker can't fit either. */
    private static boolean hole(Level level, Mob mob, LivingEntity threat, BlockPos p) {
        BlockState here = level.getBlockState(p);
        double lift = 0;
        if (!here.getCollisionShape(level, p).isEmpty()) {
            VoxelShape shape = here.getCollisionShape(level, p);
            lift = shape.max(net.minecraft.core.Direction.Axis.Y);
            if (lift > 0.3) {
                return false;
            }
        } else if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
            return false;
        }
        double cx = p.getX() + 0.5;
        double cz = p.getZ() + 0.5;
        double y = p.getY() + lift + 0.001;
        float w = mob.getBbWidth();
        float h = mob.getBbHeight();
        AABB mine = new AABB(cx - w / 2, y, cz - w / 2, cx + w / 2, y + h, cz + w / 2);
        if (!level.noCollision(mob, mine)) {
            return false;
        }
        AABB big = new AABB(cx - PLAYER_W / 2, y, cz - PLAYER_W / 2, cx + PLAYER_W / 2, y + PLAYER_H, cz + PLAYER_W / 2);
        if (level.noCollision(mob, big)) {
            return false;
        }
        double tw = threat.getBbWidth();
        double th = threat.getBbHeight();
        AABB theirs = new AABB(cx - tw / 2, y, cz - tw / 2, cx + tw / 2, y + th, cz + tw / 2);
        if (level.noCollision(mob, theirs)) {
            return false;
        }
        int sides = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.noCollision(mob, mine.move(d.getStepX() * 0.7, 0, d.getStepZ() * 0.7))) {
                sides++;
            }
        }
        return sides >= 2;
    }

    /** The closest reachable hiding place that is not toward the attacker, or null. */
    @Nullable
    public static BlockPos findHide(PathfinderMob mob, LivingEntity threat) {
        Level level = mob.level();
        BlockPos o = mob.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        double threatD = mob.distanceToSqr(threat);
        boolean smallThreat = small(threat);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dz = -RANGE; dz <= RANGE; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.setWithOffset(o, dx, dy, dz);
                    double d = o.distSqr(p);
                    if (d >= bestD || d > RANGE * RANGE || !level.isLoaded(p) || p.distSqr(threat.blockPosition()) < 9) {
                        continue;
                    }
                    if (p.distSqr(threat.blockPosition()) < threatD * 0.5 && d > 4) {
                        continue;   // not toward the attacker
                    }
                    BlockState state = level.getBlockState(p);
                    boolean ok = (!smallThreat && foliageCell(level, p, state)) || (!smallThreat && hole(level, mob, threat, p));
                    if (ok) {
                        BlockPos q = p.immutable();
                        Path path = mob.getNavigation().createPath(q, 0);
                        if (path != null && path.canReach()) {
                            best = q;
                            bestD = d;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean foliageCell(Level level, BlockPos p, BlockState state) {
        if (state.is(Blocks.FERN)) {
            return true;
        }
        return state.getBlock() instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER
                && state.getCollisionShape(level, p).isEmpty();
    }

    static final class HideGoal extends Goal {
        private final PathfinderMob mob;
        @Nullable
        private LivingEntity threat;
        @Nullable
        private BlockPos spot;
        private int age;
        private int nextScan;

        HideGoal(PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (!Config.on() || !Config.HIDE_FROM_ATTACKERS.get() || this.mob.isLeashed() || this.mob.isPassenger() || this.mob.isVehicle()
                    || this.mob.tickCount < this.nextScan || !this.mob.onGround()) {
                return false;
            }
            LivingEntity hurtBy = attacker(this.mob);
            if (hurtBy == null || small(hurtBy)) {
                return false;
            }
            this.nextScan = this.mob.tickCount + 20;
            BlockPos found = findHide(this.mob, hurtBy);
            if (found == null) {
                return false;
            }
            this.threat = hurtBy;
            this.spot = found;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return this.threat != null && this.threat.isAlive() && this.spot != null && this.age < MAX_TICKS
                    && this.mob.distanceToSqr(this.threat) < 20 * 20;
        }

        @Override
        public void start() {
            this.age = 0;
            this.go();
        }

        private void go() {
            if (this.spot != null) {
                this.mob.getNavigation().moveTo(this.mob.getNavigation().createPath(this.spot, 0), 1.4);   // exact cell: moveTo(x,y,z) stops a block short
            }
        }

        @Override
        public void tick() {
            this.age++;
            if (this.spot != null && this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.spot)) < 0.5) {
                this.mob.getNavigation().stop();
            } else if (this.mob.getNavigation().isDone() && this.age % 10 == 0) {
                this.go();
            }
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
            this.threat = null;
            this.spot = null;
        }
    }

    /** Villagers under attack: a door to put between themselves and the enemy. Called every few ticks from the mob tick. */
    private static final java.util.Map<Villager, BlockPos> RUNNING = new java.util.WeakHashMap<>();

    static void tickVillager(Villager villager, boolean rescan) {
        BlockPos known = RUNNING.get(villager);
        if (!rescan && known == null) {
            return;
        }
        if (!rescan) {
            LivingEntity t = attacker(villager);
            if (t == null || villager.distanceToSqr(Vec3.atBottomCenterOf(known)) < 1.5 * 1.5) {
                RUNNING.remove(villager);
                return;
            }
            Optional<WalkTarget> cur = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            if (cur.isEmpty() || !cur.get().getTarget().currentBlockPosition().equals(known)) {
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(known, 1.0F, 0));
            }
            return;
        }
        if (!Config.on() || !Config.HIDE_FROM_ATTACKERS.get() || villager.isSleeping() || villager.isPassenger()) {
            return;
        }
        LivingEntity threat = attacker(villager);
        if (threat == null && villager.getBrain().isActive(Activity.PANIC)) {
            Optional<LivingEntity> hostile = villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE);
            threat = hostile.orElse(null);
        }
        if (threat == null || !threat.isAlive() || villager.distanceToSqr(threat) > 24 * 24) {
            return;
        }
        Level level = villager.level();
        BlockPos o = villager.blockPosition();
        Vec3 tpos = threat.position();
        BlockPos bestSide = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.setWithOffset(o, dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    if (!(state.getBlock() instanceof DoorBlock) || !state.is(BlockTags.WOODEN_DOORS)
                            || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                        continue;
                    }
                    double d = o.distSqr(p);
                    Vec3 door = Vec3.atBottomCenterOf(p);
                    if (d >= bestD || d >= door.distanceToSqr(tpos)) {
                        continue;   // the enemy would get there first
                    }
                    Direction facing = state.getValue(DoorBlock.FACING);
                    BlockPos a = p.relative(facing);
                    BlockPos b = p.relative(facing.getOpposite());
                    BlockPos far = Vec3.atBottomCenterOf(a).distanceToSqr(tpos) > Vec3.atBottomCenterOf(b).distanceToSqr(tpos) ? a : b;
                    bestSide = far.immutable();
                    bestD = d;
                }
            }
        }
        if (bestSide == null || villager.distanceToSqr(Vec3.atBottomCenterOf(bestSide)) < 1.5 * 1.5) {
            return;
        }
        Path path = villager.getNavigation().createPath(bestSide, 0);
        if (path == null || !path.canReach()) {
            return;
        }
        RUNNING.put(villager, bestSide);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(bestSide, 1.0F, 0));
    }
}
