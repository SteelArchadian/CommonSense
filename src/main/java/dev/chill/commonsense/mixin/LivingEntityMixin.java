package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;
import dev.chill.commonsense.LeashLogic;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Last check before a leashed mob actually moves. Lead physics mods may apply their pull anywhere in the tick, so the
 * safety rules (no stepping toward an unsafe drop, no slingshot while airborne) are enforced here, on the final motion.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "travel", at = @At("HEAD"))
    private void commonsense$safeLeashMotion(Vec3 travelVector, CallbackInfo ci) {
        if ((Object) this instanceof Mob mob && mob.isLeashed() && !mob.level().isClientSide() && Config.on()) {
            LeashLogic.sanitizeMotion(mob);
        }
    }
}
