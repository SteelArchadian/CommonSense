package dev.chill.commonsense;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Iterator;

public final class LeashLogic {

    private LeashLogic() {
    }

    /** Boats, swimming, riding, ice, or simply moving faster than a mob can: stretch and drag instead of snapping. */
    public static boolean dragMode(Mob mob, Entity holder) {
        return holder.isPassenger() || holder.isInWater() || mob.isInWater()
                || Companions.isOnIce(holder) || Companions.isOnIce(mob)
                || Companions.horizontalSpeed(holder) > Config.SPEED_CAP.get();
    }

    /** Vanilla's pull, exactly, for when the mod is off. */
    public static void vanillaElastic(Mob mob, Entity holder, float distance) {
        double dx = (holder.getX() - mob.getX()) / distance;
        double dy = (holder.getY() - mob.getY()) / distance;
        double dz = (holder.getZ() - mob.getZ()) / distance;
        mob.setDeltaMovement(mob.getDeltaMovement().add(Math.copySign(dx * dx * 0.4, dx), Math.copySign(dy * dy * 0.4, dy), Math.copySign(dz * dz * 0.4, dz)));
    }

    /** Replaces vanilla's 6+ block "yank": pull sideways like vanilla, but never off a ledge, never hoist up hills, and walk/path instead. */
    public static void elastic(Mob mob, Entity holder, float distance) {
        CompanionState st = Companions.state(mob);
        long now = mob.level().getGameTime();
        boolean dragging = distance > 10.0F;

        double dx = (holder.getX() - mob.getX()) / distance;
        double dy = (holder.getY() - mob.getY()) / distance;
        double dz = (holder.getZ() - mob.getZ()) / distance;
        double k = dragging ? 0.4 * Math.min(3.0, 1.0 + (distance - 10.0) / 4.0) : 0.4;
        double px = Math.copySign(dx * dx * k, dx);
        double py = Math.copySign(dy * dy * k, dy);
        double pz = Math.copySign(dz * dz * k, dz);

        boolean inFluid = mob.isInWater() || mob.isInLava();
        if (Config.CAREFUL_DESCENT.get() && !dragging && mob.onGround() && !inFluid && unsafeDropAhead(mob, px, pz)) {
            px = 0;
            pz = 0;
            pathToLeader(mob, holder, st, now, true);
        }
        if (!inFluid && !dragging) {
            // Uphill: let it walk up (step assist + pathing). Only lift it if it is genuinely stuck against a wall.
            if (py > 0 && !(mob.horizontalCollision && st.stuckTicks >= 20)) {
                py = 0;
            }
            // Downhill: gravity is enough. Vanilla's downward yank is what flings mobs off mountains.
            if (py < 0) {
                py = 0;
            }
        }
        mob.setDeltaMovement(mob.getDeltaMovement().add(px, py, pz));
        mob.hasImpulse = true;
        st.lastPullTick = now;
        if (!mob.onGround() && st.pulledDuringFallSince < 0) {
            st.pulledDuringFallSince = now;
        }
        if (!dragging && now - st.lastRepathTick >= 10) {
            pathToLeader(mob, holder, st, now, false);
        }
    }

    /**
     * Runs after whatever lead physics is installed has pulled the mob. Keeps its sideways pull, but never toward a ledge with
     * an unsafe drop (it paths the safe way instead), never hoists the mob up a hill, and never flings it downward.
     */
    public static void correctPull(Mob mob, Entity holder, float distance, Vec3 before) {
        Vec3 after = mob.getDeltaMovement();
        Vec3 added = after.subtract(before);
        if (added.lengthSqr() < 1.0E-6) {
            return;
        }
        CompanionState st = Companions.state(mob);
        long now = mob.level().getGameTime();
        boolean inFluid = mob.isInWater() || mob.isInLava();
        double ax = added.x, ay = added.y, az = added.z;
        if (Config.CAREFUL_DESCENT.get() && mob.onGround() && !inFluid && unsafeDropAhead(mob, after.x, after.z)) {
            ax = 0;
            az = 0;
            // Keep a path that already takes it down safely; only look for another way if it has none.
            if (now - st.lastRepathTick >= 10 && !walkingSafePath(mob, before.x, before.z)) {
                pathToLeader(mob, holder, st, now, true);
            }
        }
        if (!inFluid) {
            if (ay > 0 && !(mob.horizontalCollision && st.stuckTicks >= 20)) {
                ay = 0;
            }
            if (ay < 0) {
                ay = 0;
            }
        }
        mob.setDeltaMovement(before.add(ax, ay, az));
        st.lastPullTick = now;
        if (!mob.onGround() && st.pulledDuringFallSince < 0) {
            st.pulledDuringFallSince = now;
        }
        // Some lead physics (e.g. Vanilla Backport's) start pulling from ~5 blocks; walk toward the holder instead of being dragged.
        if (distance > 4.0F && now - st.lastRepathTick >= 10) {
            pathToLeader(mob, holder, st, now, false);
        }
    }

    /** Final motion check for leashed mobs (see LivingEntityMixin). */
    public static void sanitizeMotion(Mob mob) {
        if (mob.getType().is(CommonSense.EXCLUDED) || mob.isInWater() || mob.isInLava() || mob.isPassenger()) {
            return;
        }
        Entity holder = mob.getLeashHolder();
        if (holder == null || dragMode(mob, holder)) {
            return;
        }
        Vec3 v = mob.getDeltaMovement();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        if (mob.onGround()) {
            // Its own path may step down a safe ledge right beside a deeper drop the probe sees; let it walk that path.
            if (Config.CAREFUL_DESCENT.get() && h > 0.05 && unsafeDropAhead(mob, v.x, v.z) && !walkingSafePath(mob, v.x, v.z)) {
                mob.setDeltaMovement(0, v.y, 0);
                CompanionState st = Companions.state(mob);
                long now = mob.level().getGameTime();
                if (now - st.lastRepathTick >= 10) {
                    pathToLeader(mob, holder, st, now, true);
                }
            }
        } else if (h > 0.35) {
            // Airborne: no slingshot. Keep the direction, cap the speed at a brisk walk.
            mob.setDeltaMovement(v.x / h * 0.35, v.y, v.z / h * 0.35);
        }
    }

    /**
     * Is this motion the mob walking its own path toward the next node, close by, that the pathfinder already judged safe
     * (e.g. a stair one to three blocks down)? The pathfinder never plans drops longer than the mob's max fall distance.
     */
    static boolean walkingSafePath(Mob mob, double vx, double vz) {
        Path path = mob.getNavigation().getPath();
        // Only a complete path: a partial one (no way to the target) can lead anywhere, even away from the holder.
        if (path == null || path.isDone() || !path.canReach()) {
            return false;
        }
        BlockPos next = path.getNextNodePos();
        double nx = next.getX() + 0.5 - mob.getX();
        double nz = next.getZ() + 0.5 - mob.getZ();
        double nLen = Math.sqrt(nx * nx + nz * nz);
        double vLen = Math.sqrt(vx * vx + vz * vz);
        if (nLen > 2.5 || next.getY() > mob.getBlockY() + 1 || next.getY() < mob.getBlockY() - Math.max(1, mob.getMaxFallDistance())) {
            return false;
        }
        // Right next to a node over a ledge, its heading jitters while it edges off; further out, it must not be heading away.
        if (nLen > 1.0 && vLen > 1.0E-4 && (nx * vx + nz * vz) / (nLen * vLen) < 0.0) {
            return false;
        }
        return Companions.isSafeStandPos(mob, next);
    }

    /**
     * Partway through stepping down a ledge on its own path (the next node is a safe spot below it). A new path would start
     * from the block it is leaning off and walk it back first, so replanning every few ticks keeps it on the edge forever.
     * Navigation's own stuck detection still ends the path if it really can't get down.
     */
    public static boolean descentInProgress(Mob mob) {
        if (!Config.CAREFUL_DESCENT.get() || !mob.onGround() || mob.getNavigation().isStuck()) {
            return false;
        }
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone() || path.getNextNodePos().getY() >= mob.getBlockY() || !walkingSafePath(mob, 0, 0)) {
            return false;
        }
        CompanionState st = Companions.state(mob);
        long now = mob.level().getGameTime();
        if (st.descentPath != path) {
            st.descentPath = path;
            st.descentSince = now;
        }
        return now - st.descentSince < 60;
    }

    /** Would walking in the pull direction put the mob over a drop it can't safely take? */
    public static boolean unsafeDropAhead(Mob mob, double px, double pz) {
        double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0E-4) {
            return false;
        }
        double reach = mob.getBbWidth() / 2.0 + 0.7;
        Level level = mob.level();
        BlockPos probe = BlockPos.containing(mob.getX() + px / len * reach, mob.getY() + 0.1, mob.getZ() + pz / len * reach);
        int maxDrop = Math.max(1, mob.getMaxFallDistance());
        for (int drop = 0; drop <= maxDrop + 1; drop++) {
            BlockPos pos = probe.below(drop);
            if (!level.getFluidState(pos).isEmpty()) {
                return level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
            }
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return drop > maxDrop;
            }
        }
        return true;
    }

    /** Path to the leader; if there's no full path (cliff, odd terrain), walk the leader's own recent footsteps instead. */
    public static void pathToLeader(Mob mob, Entity leader, CompanionState st, long now, boolean preferFootsteps) {
        st.lastRepathTick = now;
        if (descentInProgress(mob)) {
            return;
        }
        // Brain-driven mobs (armadillos, goats, camels...) ignore raw navigation orders; give their brain a walk target instead.
        boolean brain = mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED);
        Path path = mob.getNavigation().createPath(leader, 1);
        if (path != null && path.canReach() && !preferFootsteps) {
            if (brain) {
                mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(leader, false), 1.0F, 2));
            } else {
                mob.getNavigation().moveTo(path, 1.0);
            }
            return;
        }
        Vec3 step = leader instanceof Player player ? nextFootstep(player.getData(CommonSense.TRAIL), mob) : null;
        if (step != null) {
            if (brain) {
                mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(step, 1.0F, 1));
            } else {
                mob.getNavigation().moveTo(step.x, step.y, step.z, 1.0);
            }
        } else if (path != null && !brain) {
            mob.getNavigation().moveTo(path, 1.0);
        }
    }

    /** The footstep a few steps on from the one nearest the mob, so it retraces exactly where the player walked. */
    @Nullable
    public static Vec3 nextFootstep(Trail trail, Mob mob) {
        if (trail.crumbs.isEmpty()) {
            return null;
        }
        int index = 0;
        int nearest = -1;
        double best = 36.0;
        for (Vec3 crumb : trail.crumbs) {
            double d = crumb.distanceToSqr(mob.position());
            if (d < best) {
                best = d;
                nearest = index;
            }
            index++;
        }
        if (nearest < 0) {
            return null;
        }
        int target = Math.min(trail.crumbs.size() - 1, nearest + 3);
        Iterator<Vec3> it = trail.crumbs.iterator();
        Vec3 result = null;
        for (int i = 0; i <= target && it.hasNext(); i++) {
            result = it.next();
        }
        return result;
    }

    @Nullable
    public static Vec3 latestSafeFootstep(Player player, Mob mob) {
        Iterator<Vec3> it = player.getData(CommonSense.TRAIL).crumbs.descendingIterator();
        for (int i = 0; it.hasNext() && i < 32; i++) {
            Vec3 crumb = it.next();
            if (Companions.isSafeStandPos(mob, BlockPos.containing(crumb))) {
                return crumb;
            }
        }
        return null;
    }

}
