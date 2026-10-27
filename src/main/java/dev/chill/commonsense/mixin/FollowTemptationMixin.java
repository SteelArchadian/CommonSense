package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.FollowTemptation;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reliable tempt, brain based animals (goats, camels, sniffers, armadillos, axolotls, frogs). Folded in from the old Reliable Tempt mod.
 */
@Mixin(FollowTemptation.class)
public class FollowTemptationMixin {

    // Remove the 5 second delay before an animal can be tempted again.
    @Inject(method = "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;J)V", at = @At("TAIL"))
    private void commonsense$stop(ServerLevel level, PathfinderMob entity, long gameTime, CallbackInfo ci) {
        if (!(Config.on() && Config.RELIABLE_TEMPT.get())) {
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS);
        entity.getNavigation().stop();
    }
}
