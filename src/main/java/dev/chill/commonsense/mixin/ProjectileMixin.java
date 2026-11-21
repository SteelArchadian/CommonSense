package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Manners;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Your arrows, tridents, snowballs and fireballs (and your pets' own shots) fly on through your pets and mounts instead of
 * stopping on them, so a wolf running in front of you doesn't soak up the shot meant for the skeleton. Every projectile
 * subclass checks this first, modded ones included. Fishing hooks are left alone: hooking your own pet is on purpose.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void commonsense$passThroughPets(Entity target, CallbackInfoReturnable<Boolean> cir) {
        Projectile self = (Projectile) (Object) this;
        if (!(self instanceof FishingHook) && Manners.ownShotAtOwnPet(self.getOwner(), target)) {
            cir.setReturnValue(false);
        }
    }
}
