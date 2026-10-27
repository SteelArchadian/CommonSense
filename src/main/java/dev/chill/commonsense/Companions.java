package dev.chill.commonsense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Shared checks: who counts as a companion, who it follows, and what counts as safe. */
public final class Companions {

    private Companions() {
    }

    public static CompanionState state(Mob mob) {
        return mob.getData(CommonSense.STATE);
    }

    public static boolean isCompanion(Mob mob) {
        if (mob.getType().is(CommonSense.EXCLUDED)) {
            return false;
        }
        return mob.isLeashed()
                || (mob instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null)
                || (mob instanceof AbstractGolem && !(mob instanceof Shulker))
                || mob.getType().is(CommonSense.COMPANIONS);
    }

    /** The entity a companion is trying to keep up with: whoever holds its lead, or its owner if it's a pet that isn't sitting. */
    @Nullable
    public static LivingEntity leader(Mob mob) {
        if (mob.isLeashed()) {
            return mob.getLeashHolder() instanceof LivingEntity holder && holder.level() == mob.level() ? holder : null;
        }
        // Villagers following a lure (emerald block) keep up like anything on a lead.
        if (mob instanceof net.minecraft.world.entity.npc.Villager villager && VillageLogic.memory(villager).lured) {
            return VillageLogic.lureHolder(villager);
        }
        if (mob instanceof TamableAnimal pet && pet.isTame() && !pet.isOrderedToSit() && !pet.isPassenger()
                && pet.getOwner() instanceof LivingEntity owner && owner.level() == mob.level()) {
            return owner;
        }
        return null;
    }

    /** Who a mob in danger should be brought back to. */
    @Nullable
    public static LivingEntity guardian(Mob mob) {
        if (mob.isLeashed()) {
            return mob.getLeashHolder() instanceof LivingEntity holder && holder.level() == mob.level() ? holder : null;
        }
        if (mob instanceof OwnableEntity ownable && ownable.getOwner() instanceof LivingEntity owner && owner.level() == mob.level()) {
            return owner;
        }
        return null;
    }

    /** Somewhere the owner can't reasonably be teleported next to: swimming, in a water column, falling, gliding or flying. */
    public static boolean isUnsettled(LivingEntity entity) {
        if (entity.isInWater() || entity.isInLava() || entity.isFallFlying() || entity.isInPowderSnow) {
            return true;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return true;
        }
        return !entity.isPassenger() && entity.fallDistance > 1.5F;
    }

    public static boolean isOnIce(Entity entity) {
        BlockPos below = entity.getBlockPosBelowThatAffectsMyMovement();
        return entity.level().getBlockState(below).getFriction(entity.level(), below, entity) > 0.8F;
    }

    public static double horizontalSpeed(Entity entity) {
        if (entity instanceof Player player) {
            return player.getData(CommonSense.TRAIL).speed;
        }
        Vec3 dm = entity.getDeltaMovement();
        return Math.sqrt(dm.x * dm.x + dm.z * dm.z);
    }

    /** Solid, hazard-free ground with room for the mob. WALKABLE already excludes blocks next to lava, fire, cactus and the like. */
    public static boolean isSafeStandPos(Mob mob, BlockPos feet) {
        Level level = mob.level();
        if (!level.isLoaded(feet) || WalkNodeEvaluator.getPathTypeStatic(mob, feet) != PathType.WALKABLE) {
            return false;
        }
        if (!level.getFluidState(feet).isEmpty() || level.getBlockState(feet.below()).getBlock() instanceof LeavesBlock) {
            return false;
        }
        return level.noCollision(mob, mob.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(mob.position())));
    }

    /**
     * Where a pet can be put down without risk: safe ground, no lava or fire within the hazard radius, and not on a ledge
     * it could be bumped off (a one-block bridge, a cliff edge). Refusing just means the pet keeps following and tries again.
     */
    public static boolean isSafeLandingPos(Mob mob, BlockPos feet) {
        if (!isSafeStandPos(mob, feet)) {
            return false;
        }
        int radius = Config.TELEPORT_HAZARD_RADIUS.get();
        if (radius > 0 && hazardNear(mob.level(), feet, radius)) {
            return false;
        }
        return !Config.TELEPORT_AVOID_LEDGES.get() || !onLedge(mob, feet);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK) || CampfireBlock.isLitCampfire(state);
    }

    private static boolean hazardNear(Level level, BlockPos feet, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    p.setWithOffset(feet, dx, dy, dz);
                    if (!level.isLoaded(p) || isHazard(level.getBlockState(p))) {
                        return true;   // unknown ground counts as unsafe
                    }
                }
            }
        }
        return false;
    }

    /** True if stepping off any side would mean a drop the mob can't survive (or one into lava). Walls are fine. */
    private static boolean onLedge(Mob mob, BlockPos feet) {
        Level level = mob.level();
        int maxDrop = mob.getMaxFallDistance() + 1;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                continue;
            }
            boolean landing = false;
            for (int d = 1; d <= maxDrop; d++) {
                BlockPos below = side.below(d);
                BlockState state = level.getBlockState(below);
                if (isHazard(state)) {
                    return true;
                }
                if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, below).isEmpty()) {
                    landing = true;
                    break;
                }
            }
            if (!landing) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Vec3 findSafeSpotNear(Mob mob, BlockPos center, int minRadius, int maxRadius) {
        return findSafeSpotNear(mob, center, minRadius, maxRadius, false);
    }

    /** strict = {@link #isSafeLandingPos}; otherwise just safe ground (for emergencies, when anything beats drowning). */
    @Nullable
    public static Vec3 findSafeSpotNear(Mob mob, BlockPos center, int minRadius, int maxRadius, boolean strict) {
        for (int i = 0; i < 24; i++) {
            int dx = mob.getRandom().nextIntBetweenInclusive(-maxRadius, maxRadius);
            int dz = mob.getRandom().nextIntBetweenInclusive(-maxRadius, maxRadius);
            if (Math.abs(dx) < minRadius && Math.abs(dz) < minRadius) {
                continue;
            }
            for (int dy = 1; dy >= -2; dy--) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (strict ? isSafeLandingPos(mob, pos) : isSafeStandPos(mob, pos)) {
                    return Vec3.atBottomCenterOf(pos);
                }
            }
        }
        return null;
    }

    /** Teleport that can't hurt: no leftover fall distance, no momentum. */
    public static void teleportSafely(Mob mob, Vec3 pos) {
        mob.teleportTo(pos.x, pos.y, pos.z);
        mob.resetFallDistance();
        mob.setDeltaMovement(Vec3.ZERO);
        mob.getNavigation().stop();
        state(mob).lastTeleportTick = mob.level().getGameTime();
    }

    public static boolean isServer(Entity entity) {
        return entity.level() instanceof ServerLevel;
    }
}
