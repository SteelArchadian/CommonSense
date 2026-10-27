package dev.chill.commonsense;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;

public final class CompanionEvents {

    private static final net.minecraft.resources.ResourceLocation SPEED_ID = CommonSense.id("follow_speed");
    private static final net.minecraft.resources.ResourceLocation STEP_ID = CommonSense.id("step_assist");

    private CompanionEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.on()) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Trail trail = player.getData(CommonSense.TRAIL);
            Vec3 pos = player.position();
            trail.speed = trail.lastPos == null ? 0 : Math.sqrt(Math.pow(pos.x - trail.lastPos.x, 2) + Math.pow(pos.z - trail.lastPos.z, 2));
            trail.lastPos = pos;
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.05, pos.z);
            trail.safe = !Companions.isUnsettled(player) && !player.isPassenger()
                    && player.level().getBlockState(below).isFaceSturdy(player.level(), below, net.minecraft.core.Direction.UP);
            if (trail.safe) {
                trail.add(pos);
            }
        }
    }

    @SubscribeEvent
    public static void onMobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !Companions.isServer(mob) || !Config.on() || !mob.isAlive()) {
            return;
        }
        int phase = mob.tickCount + mob.getId();
        if (phase % 20 == 0) {
            updateHazardAvoidance(mob);
        }
        if (mob instanceof net.minecraft.world.entity.npc.Villager villager && (phase % 5 == 0 || VillageLogic.inLureCooldown(villager))) {
            VillageLogic.tickLure(villager);
        }
        if (phase % 5 == 0) {
            LivingEntity leader = Companions.leader(mob);
            CompanionState st = leader != null || mob.hasData(CommonSense.STATE) ? Companions.state(mob) : null;
            if (st != null) {
                follow(mob, leader, st);
            }
        }
        if (phase % 10 == 0 && Config.PET_RESCUE.get()) {
            rescue(mob);
        }
        if (phase % 40 == 0 && VillageLogic.isVillageMob(mob)) {
            VillageLogic.tick(mob);
        }
    }

    // ------------------------------------------------------------------ hazard-aware pathing

    private static void updateHazardAvoidance(Mob mob) {
        boolean want = Config.HAZARD_AVOIDANCE.get() && !mob.fireImmune() && Companions.isCompanion(mob);
        if (!want && !mob.hasData(CommonSense.STATE)) {
            return;
        }
        CompanionState st = Companions.state(mob);
        if (want && !st.malusApplied) {
            // Wide mobs (iron golems) hang over the block next to their path, so for them "next to lava/fire" is off limits entirely.
            boolean wide = mob.getBbWidth() > 1.0F;
            raise(mob, st, PathType.DANGER_FIRE, wide ? -1.0F : 16.0F);
            raise(mob, st, PathType.DAMAGE_FIRE, -1.0F);
            raise(mob, st, PathType.DANGER_OTHER, wide ? 16.0F : 12.0F);
            raise(mob, st, PathType.DAMAGE_OTHER, -1.0F);
            raise(mob, st, PathType.DANGER_POWDER_SNOW, 8.0F);
            raise(mob, st, PathType.POWDER_SNOW, -1.0F);
            raise(mob, st, PathType.DAMAGE_CAUTIOUS, 8.0F);
            raise(mob, st, PathType.LAVA, -1.0F);
            st.malusApplied = true;
        } else if (!want && st.malusApplied) {
            for (Map.Entry<PathType, Float> e : st.savedMalus.entrySet()) {
                mob.setPathfindingMalus(e.getKey(), e.getValue());
            }
            st.savedMalus.clear();
            st.malusApplied = false;
        }
    }

    /** Only ever makes a block type more avoided; never un-forbids something another mod or vanilla forbade. */
    private static void raise(Mob mob, CompanionState st, PathType type, float value) {
        float original = mob.getPathfindingMalus(type);
        st.savedMalus.put(type, original);
        if (original >= 0.0F) {
            mob.setPathfindingMalus(type, value < 0.0F ? -1.0F : Math.max(original, value));
        }
    }

    // ------------------------------------------------------------------ keeping up

    private static void follow(Mob mob, LivingEntity leader, CompanionState st) {
        if (leader == null) {
            st.boost = 0;
            setModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID, 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            if (st.stepAssist) {
                st.stepAssist = false;
                setModifier(mob, Attributes.STEP_HEIGHT, STEP_ID, 0, AttributeModifier.Operation.ADD_VALUE);
            }
            return;
        }
        float dist = mob.distanceTo(leader);

        Vec3 pos = mob.position();
        st.stuckTicks = dist > 6.0F && pos.distanceToSqr(st.stuckCheckPos) < 0.01 ? st.stuckTicks + 5 : 0;
        st.stuckCheckPos = pos;
        if (mob.onGround()) {
            st.pulledDuringFallSince = -1;
        }

        // Measured, not estimated: how fast this mob actually moved over the last 5 ticks.
        double mobSpeed = st.lastSpeedPos == null ? 0 : Math.sqrt(Math.pow(pos.x - st.lastSpeedPos.x, 2) + Math.pow(pos.z - st.lastSpeedPos.z, 2)) / 5.0;
        st.lastSpeedPos = pos;
        if (Config.SPEED_MATCHING.get() && mob.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            double leaderSpeed = Companions.horizontalSpeed(leader);
            // Match the leader up to a sprint, never past it, so speed potions, horses and elytra aren't matched.
            // Plus a catch-up term, so it closes the gap instead of trailing at the end of the lead.
            double target = Math.min(leaderSpeed + 0.04 * Math.max(0.0, dist - 3.5), Config.SPEED_CAP.get());
            if (nearUnsafeEdge(mob)) {
                // A sped-up mob overshoots corners; never boost next to a drop it couldn't survive.
                st.boost = 0.0;
            } else if (mobSpeed > Config.SPEED_CAP.get() || dist < 2.5F || leaderSpeed < 0.02) {
                st.boost = Math.max(0.0, st.boost - 0.15);
            } else if (dist > 3.5F && mobSpeed < target - 0.01) {
                st.boost = Math.min(Config.MAX_SPEED_BOOST.get() - 1.0, st.boost + 0.15);
            }
            setModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID, st.boost, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        }
        st.lastLeaderDist = dist;

        // Brain-driven mobs on a lead don't use vanilla's lead-following navigation, so keep a walk target on the leader.
        long now = mob.level().getGameTime();
        if (mob.isLeashed() && dist > 3.0F && now - st.lastRepathTick >= 10
                && mob.getBrain().checkMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            LeashLogic.pathToLeader(mob, leader, st, now, false);
        }

        boolean wantStep = Config.STEP_ASSIST.get() && dist > 2.0F && leader.getY() > mob.getY() + 0.5 && !mob.isInWater();
        if (wantStep != st.stepAssist) {
            st.stepAssist = wantStep;
            setModifier(mob, Attributes.STEP_HEIGHT, STEP_ID, wantStep ? 0.5 : 0, AttributeModifier.Operation.ADD_VALUE);
        }
    }

    private static boolean nearUnsafeEdge(Mob mob) {
        if (!mob.onGround()) {
            return false;
        }
        Vec3 dm = mob.getDeltaMovement();
        return LeashLogic.unsafeDropAhead(mob, dm.x, dm.z)
                || LeashLogic.unsafeDropAhead(mob, 1, 0) || LeashLogic.unsafeDropAhead(mob, -1, 0)
                || LeashLogic.unsafeDropAhead(mob, 0, 1) || LeashLogic.unsafeDropAhead(mob, 0, -1);
    }

    private static void setModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    net.minecraft.resources.ResourceLocation id, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = mob.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        AttributeModifier current = inst.getModifier(id);
        if (amount <= 1.0E-3) {
            if (current != null) {
                inst.removeModifier(id);
            }
        } else if (current == null || Math.abs(current.amount() - amount) > 0.02) {
            inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
        }
    }

    // ------------------------------------------------------------------ rescue

    private static void rescue(Mob mob) {
        LivingEntity guardian = Companions.guardian(mob);
        if (guardian == null) {
            return;
        }
        CompanionState st = Companions.state(mob);
        long now = mob.level().getGameTime();

        boolean drowning = mob.isUnderWater() && !mob.canBreatheUnderwater() && mob.getAirSupply() < mob.getMaxAirSupply() / 3;
        boolean burning = mob.isInLava() && !mob.fireImmune();
        boolean falling = !mob.isNoGravity() && !mob.onGround() && !mob.isInWater() && mob.getDeltaMovement().y < -0.4
                && mob.fallDistance > Math.max(6.0F, mob.getMaxFallDistance() + 3.0F);
        boolean inVoid = mob.getY() < mob.level().getMinBuildHeight() - 4;

        if (!drowning && !burning && !falling && !inVoid) {
            if (mob.onGround() && !mob.isInWater() && !mob.isInLava() && mob.fallDistance == 0.0F && (mob.tickCount % 40) < 10) {
                st.lastSafePos = mob.position();
            }
            return;
        }
        if (now - st.lastRescueTick < 40) {
            return;
        }

        Vec3 target = null;
        if (!Companions.isUnsettled(guardian)) {
            target = Companions.findSafeSpotNear(mob, guardian.blockPosition(), 1, 3, true);
        }
        if (target == null && guardian instanceof Player player) {
            target = LeashLogic.latestSafeFootstep(player, mob);
        }
        if (target == null && !Companions.isUnsettled(guardian)) {
            target = Companions.findSafeSpotNear(mob, guardian.blockPosition(), 1, 3, false);   // dying: any safe ground beats waiting
        }
        if (target == null && st.lastSafePos != null && Companions.isSafeStandPos(mob, BlockPos.containing(st.lastSafePos))) {
            target = st.lastSafePos;
        }
        if (target != null) {
            if (burning) {
                mob.clearFire();
            }
            Companions.teleportSafely(mob, target);
            st.lastRescueTick = now;
        }
    }

    // ------------------------------------------------------------------ fall safety net

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !Companions.isServer(mob) || !Config.on() || !mob.hasData(CommonSense.STATE)) {
            return;
        }
        CompanionState st = Companions.state(mob);
        long now = mob.level().getGameTime();
        boolean teleported = now - st.lastTeleportTick < 40;
        long window = 20 + (long) (6.0 * Math.sqrt(event.getDistance()));
        boolean pulled = Config.LEASH_FALL_PROTECTION.get() && (st.pulledDuringFallSince >= 0 || now - st.lastPullTick < window);
        if (teleported || pulled) {
            event.setDamageMultiplier(0.0F);
        }
        st.pulledDuringFallSince = -1;
    }
}
