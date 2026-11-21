package dev.chill.commonsense;

import dev.chill.commonsense.mixin.MobGoalsAccessor;
import dev.chill.commonsense.mixin.PanicGoalAccessor;
import dev.chill.commonsense.mixin.TemptGoalAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * What a herd does around people, for goal-based animals (cows, sheep, pigs, horses, llamas...):
 * <ul>
 * <li>Wariness: a scared herd bolts the same way as a group, then keeps its distance from whoever hurt it for a while and
 * only follows food if that player sneaks (and then slowly). See {@link HerdInstinct}.</li>
 * <li>Herd follow: an animal near one that is being tempted follows it, so a straggler isn't left behind. It only follows
 * animals tempted directly by a player, never other followers, so a whole field can't snowball.</li>
 * </ul>
 * Brain-based animals (goats, camels, armadillos...) keep vanilla panic; they still get the tempt range and wariness checks.
 */
public final class HerdBehaviour {

    /** How long (ticks) the opening bolt lasts before the herd settles into keeping its distance. */
    static final int FLEE_TICKS = 100;
    /** A crouching player can come this close to a wary animal (TemptGoal stops at 2.5 anyway). */
    private static final double SNEAK_DISTANCE = 2.5;

    /** Last tick any animal was tempted, on any level: lets idle herds skip the neighbour search entirely. */
    private static long lastTemptTick = Long.MIN_VALUE / 2;

    private HerdBehaviour() {
    }

    @Nullable
    static CompanionState existing(Mob mob) {
        return mob.hasData(CommonSense.STATE) ? mob.getData(CommonSense.STATE) : null;
    }

    public static void markTempted(Mob mob) {
        long now = mob.level().getGameTime();
        Companions.state(mob).temptedTick = now;
        lastTemptTick = now;
    }

    static boolean directlyTempted(Mob mob, long now) {
        CompanionState st = existing(mob);
        return st != null && now - st.temptedTick <= 10;
    }

    public static boolean isWaryOf(Mob mob, @Nullable Entity entity) {
        CompanionState st = existing(mob);
        return st != null && entity != null && entity.getUUID().equals(st.waryOf) && mob.level().getGameTime() < st.waryUntil;
    }

    static boolean isWary(Mob mob) {
        CompanionState st = existing(mob);
        return st != null && st.waryOf != null && mob.level().getGameTime() < st.waryUntil;
    }

    /** Wary animals only follow food from the player they fear if that player is sneaking. */
    public static boolean refusesTempt(Mob mob, @Nullable Entity player) {
        return player != null && !player.isCrouching() && isWaryOf(mob, player);
    }

    /** The whole frightened group at once, so they bolt the same way and stay bunched. */
    static void scare(List<Mob> herd, LivingEntity attacker) {
        if (herd.isEmpty()) {
            return;
        }
        Vec3 center = Vec3.ZERO;
        for (Mob m : herd) {
            center = center.add(m.position());
        }
        center = center.scale(1.0 / herd.size());
        Vec3 away = new Vec3(center.x - attacker.getX(), 0, center.z - attacker.getZ());
        if (away.lengthSqr() < 1.0E-4) {
            double a = attacker.getRandom().nextDouble() * Math.PI * 2;
            away = new Vec3(Math.cos(a), 0, Math.sin(a));
        }
        Vec3 goal = center.add(away.normalize().scale(Config.HERD_FLEE_DISTANCE.get()));
        long now = attacker.level().getGameTime();
        long waryUntil = now + Math.max(FLEE_TICKS, Config.HERD_WARY_SECONDS.get() * 20L);
        for (Mob m : herd) {
            CompanionState st = Companions.state(m);
            st.waryOf = attacker.getUUID();
            st.waryUntil = waryUntil;
            st.fleeTarget = goal.add(m.position().subtract(center).scale(0.4));   // same formation, pulled tighter
            st.fleeUntil = now + FLEE_TICKS;
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Animal animal) || animal instanceof Enemy) {
            return;
        }
        GoalSelector goals = ((MobGoalsAccessor) animal).commonsense$goalSelector();
        if (goals.getAvailableGoals().isEmpty()) {
            return;   // brain-driven: goals here would fight the brain for the navigation
        }
        int temptPriority = -1;
        double temptSpeed = 1.0;
        double panicSpeed = 1.5;
        for (WrappedGoal wrapped : goals.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (goal instanceof WaryGoal) {
                return;
            }
            if (goal instanceof TemptGoal tempt && temptPriority < 0) {
                temptPriority = wrapped.getPriority();
                temptSpeed = ((TemptGoalAccessor) tempt).commonsense$speed();
            } else if (goal instanceof PanicGoal panic) {
                panicSpeed = ((PanicGoalAccessor) panic).commonsense$speed();
            }
        }
        goals.addGoal(0, new WaryGoal(animal, panicSpeed));
        if (temptPriority >= 0) {
            goals.addGoal(temptPriority, new FollowTemptedGoal(animal, temptSpeed));
        }
    }

    /** Bolt with the herd, then back away whenever the feared one comes close, until the wariness wears off. */
    static final class WaryGoal extends Goal {
        private final PathfinderMob mob;
        private final double speed;
        private boolean fleeing;
        private int repath;

        WaryGoal(PathfinderMob mob, double speed) {
            this.mob = mob;
            this.speed = speed;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Nullable
        private LivingEntity threat(CompanionState st) {
            return st.waryOf != null && this.mob.level() instanceof ServerLevel level && level.getEntity(st.waryOf) instanceof LivingEntity e
                    && e.isAlive() && !e.isSpectator() ? e : null;
        }

        @Override
        public boolean canUse() {
            CompanionState st = existing(this.mob);
            if (st == null || st.waryOf == null || !Config.on() || !Config.HERD_INSTINCT.get()) {
                return false;
            }
            long now = this.mob.level().getGameTime();
            if (now >= st.waryUntil) {
                st.waryOf = null;
                st.fleeTarget = null;
                return false;
            }
            if (this.mob.isLeashed() || this.mob.isVehicle() || this.mob.isPassenger()) {
                return false;
            }
            this.fleeing = now < st.fleeUntil && st.fleeTarget != null;
            if (this.fleeing) {
                return true;
            }
            LivingEntity threat = this.threat(st);
            if (threat == null) {
                return false;
            }
            double keep = threat.isCrouching() ? SNEAK_DISTANCE : Config.HERD_WARY_DISTANCE.get();
            return this.mob.distanceToSqr(threat) < keep * keep;
        }

        @Override
        public boolean canContinueToUse() {
            return !this.mob.getNavigation().isDone() || this.canUse();
        }

        @Override
        public void start() {
            this.move();
        }

        @Override
        public void tick() {
            if (this.mob.getNavigation().isDone() && --this.repath <= 0) {
                this.move();
            }
        }

        private void move() {
            this.repath = 10;
            CompanionState st = existing(this.mob);
            if (st == null) {
                return;
            }
            if (this.fleeing && st.fleeTarget != null) {
                Vec3 t = st.fleeTarget;
                if (!this.mob.getNavigation().moveTo(t.x, t.y, t.z, this.speed)) {
                    Vec3 step = LandRandomPos.getPosTowards(this.mob, 10, 5, t);
                    if (step != null) {
                        this.mob.getNavigation().moveTo(step.x, step.y, step.z, this.speed);
                    }
                }
                return;
            }
            LivingEntity threat = this.threat(st);
            if (threat != null) {
                // Straight away from the threat to well past the comfort distance; a random "away" spot is too timid.
                Vec3 dir = new Vec3(this.mob.getX() - threat.getX(), 0, this.mob.getZ() - threat.getZ());
                dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : dir.normalize();
                Vec3 t = this.mob.position().add(dir.scale(Config.HERD_WARY_DISTANCE.get() + 2));
                double speed = Math.max(1.0, this.speed * 0.7);
                if (!this.mob.getNavigation().moveTo(t.x, t.y, t.z, speed)) {
                    Vec3 away = LandRandomPos.getPosAway(this.mob, 10, 5, threat.position());
                    if (away != null) {
                        this.mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
                    }
                }
            }
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }
    }

    /** Follow a nearby animal of the same kind that a player is tempting directly. */
    static final class FollowTemptedGoal extends Goal {
        private final PathfinderMob mob;
        private final double speed;
        @Nullable
        private Mob leader;
        private int repath;
        private int nextLook;

        FollowTemptedGoal(PathfinderMob mob, double speed) {
            this.mob = mob;
            this.speed = speed;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            long now = this.mob.level().getGameTime();
            if (now < lastTemptTick || now - lastTemptTick > 10 || --this.nextLook > 0) {   // now < last: a different world was loaded
                return false;   // cheap exits first: nobody is tempting anything, or looked very recently
            }
            this.nextLook = 2;   // canUse runs every other tick, so this looks every 4 ticks
            if (!Config.on() || !Config.HERD_FOLLOW.get() || this.mob.isLeashed() || this.mob.isPassenger()
                    || (this.mob instanceof Animal a && a.isInLove()) || isWary(this.mob) || directlyTempted(this.mob, now)) {
                return false;
            }
            double r = Config.HERD_FOLLOW_RANGE.get();
            Mob best = null;
            double bestSq = r * r;
            for (Mob m : this.mob.level().getEntitiesOfClass(Mob.class, this.mob.getBoundingBox().inflate(r),
                    m -> m != this.mob && m.getType() == this.mob.getType())) {
                double d = m.distanceToSqr(this.mob);
                if (d <= bestSq && directlyTempted(m, now)) {
                    best = m;
                    bestSq = d;
                }
            }
            this.leader = best;
            return best != null;
        }

        @Override
        public boolean canContinueToUse() {
            Mob l = this.leader;
            double keep = Config.HERD_FOLLOW_RANGE.get() * 3;
            return l != null && l.isAlive() && l.level() == this.mob.level() && Config.on() && Config.HERD_FOLLOW.get()
                    && directlyTempted(l, this.mob.level().getGameTime()) && !isWary(this.mob)
                    && this.mob.distanceToSqr(l) < keep * keep;
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void tick() {
            Mob l = this.leader;
            if (l == null) {
                return;
            }
            this.mob.getLookControl().setLookAt(l, 10.0F, this.mob.getMaxHeadXRot());
            if (this.mob.distanceToSqr(l) < 6.25) {
                this.mob.getNavigation().stop();
            } else if (--this.repath <= 0) {
                this.repath = 10;
                this.mob.getNavigation().moveTo(l, this.speed);
            }
        }

        @Override
        public void stop() {
            this.leader = null;
            this.mob.getNavigation().stop();
        }
    }
}
