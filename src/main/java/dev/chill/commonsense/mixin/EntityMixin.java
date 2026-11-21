package dev.chill.commonsense.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.chill.commonsense.Config;
import dev.chill.commonsense.LeashLogic;
import dev.chill.commonsense.Manners;
import dev.chill.commonsense.CommonSense;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single place every leashable entity's lead is ticked, so this covers horses, camels and modded mobs, and it works on top of
 * whichever lead physics is installed (vanilla, or mods like Vanilla Backport that replace it). The lead does its pull first;
 * then the result is corrected so it never drags the mob off a ledge, hoists it up a hill, or flings it downhill.
 * In drag situations (boat, water, ice, riding) the lead stretches instead of snapping.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /**
     * Your own pet or mount bumping into you: only the pet gives way, by the same nudge vanilla would give it. Covers every
     * pushing path (mob pushing you, you pushing it, client and server) since they all meet here.
     */
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void commonsense$noPetShove(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Entity pet = Manners.petBumpingOwner(self, other);
        if (pet == null || self.isPassengerOfSameVehicle(other) || self.noPhysics || other.noPhysics) {
            return;
        }
        ci.cancel();
        Entity owner = pet == self ? other : self;
        double dx = pet.getX() - owner.getX();
        double dz = pet.getZ() - owner.getZ();
        double d = net.minecraft.util.Mth.absMax(dx, dz);
        if (d >= 0.01F && !pet.isVehicle() && pet.isPushable()) {
            d = Math.sqrt(d);
            double scale = Math.min(1.0, 1.0 / d) * 0.05F / d;
            pet.push(dx * scale, 0.0, dz * scale);
        }
    }

    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Leashable;tickLeash(Lnet/minecraft/world/entity/Entity;)V"))
    private void commonsense$tickLeash(Entity entity, Operation<Void> original) {
        if (!(entity instanceof Mob mob) || !Config.on() || !mob.isAlive() || mob.getType().is(CommonSense.EXCLUDED)
                || mob.getLeashData() == null || mob.getLeashData().delayedLeashInfo != null) {
            original.call(entity);
            return;
        }
        Entity holder = mob.getLeashHolder();
        if (holder == null || !holder.isAlive() || holder.level() != mob.level()) {
            original.call(entity);
            return;
        }
        float distance = mob.distanceTo(holder);
        if (Config.DRAG_INSTEAD_OF_SNAP.get() && distance > 10.0F && distance <= Config.DRAG_SNAP_DISTANCE.get() && LeashLogic.dragMode(mob, holder)) {
            if (mob.handleLeashAtDistance(holder, distance)) {
                mob.elasticRangeLeashBehaviour(holder, distance);
                mob.checkSlowFallDistance();
            }
            return;
        }
        var before = mob.getDeltaMovement();
        // Vanilla re-paths a leashed mob toward its holder every tick within 6 blocks; don't let that cut a ledge descent short.
        var descent = LeashLogic.descentInProgress(mob) ? mob.getNavigation().getPath() : null;
        original.call(entity);
        if (descent != null && mob.isLeashed() && mob.getNavigation().getPath() != descent) {
            mob.getNavigation().moveTo(descent, 1.0);
        }
        if (mob.isLeashed()) {
            LeashLogic.correctPull(mob, holder, distance, before);
        }
    }
}
