package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Companions;
import dev.chill.commonsense.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {

    /** Don't teleport next to an owner who is swimming, rising in a water column, falling, gliding or flying. Come once they've landed. */
    @Inject(method = "tryToTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void commonsense$waitForOwner(CallbackInfo ci) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        if (Config.on() && Config.WAIT_WHILE_OWNER_UNSAFE.get() && self.getOwner() instanceof LivingEntity owner && Companions.isUnsettled(owner)) {
            ci.cancel();
        }
    }

    @Inject(method = "canTeleportTo", at = @At("RETURN"), cancellable = true)
    private void commonsense$safeLanding(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Config.on() && Config.SAFE_PET_TELEPORT.get() && !Companions.isSafeLandingPos((TamableAnimal) (Object) this, pos)) {
            cir.setReturnValue(false);
        }
    }

    /** Vanilla moveTo keeps the pet's fall distance, so a pet teleported mid-fall takes all that damage on arrival. */
    @Inject(method = "maybeTeleportTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/TamableAnimal;moveTo(DDDFF)V", shift = At.Shift.AFTER))
    private void commonsense$arriveSafely(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        TamableAnimal self = (TamableAnimal) (Object) this;
        if (Config.on() && Config.SAFE_PET_TELEPORT.get()) {
            self.resetFallDistance();
            self.setDeltaMovement(Vec3.ZERO);
            Companions.state(self).lastTeleportTick = self.level().getGameTime();
        }
    }
}
