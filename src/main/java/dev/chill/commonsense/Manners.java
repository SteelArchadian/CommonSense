package dev.chill.commonsense;

import dev.chill.commonsense.mixin.MobGoalsAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Small courtesies between players and the mobs they keep:
 * <ul>
 * <li>No friendly fire: your swings, sweeps and arrows don't hurt your own pets and mounts (sneak to mean it), and pets of
 * the same owner don't hurt each other.</li>
 * <li>Pet restraint: pets don't join in on villagers, golems or other pets you hit by accident, unless they started it.</li>
 * <li>Ground tie: a saddled mount you climb off stays where you left it instead of wandering away.</li>
 * <li>Creeper dodge: companions step away from a creeper that is about to blow.</li>
 * <li>No trampling: animals, pets and villagers don't trample farmland; only players (on foot) and monsters do.</li>
 * <li>Give up: a pet that has had no way to reach its target for a while drops it and comes back.</li>
 * </ul>
 * Everything works off vanilla interfaces (OwnableEntity, Saddleable, Creeper), so modded pets, mounts and creepers join in.
 */
public final class Manners {

    /** Saved with the mob, so a tied horse stays tied across restarts. */
    static final String TIE_KEY = "commonsense:tie";

    private Manners() {
    }

    // ---- no shoving ----

    /** If one of the two is a pet or mount owned by the other, the pet; otherwise null. Runs on both sides (the client pushes you). */
    @Nullable
    public static Entity petBumpingOwner(Entity a, Entity b) {
        if (!Config.on() || !Config.NO_PET_SHOVE.get()) {
            return null;
        }
        if (b instanceof Player && b.getUUID().equals(ownerOf(a))) {
            return a;
        }
        if (a instanceof Player && a.getUUID().equals(ownerOf(b))) {
            return b;
        }
        return null;
    }

    // ---- no friendly fire ----

    /** A shot from you (not sneaking) or from one of your pets, about to hit another of your pets or mounts. */
    public static boolean ownShotAtOwnPet(@Nullable Entity shooter, Entity target) {
        if (shooter == null || shooter == target || !Config.on() || !Config.NO_FRIENDLY_FIRE.get()) {
            return false;
        }
        UUID targetOwner = ownerOf(target);
        if (targetOwner == null) {
            return false;
        }
        if (shooter instanceof Player player) {
            return targetOwner.equals(player.getUUID()) && !player.isCrouching();
        }
        return targetOwner.equals(ownerOf(shooter));
    }

    @Nullable
    private static UUID ownerOf(Entity entity) {
        return entity instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null;
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!Config.on() || !Config.NO_FRIENDLY_FIRE.get() || event.getEntity().level().isClientSide()) {
            return;
        }
        UUID victimOwner = ownerOf(event.getEntity());
        Entity attacker = event.getSource().getEntity();
        if (victimOwner == null || attacker == null || attacker == event.getEntity()) {
            return;
        }
        boolean ownHit = attacker instanceof Player player && victimOwner.equals(player.getUUID()) && !player.isCrouching();
        boolean petOnPet = !(attacker instanceof Player) && victimOwner.equals(ownerOf(attacker));
        if (ownHit || petOnPet) {
            event.setCanceled(true);
            if (petOnPet && attacker instanceof Mob mob && mob.getTarget() == event.getEntity()) {
                mob.setTarget(null);
            }
        }
    }

    // ---- pet restraint ----

    /** Your pets don't maul a villager, golem or someone's pet just because you hit it by accident; they still defend you and themselves. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!Config.on() || !Config.PET_RESTRAINT.get() || !(event.getEntity() instanceof Mob pet) || pet.level().isClientSide()) {
            return;
        }
        UUID owner = ownerOf(pet);
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (owner == null || !(target instanceof Mob victim) || victim instanceof Enemy) {
            return;
        }
        boolean peaceful = victim instanceof AbstractVillager || VillageLogic.isVillageMob(victim) || ownerOf(victim) != null;
        if (!peaceful || startedIt(victim, pet, owner)) {
            return;
        }
        event.setCanceled(true);
    }

    /** The victim went for the owner or one of the owner's pets recently, so it is fair game. */
    private static boolean startedIt(Mob victim, Mob pet, UUID owner) {
        LivingEntity aimed = victim.getTarget();
        if (aimed != null && (owner.equals(aimed.getUUID()) || owner.equals(ownerOf(aimed)))) {
            return true;
        }
        LivingEntity hit = victim.getLastHurtMob();
        return hit != null && victim.tickCount - victim.getLastHurtMobTimestamp() < 200
                && (owner.equals(hit.getUUID()) || owner.equals(ownerOf(hit)));
    }

    // ---- no trampling ----

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        Entity e = event.getEntity();
        if (Config.on() && Config.NO_TRAMPLING.get() && !(e instanceof Player) && !(e instanceof Enemy)) {
            event.setCanceled(true);
        }
    }

    // ---- ground tie ----

    @Nullable
    public static BlockPos tie(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        return data.contains(TIE_KEY) ? BlockPos.of(data.getLong(TIE_KEY)) : null;
    }

    public static void setTie(Mob mob, @Nullable BlockPos pos) {
        if (pos == null) {
            mob.getPersistentData().remove(TIE_KEY);
        } else {
            mob.getPersistentData().putLong(TIE_KEY, pos.asLong());
        }
    }

    static boolean tieable(Entity entity) {
        return entity instanceof Mob mob && mob instanceof Saddleable saddle && saddle.isSaddled() && !(mob instanceof Enemy);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntityBeingMounted() instanceof Mob mount)) {
            return;
        }
        if (event.isMounting()) {
            setTie(mount, null);
        } else if (event.getEntityMounting() instanceof Player && Config.on() && Config.GROUND_TIE.get() && tieable(mount)) {
            setTie(mount, mount.blockPosition());
        }
    }

    // ---- goals ----

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob) || mob instanceof Enemy) {
            return;
        }
        GoalSelector goals = ((MobGoalsAccessor) mob).commonsense$goalSelector();
        if (goals.getAvailableGoals().isEmpty()) {
            return;   // brain-driven
        }
        int strollPriority = -1;
        for (WrappedGoal wrapped : goals.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof DodgeCreeperGoal) {
                return;
            }
            if (wrapped.getGoal() instanceof RandomStrollGoal && strollPriority < 0) {
                strollPriority = wrapped.getPriority();
            }
        }
        goals.addGoal(0, new DodgeCreeperGoal(mob));
        if (mob instanceof OwnableEntity) {
            goals.addGoal(0, new GiveUpGoal(mob));
        }
        if (mob instanceof Saddleable && strollPriority >= 0) {
            // just ahead of wandering, still behind panic, tempting and breeding
            goals.addGoal(Math.max(0, strollPriority - 1), new GroundTieGoal(mob));
        }
    }

    /** Stay by the spot the rider got off, walking back if pushed away; ends when ridden, leashed or unsaddled. */
    static final class GroundTieGoal extends Goal {
        private final PathfinderMob mob;
        private int repath;

        GroundTieGoal(PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            BlockPos tie = tie(this.mob);
            if (tie == null) {
                return false;
            }
            // a mount carried or pushed far away (water, pistons, another mod's teleport) is freed instead of trekking back
            if (!tieable(this.mob) || this.mob.isLeashed() || this.mob.isVehicle() || this.mob.blockPosition().distSqr(tie) > 48 * 48) {
                setTie(this.mob, null);
                return false;
            }
            return Config.on() && Config.GROUND_TIE.get();
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void tick() {
            BlockPos tie = tie(this.mob);
            if (tie == null || --this.repath > 0) {
                return;
            }
            this.repath = 20;
            if (this.mob.blockPosition().distSqr(tie) > 4) {
                this.mob.getNavigation().moveTo(tie.getX() + 0.5, tie.getY(), tie.getZ() + 0.5, 1.0);
            } else {
                this.mob.getNavigation().stop();
            }
        }
    }

    /**
     * Watches an owned pet's target without taking any control: once a second it asks for a path, and after 5 seconds in a row
     * with no path that gets there it drops the target, so the pet goes back to following its owner.
     */
    static final class GiveUpGoal extends Goal {
        private static final int CHECK = 20;
        private static final int PATIENCE = 5;
        private final PathfinderMob mob;
        @Nullable
        private LivingEntity watching;
        private int misses;
        private int nextCheck;

        GiveUpGoal(PathfinderMob mob) {
            this.mob = mob;   // no flags: runs beside the pet's own goals
        }

        @Override
        public boolean canUse() {
            return this.mob.getTarget() != null && Config.on() && Config.GIVE_UP_UNREACHABLE.get() && ownerOf(this.mob) != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            this.watching = null;
            this.misses = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target != this.watching) {
                this.watching = target;
                this.misses = 0;
                this.nextCheck = 0;
            }
            if (target == null || --this.nextCheck > 0) {
                return;
            }
            this.nextCheck = CHECK;
            if (this.reachable(target)) {
                this.misses = 0;
            } else if (++this.misses >= PATIENCE) {
                this.mob.setTarget(null);
                this.mob.getNavigation().stop();
            }
        }

        /** In reach already, or the pathfinder finds a way to it (ranged attackers in sight count as reaching). */
        private boolean reachable(LivingEntity target) {
            double reach = (this.mob.getBbWidth() + target.getBbWidth()) / 2.0 + 1.0;
            if (this.mob.distanceToSqr(target) <= reach * reach && this.mob.hasLineOfSight(target)) {
                return true;
            }
            Path walking = this.mob.getNavigation().getPath();
            if (walking != null && !walking.isDone() && HostileWits.reaches(walking, target.blockPosition())) {
                return true;   // already on a path that gets there: no need to plan another
            }
            Path path = this.mob.getNavigation().createPath(target, 0);
            if (path != null && path.canReach()) {
                return true;
            }
            return !(this.mob instanceof net.minecraft.world.entity.animal.Animal) && this.mob.hasLineOfSight(target)
                    && this.mob.distanceToSqr(target) < 16 * 16;
        }
    }

    /** Companions (not sitting) get clear of a creeper that has started to hiss. */
    static final class DodgeCreeperGoal extends Goal {
        private static final double SCAN = 6.0;
        private static final double SAFE = 7.0;
        private final PathfinderMob mob;
        @Nullable
        private Creeper creeper;
        private int nextLook;

        DodgeCreeperGoal(PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Nullable
        private Creeper findHissing() {
            List<Creeper> near = this.mob.level().getEntitiesOfClass(Creeper.class, this.mob.getBoundingBox().inflate(SCAN),
                    c -> c.isAlive() && (c.getSwellDir() > 0 || c.isIgnited()));
            Creeper best = null;
            double bestSq = Double.MAX_VALUE;
            for (Creeper c : near) {
                double d = c.distanceToSqr(this.mob);
                if (d < bestSq) {
                    best = c;
                    bestSq = d;
                }
            }
            return best;
        }

        @Override
        public boolean canUse() {
            if (--this.nextLook > 0) {
                return false;
            }
            this.nextLook = 3;   // canUse runs every other tick: a look every 6 ticks, well inside a creeper's 1.5 s fuse
            if (!Config.on() || !Config.DODGE_CREEPERS.get() || !Companions.isCompanion(this.mob)
                    || (this.mob instanceof TamableAnimal pet && pet.isOrderedToSit()) || this.mob.isPassenger()) {
                return false;
            }
            this.creeper = this.findHissing();
            return this.creeper != null;
        }

        @Override
        public boolean canContinueToUse() {
            Creeper c = this.creeper;
            return c != null && c.isAlive() && this.mob.distanceToSqr(c) < SAFE * SAFE && !this.mob.getNavigation().isDone();
        }

        @Override
        public void start() {
            Creeper c = this.creeper;
            if (c == null) {
                return;
            }
            Vec3 dir = new Vec3(this.mob.getX() - c.getX(), 0, this.mob.getZ() - c.getZ());
            dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : dir.normalize();
            Vec3 t = this.mob.position().add(dir.scale(SAFE + 1));
            double speed = 1.6;
            if (!this.mob.getNavigation().moveTo(t.x, t.y, t.z, speed)) {
                Vec3 away = DefaultRandomPos.getPosAway(this.mob, 10, 4, c.position());
                if (away != null) {
                    this.mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
                }
            }
        }

        @Override
        public void stop() {
            this.creeper = null;
        }
    }
}
