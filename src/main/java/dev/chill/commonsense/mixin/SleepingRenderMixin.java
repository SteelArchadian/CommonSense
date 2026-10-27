package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla draws a mob out to (hitbox size x 64) blocks. Sleeping shrinks the hitbox to 0.2 x 0.2, so sleeping villagers vanish
 * past ~12.8 blocks even in plain view. Use the mob's standing size for this check instead.
 */
@Mixin(Entity.class)
public abstract class SleepingRenderMixin {

    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void commonsense$sleepingRenderDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LivingEntity living && living.getPose() == Pose.SLEEPING && Config.on() && Config.SLEEPING_RENDER_FIX.get()) {
            EntityDimensions standing = living.getDimensions(Pose.STANDING);
            double size = (standing.width() * 2.0 + standing.height()) / 3.0;   // same average vanilla's AABB.getSize() takes
            double max = size * 64.0 * Entity.getViewScale();
            cir.setReturnValue(distance < max * max);
        }
    }
}
