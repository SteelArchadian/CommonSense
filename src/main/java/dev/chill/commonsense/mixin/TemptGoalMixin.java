package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reliable tempt, goal based animals (cows, sheep, pigs, chickens, horses, cats, ...): follow at once when the food comes out,
 * stop at once when it's put away. Folded in from the old Reliable Tempt mod.
 */
@Mixin(value = TemptGoal.class, priority = 1001)
public abstract class TemptGoalMixin {

    @Shadow @Final protected PathfinderMob mob;
    @Shadow protected Player player;
    @Shadow private int calmDown;

    @Shadow protected abstract boolean canScare();
    @Shadow private boolean shouldFollow(LivingEntity entity) { throw new AssertionError(); }

    @Unique private boolean commonsense$scaredOff;

    @Unique
    private boolean commonsense$isTempting(Player player) {
        return player != null && player.isAlive() && !player.isSpectator() && this.shouldFollow(player);
    }

    // Drop the goal as soon as the item leaves the player's hands.
    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
    private void commonsense$canContinueToUse(CallbackInfoReturnable<Boolean> cir) {
        if (Config.on() && Config.RELIABLE_TEMPT.get() && !this.commonsense$isTempting(this.player)) {
            cir.setReturnValue(false);
        }
    }

    // Goals are only re-evaluated every other tick; stop walking immediately in between.
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void commonsense$tick(CallbackInfo ci) {
        if (Config.on() && Config.RELIABLE_TEMPT.get() && !this.commonsense$isTempting(this.player)) {
            this.mob.getNavigation().stop();
            ci.cancel();
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void commonsense$beforeStop(CallbackInfo ci) {
        // Cats and ocelots get "scared off" by sudden movement while the item is still held; keep vanilla's cooldown for that case.
        this.commonsense$scaredOff = this.canScare() && this.commonsense$isTempting(this.player);
    }

    // Remove the 5 second delay before an animal can be tempted again.
    @Inject(method = "stop", at = @At("TAIL"))
    private void commonsense$afterStop(CallbackInfo ci) {
        if (Config.on() && Config.RELIABLE_TEMPT.get() && !this.commonsense$scaredOff) {
            this.calmDown = 0;
        }
        this.commonsense$scaredOff = false;
    }
}
