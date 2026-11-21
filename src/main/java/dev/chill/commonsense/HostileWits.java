package dev.chill.commonsense;

import dev.chill.commonsense.mixin.MobGoalsAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Hostile (and angry neutral) mobs that can't get to you act on what they understand, instead of pressing into a wall or
 * wandering off. What a mob is is read from what it does, never from a list of mods:
 * <ul>
 *   <li>Brawlers (anything with a melee attack goal: zombies, husks, drowned, zombified piglins, golems...) hang around the
 *       closest spot they can reach and keep an eye out for you.</li>
 *   <li>Shooters (bows, crossbows, potions, fireballs: skeletons, strays, witches, blazes...) look for a spot with a clear
 *       shot, through a window or a gap in the wall, and take it. While they can see you they fight as usual.</li>
 *   <li>Mobs that know doors (raiders like pillagers and vindicators, any door-opening mob) wait on your doorstep, or watch
 *       you through a window.</li>
 *   <li>Creepers stay dim: they drift about well back from your walls and never wait at the door.</li>
 *   <li>Climbers (spiders, and any mob on wall-climbing navigation) lie in wait out of sight, up on the roof or wall above
 *       your door, and drop or leap on you when you come out. While they can see you, their own chase runs (vanilla, or a
 *       climbing mod like Spiders). While waiting they carry the entity tag {@value #AMBUSH_TAG} so such mods can tell.</li>
 * </ul>
 * Fliers, swimmers, tamed pets and brain-driven mobs keep their own pathing. The goal
 * only runs while the target is out of reach, and checks that once a second, so it costs nothing in a normal fight.
 */
public final class HostileWits {
    /** Entity tag a climber carries while it lies in wait, so other mods' chase logic can stand aside. */
    public static final String AMBUSH_TAG = "commonsense.ambush";

    private HostileWits() {
    }

    /**
     * Whether a fresh path really gets to {@code pos}. Vanilla createPath hands back the path the mob is already walking when the
     * last path it planned was to the same spot, and that one "reaches" its own end, somewhere else entirely.
     */
    static boolean reaches(@Nullable Path path, BlockPos pos) {
        return path != null && path.canReach() && path.getEndNode() != null && path.getEndNode().asBlockPos().closerThan(pos, 2.0);
    }

    /** A path can come back with no nodes at all: then it has no end and goes nowhere. */
    static boolean hasEnd(@Nullable Path path) {
        return path != null && path.getEndNode() != null;
    }

    static boolean enabledFor(PathfinderMob mob) {
        // tamed pets have their own rule (give up on unreachable targets)
        return Config.on() && Config.HOSTILE_WITS.get() && !(mob instanceof OwnableEntity own && own.getOwnerUUID() != null)
                && !mob.isOnFire() && !mob.isLeashed() && !mob.isPassenger();
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob) || mob.getType().is(CommonSense.EXCLUDED)) {
            return;
        }
        if (!(mob instanceof Enemy) && !(mob instanceof NeutralMob)) {
            return;
        }
        if (mob.getNavigation() instanceof FlyingPathNavigation || mob.getNavigation() instanceof WaterBoundPathNavigation) {
            return;   // fliers and swimmers reach you their own way
        }
        GoalSelector goals = ((MobGoalsAccessor) mob).commonsense$goalSelector();
        if (goals.getAvailableGoals().isEmpty()) {
            return;   // brain-driven
        }
        for (WrappedGoal wrapped : goals.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof WaitOutGoal || wrapped.getGoal() instanceof AmbushGoal) {
                return;
            }
        }
        goals.addGoal(1, mob.getNavigation() instanceof WallClimberNavigation ? new AmbushGoal(mob) : new WaitOutGoal(mob));
    }

    static boolean smart(PathfinderMob mob) {
        // the node evaluator's flag, not GroundPathNavigation.canOpenDoors(): that one reports canPassDoors, true for every walker
        return mob instanceof Raider || (mob.getNavigation() instanceof GroundPathNavigation ground && ground.getNodeEvaluator().canOpenDoors());
    }

    /** Wait near a target that can't be reached: mill about (simple mobs) or hold a door or window spot (smart mobs). */
    public static final class WaitOutGoal extends Goal {
        private static final int CHECK = 20;
        private final PathfinderMob mob;
        private final boolean smart;
        private final boolean creeper;
        private int unreachableChecks;
        private int nextCheck;
        private int nextMove;
        private long lingerUntil;
        @Nullable
        private LivingEntity quarry;
        @Nullable
        private Vec3 lastSeen;
        @Nullable
        private BlockPos spot;
        @Nullable
        private Path approach;
        @Nullable
        private BlockPos millAnchor;
        @Nullable
        private BlockPos lastTo;
        private String windowTrace = "-";
        private String exitWhy = "-";
        private String reachWhy = "-";

        WaitOutGoal(PathfinderMob mob) {
            this.mob = mob;
            this.smart = smart(mob);
            this.creeper = mob instanceof Creeper;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean enabled() {
            return enabledFor(this.mob);
        }

        @Override
        public boolean canUse() {
            if (!enabled()) {
                this.unreachableChecks = 0;
                return false;
            }
            LivingEntity target = this.mob.getTarget();
            if (target == null || !target.isAlive() || target.level() != this.mob.level()) {
                this.unreachableChecks = 0;
                return false;
            }
            if (--this.nextCheck > 0) {
                return false;
            }
            this.nextCheck = CHECK;
            // a shooter with a clear shot needs no path: check the cheap thing first
            if ((shooter() && this.mob.getSensing().hasLineOfSight(target)) || reachable(target)) {
                this.unreachableChecks = 0;
                return false;
            }
            return ++this.unreachableChecks >= 2;
        }

        @Override
        public void start() {
            this.quarry = this.mob.getTarget();
            this.lastSeen = this.quarry == null ? null : this.quarry.position();
            this.lingerUntil = now() + Config.HOSTILE_LINGER_SECONDS.get() * 20L;
            this.spot = null;
            this.nextMove = 0;
            this.nextCheck = CHECK;
        }

        @Override
        public boolean canContinueToUse() {
            if (!enabled()) {
                return false;
            }
            LivingEntity target = this.mob.getTarget();
            if (target == null || !target.isAlive()) {
                return this.lastSeen != null && now() < this.lingerUntil;   // lost sight of you: hang about a while
            }
            if (target.level() != this.mob.level()) {
                return false;
            }
            if (target != this.quarry) {
                this.quarry = target;
                this.spot = null;
            }
            if (--this.nextCheck <= 0) {
                this.nextCheck = CHECK;
                if (reachable(target) || (shooter() && this.mob.getSensing().hasLineOfSight(target))) {
                    this.exitWhy = this.reachWhy;
                    return false;   // a way in opened up, or there's a shot: back to the fight
                }
            }
            return true;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target != null && target.isAlive()) {
                this.lingerUntil = now() + Config.HOSTILE_LINGER_SECONDS.get() * 20L;
                if (this.mob.getSensing().hasLineOfSight(target)) {
                    this.lastSeen = target.position();
                    this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                } else if (this.lastSeen == null) {
                    this.lastSeen = target.position();
                }
            }
            if (this.lastSeen != null && (target == null || !this.mob.getSensing().hasLineOfSight(target))) {
                this.mob.getLookControl().setLookAt(this.lastSeen.x, this.lastSeen.y + 1.0, this.lastSeen.z);
            }
            if (--this.nextMove > 0) {
                return;
            }
            if (this.smart || shooter()) {
                holdPost(target);
            } else {
                millAbout(target);
            }
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
            this.spot = null;
            this.millAnchor = null;
            this.quarry = null;
            this.lastSeen = null;
            this.unreachableChecks = 0;
        }

        /**
         * Fights from range: holding a bow or crossbow, or has no melee attack at all (witches, blazes, evokers). A skeleton that
         * lost its bow switches to its melee goal, so it counts as a brawler again.
         */
        private boolean shooter() {
            if (this.mob instanceof RangedAttackMob && this.mob.isHolding(stack -> stack.getItem() instanceof ProjectileWeaponItem)) {
                return true;
            }
            for (WrappedGoal wrapped : ((MobGoalsAccessor) this.mob).commonsense$goalSelector().getAvailableGoals()) {
                if (wrapped.getGoal() instanceof MeleeAttackGoal) {
                    return false;
                }
            }
            return true;
        }

        private long now() {
            return this.mob.level().getGameTime();
        }

        private boolean reachable(LivingEntity target) {
            double reach = (this.mob.getBbWidth() + target.getBbWidth()) / 2.0 + 1.0;
            if (this.mob.distanceToSqr(target) <= reach * reach && this.mob.getSensing().hasLineOfSight(target)) {
                this.reachWhy = "close";
                return true;
            }
            Path walking = this.mob.getNavigation().getPath();
            if (walking != null && !walking.isDone() && reaches(walking, target.blockPosition())) {
                this.reachWhy = "walking";   // already on a path that gets there: no need to plan another
                return true;
            }
            Path path = this.mob.getNavigation().createPath(target, 0);
            this.reachWhy = !hasEnd(path) ? "nopath" : "path:" + path.getNodeCount() + "->" + path.getEndNode().asBlockPos().toShortString();
            return reaches(path, target.blockPosition());
        }

        /** For the dev scenarios' trace: why the wait last ended. */
        public String exitWhy() {
            return this.exitWhy;
        }

        /** Simple mobs: wander a few blocks around the closest point they can get to. */
        private void millAbout(@Nullable LivingEntity target) {
            this.nextMove = 60 + this.mob.getRandom().nextInt(60);
            BlockPos anchor = this.millAnchor;
            if (anchor == null || target != null) {
                Path toward = target != null ? this.mob.getNavigation().createPath(target, 0)
                        : this.lastSeen != null ? this.mob.getNavigation().createPath(BlockPos.containing(this.lastSeen), 0) : null;
                anchor = hasEnd(toward) ? toward.getEndNode().asBlockPos() : this.mob.blockPosition();
                this.millAnchor = anchor;
            }
            var random = this.mob.getRandom();
            BlockPos to = anchor.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
            if (this.creeper && this.lastSeen != null) {
                // creepers are dim and skittish: they drift about well back from where you went, never up to your walls
                Vec3 away = Vec3.atBottomCenterOf(anchor).subtract(this.lastSeen).multiply(1, 0, 1);
                away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
                double back = 5.0 + random.nextInt(4);
                to = BlockPos.containing(anchor.getX() + away.x * back + random.nextInt(5) - 2, anchor.getY(),
                        anchor.getZ() + away.z * back + random.nextInt(5) - 2);
            }
            this.lastTo = to;
            Path path = this.mob.getNavigation().createPath(to, 0);
            if (path != null) {
                this.mob.getNavigation().moveTo(path, 0.8);
            }
        }

        /** For the dev scenarios' trace. */
        public String debug() {
            return "spot=" + this.spot + " anchor=" + this.millAnchor + " to=" + this.lastTo + " seen=" + this.lastSeen + " win=" + this.windowTrace;
        }

        /** Shooters and door-wise mobs: pick a window or door spot once, walk there, and stand watch. */
        private void holdPost(@Nullable LivingEntity target) {
            this.nextMove = 40;
            if (this.spot == null) {
                Vec3 about = target != null ? target.position() : this.lastSeen;
                if (about == null) {
                    return;
                }
                boolean shooter = shooter();
                boolean doors = this.smart;
                // drop the mill-about path first: createPath hands that back instead of planning, if the spot it's asked for
                // is the one it planned last (the same window spot, tried again after a miss)
                this.mob.getNavigation().stop();
                BlockPos door = !shooter && doors ? findDoorPost(this.mob, about) : null;
                BlockPos window = door == null && target != null && (shooter || this.smart) ? findWindowPost(target) : null;
                if (door == null && window == null && shooter && doors) {
                    door = findDoorPost(this.mob, about);
                }
                this.spot = door != null ? door : window;
                if (this.spot == null && this.approach != null) {
                    // every view spot is past this mob's planning range: head toward the nearest and look again from closer
                    this.mob.getNavigation().moveTo(this.approach, 0.9);
                    this.approach = null;
                    this.nextMove = 20;
                    return;
                }
                if (this.spot == null) {
                    this.nextMove = 100;   // nowhere sensible yet: try again in a few seconds, meanwhile wait like anyone else
                    millAbout(target);
                    return;
                }
            }
            if (this.mob.position().distanceToSqr(Vec3.atBottomCenterOf(this.spot)) > 0.5) {   // the exact spot: a window's view is narrow
                Path path = this.mob.getNavigation().createPath(this.spot, 0);
                if (reaches(path, this.spot)) {
                    this.mob.getNavigation().moveTo(path, 0.9);
                } else {
                    this.spot = null;   // the way there closed: choose again
                }
            } else {
                this.mob.getNavigation().stop();
            }
        }

        /** The outside step of the nearest door around the target that this mob can walk up to. */
        @Nullable
        static BlockPos findDoorPost(PathfinderMob mob, Vec3 about) {
            Level level = mob.level();
            BlockPos center = BlockPos.containing(about);
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -3, -8), center.offset(8, 3, 8))) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.is(BlockTags.DOORS) || !(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                double dist = pos.distToCenterSqr(about);
                if (dist >= bestDist) {
                    continue;
                }
                Direction facing = state.getValue(DoorBlock.FACING);
                for (BlockPos side : new BlockPos[] {pos.relative(facing), pos.relative(facing.getOpposite())}) {
                    // walkable ground is enough here: a wide mob's box would brush the wall beside the door
                    if (!level.isLoaded(side) || WalkNodeEvaluator.getPathTypeStatic(mob, side) != PathType.WALKABLE) {
                        continue;
                    }
                    Path path = mob.getNavigation().createPath(side, 0);
                    // a mob wider than a block (spiders) can't fit the node right against the wall: close beside it will do
                    int slack = mob.getBbWidth() > 1.0F ? 2 : 0;
                    if (hasEnd(path) && (reaches(path, side) || (slack > 0 && path.getEndNode().asBlockPos().distManhattan(side) <= slack))) {
                        best = side.immutable();
                        bestDist = dist;
                        break;
                    }
                }
            }
            return best;
        }

        /**
         * A spot 3-12 blocks out with a clear view of the target (an open window, a gap in the wall) that this mob can reach.
         * Looks outward from the target along 32 rays, so a one-block window is found without testing every block around.
         */
        @Nullable
        private BlockPos findWindowPost(LivingEntity target) {
            Level level = this.mob.level();
            Vec3 eye = target.getEyePosition();
            // gather every spot with a view first (cheap ray casts), then path-test only the few nearest the mob:
            // testing in ray order spent the budget on far-side spots out of pathfinding range
            List<BlockPos> seen = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                double angle = i * Math.PI / 16.0;
                Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
                Vec3 end = eye.add(dir.scale(12.0));
                var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
                double free = hit.getType() == HitResult.Type.MISS ? 12.0 : hit.getLocation().distanceTo(eye);
                for (double d = 3.0; d <= free - 0.5; d += 1.5) {
                    Vec3 at = eye.add(dir.scale(d));
                    BlockPos pos = standableNear(level, BlockPos.containing(at.x, target.getY(), at.z));
                    if (pos == null || seen.contains(pos)) {
                        continue;
                    }
                    Vec3 from = new Vec3(pos.getX() + 0.5, pos.getY() + this.mob.getEyeHeight(), pos.getZ() + 0.5);
                    if (level.clip(new ClipContext(from, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS) {
                        seen.add(pos.immutable());
                    }
                }
            }
            BlockPos here = this.mob.blockPosition();
            seen.sort(Comparator.comparingDouble(here::distSqr));
            this.approach = null;
            for (int i = 0; i < Math.min(6, seen.size()); i++) {
                BlockPos pos = seen.get(i);
                Path path = this.mob.getNavigation().createPath(pos, 0);
                this.windowTrace = seen.size() + ":" + pos.toShortString() + (!hasEnd(path) ? ":nopath" : reaches(path, pos) ? ":ok" : ":end@" + path.getEndNode().asBlockPos().toShortString());
                if (reaches(path, pos)) {
                    return pos;
                }
                if (this.approach == null && hasEnd(path) && path.getNodeCount() > 1
                        && path.getEndNode().asBlockPos().distSqr(pos) < here.distSqr(pos) * 0.5) {
                    this.approach = path;   // gets well over halfway there: worth walking while nothing closer works
                }
                // a path that stops a step short still serves if the spot it stops on has the same view
                if (hasEnd(path) && !path.canReach() && path.getEndNode().asBlockPos().closerThan(pos, 2.0)) {
                    BlockPos stop = path.getEndNode().asBlockPos();
                    Vec3 from = new Vec3(stop.getX() + 0.5, stop.getY() + this.mob.getEyeHeight(), stop.getZ() + 0.5);
                    if (level.clip(new ClipContext(from, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS) {
                        Path again = this.mob.getNavigation().createPath(stop, 0);
                        if (reaches(again, stop)) {
                            this.windowTrace += ":short-ok";
                            return stop;
                        }
                    }
                }
            }
            return null;
        }

        @Nullable
        private BlockPos standableNear(Level level, BlockPos column) {
            for (int dy = 1; dy >= -4; dy--) {
                BlockPos pos = column.above(dy);
                if (level.isLoaded(pos) && Companions.isSafeStandPos(this.mob, pos)) {
                    return pos;
                }
            }
            return null;
        }
    }

    /**
     * A climber that has lost sight of its target waits where you'll come out: up on the roof edge or wall top above your door
     * (or above the closest spot it can get to), out of your eye line. When you show up within pouncing range it leaps on you;
     * at a distance it hands back to its normal chase.
     */
    public static final class AmbushGoal extends Goal {
        private static final int CHECK = 20;
        private static final double POUNCE = 6.0;
        private final PathfinderMob mob;
        private int unseenChecks;
        private int nextCheck;
        private int nextMove;
        private long lingerUntil;
        @Nullable
        private Vec3 lastSeen;
        @Nullable
        private BlockPos perch;

        AmbushGoal(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            if (!enabledFor(this.mob) || target == null || !target.isAlive() || target.level() != this.mob.level()) {
                this.unseenChecks = 0;
                return false;
            }
            if (--this.nextCheck > 0) {
                return false;
            }
            this.nextCheck = CHECK;
            if (this.mob.getSensing().hasLineOfSight(target) || this.mob.distanceToSqr(target) > 24 * 24) {
                this.unseenChecks = 0;
                return false;
            }
            return ++this.unseenChecks >= 2;
        }

        @Override
        public void start() {
            LivingEntity target = this.mob.getTarget();
            this.lastSeen = target != null ? target.position() : null;
            this.lingerUntil = this.mob.level().getGameTime() + Config.HOSTILE_LINGER_SECONDS.get() * 20L;
            this.perch = null;
            this.nextMove = 0;
            this.mob.addTag(AMBUSH_TAG);
        }

        @Override
        public boolean canContinueToUse() {
            if (!enabledFor(this.mob) || this.lastSeen == null) {
                return false;
            }
            LivingEntity target = this.mob.getTarget();
            if (target == null || !target.isAlive()) {
                return this.mob.level().getGameTime() < this.lingerUntil;
            }
            if (target.level() != this.mob.level()) {
                return false;
            }
            // you're in plain view but out of pouncing range: let the normal chase have you
            return !(this.mob.getSensing().hasLineOfSight(target) && this.mob.distanceToSqr(target) > POUNCE * POUNCE);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target != null && target.isAlive()) {
                this.lingerUntil = this.mob.level().getGameTime() + Config.HOSTILE_LINGER_SECONDS.get() * 20L;
                boolean close = this.mob.distanceToSqr(target) <= POUNCE * POUNCE && target.getY() <= this.mob.getY() + 1.0;
                if (this.mob.getSensing().hasLineOfSight(target) || (close && overTheLip(target))) {
                    this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    if (close) {
                        pounce(target);
                        return;
                    }
                    this.lastSeen = target.position();
                }
            }
            if (--this.nextMove > 0) {
                return;
            }
            this.nextMove = 20;
            if (this.perch == null) {
                this.perch = findPerch();
                if (this.perch == null) {
                    this.nextMove = 100;
                    return;
                }
            }
            Vec3 at = Vec3.atBottomCenterOf(this.perch);
            Vec3 off = at.subtract(this.mob.position());
            double flat = off.x * off.x + off.z * off.z;
            if (this.mob.onGround() && flat <= 1.0 && Math.abs(off.y) < 0.6) {
                this.mob.getNavigation().stop();   // up on the perch, feet on the roof: wait
            } else if (flat <= 9.0 && off.y > -0.5) {
                // the last stretch: steer straight at it so it climbs the face and steps over the lip instead of hanging there
                this.nextMove = 1;
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(at.x, at.y, at.z, 1.0);
            } else if (this.mob.onGround() && !this.mob.horizontalCollision) {
                // climbing navigation walks straight at a spot it has no ground path to, scaling the wall on the way;
                // re-aiming mid-climb would hand it a fresh ground path and drop it back down
                this.mob.getNavigation().moveTo(at.x, at.y, at.z, 1.0);
            }
        }

        @Nullable
        public BlockPos perch() {
            return this.perch;
        }

        @Override
        public void stop() {
            this.mob.removeTag(AMBUSH_TAG);
            this.perch = null;
            this.lastSeen = null;
            this.unseenChecks = 0;
        }

        /** Crouched back from a roof edge its own eyes can't see down past the lip; peering over it, they could. */
        private boolean overTheLip(LivingEntity target) {
            Vec3 eye = this.mob.getEyePosition();
            Vec3 to = target.getEyePosition().subtract(eye);
            Vec3 flat = new Vec3(to.x, 0.0, to.z);
            if (flat.lengthSqr() < 1.0E-4) {
                return false;
            }
            Vec3 from = eye.add(flat.normalize().scale(this.mob.getBbWidth() / 2.0 + 0.5));
            return this.mob.level().clip(new ClipContext(from, target.getEyePosition(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS;
        }

        private void pounce(LivingEntity target) {
            Vec3 to = target.position().subtract(this.mob.position());
            Vec3 flat = new Vec3(to.x, 0.0, to.z);
            double len = flat.length();
            Vec3 push = len < 1.0E-4 ? Vec3.ZERO : flat.scale(Math.min(0.6, 0.15 + len * 0.08) / len);   // dropping from a height carries it far enough
            this.mob.setDeltaMovement(push.x, to.y > -1.0 ? 0.35 : 0.1, push.z);
            this.mob.hasImpulse = true;
            this.mob.getNavigation().stop();
            this.lastSeen = null;   // ends the wait: the chase and its bite take it from here
        }

        /** Lower half of the door nearest {@code about}, within 8 blocks across and 3 up or down. */
        @Nullable
        private static BlockPos nearestDoor(Level level, Vec3 about) {
            BlockPos center = BlockPos.containing(about);
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -3, -8), center.offset(8, 3, 8))) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    double dist = pos.distToCenterSqr(about);
                    if (dist < bestDist) {
                        best = pos.immutable();
                        bestDist = dist;
                    }
                }
            }
            return best;
        }

        /** Roof edge or wall top above the spot you'll come out of: your door, else the nearest point it can walk to. */
        @Nullable
        private BlockPos findPerch() {
            if (this.lastSeen == null) {
                return null;
            }
            Level level = this.mob.level();
            BlockPos door = nearestDoor(level, this.lastSeen);
            if (door != null) {
                // straight up the wall from the door to the first spot with footing: the lip right above where you'll come out.
                // A climber gets there up the wall, so no ground path is needed
                for (int dy = 2; dy <= 7; dy++) {
                    BlockPos pos = door.above(dy);
                    if (!level.isLoaded(pos)) {
                        break;
                    }
                    if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                            && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                        return pos;
                    }
                }
            }
            BlockPos base = WaitOutGoal.findDoorPost(this.mob, this.lastSeen);
            if (base == null) {
                Path toward = this.mob.getNavigation().createPath(BlockPos.containing(this.lastSeen), 0);
                base = hasEnd(toward) ? toward.getEndNode().asBlockPos() : null;
            }
            if (base == null) {
                return null;
            }
            BlockPos best = null;
            for (Direction dir : new Direction[] {null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos column = dir == null ? base : base.relative(dir);
                for (int dy = 2; dy <= 6; dy++) {
                    BlockPos pos = column.above(dy);
                    if (level.isLoaded(pos) && Companions.isSafeStandPos(this.mob, pos)) {
                        if (best == null || pos.getY() < best.getY()) {
                            best = pos;
                        }
                        break;
                    }
                }
            }
            return best != null ? best : base;   // nothing to perch on: lurk on the doorstep itself
        }
    }
}
