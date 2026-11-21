package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;
import dev.chill.commonsense.HerdBehaviour;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brain-based animals: the extra tempt range, and wary animals ignoring food unless the feared player sneaks. */
@Mixin(TemptingSensor.class)
public abstract class TemptingSensorMixin {

    @Shadow
    protected abstract boolean playerHoldingTemptation(Player player);

    @Inject(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;)V", at = @At("TAIL"))
    private void commonsense$rangeAndWariness(ServerLevel level, PathfinderMob entity, CallbackInfo ci) {
        if (!Config.on()) {
            return;
        }
        Brain<?> brain = entity.getBrain();
        Player current = brain.getMemory(MemoryModuleType.TEMPTING_PLAYER).orElse(null);
        int bonus = Config.TEMPT_RANGE_BONUS.get();
        if (current == null && bonus > 0) {
            double range = 10.0 + bonus;
            double bestSq = range * range;
            for (Player p : level.players()) {
                double d = entity.distanceToSqr(p);
                if (d < bestSq && !p.isSpectator() && p.isAlive() && !entity.hasPassenger(p) && this.playerHoldingTemptation(p)) {
                    current = p;
                    bestSq = d;
                }
            }
            if (current != null) {
                brain.setMemory(MemoryModuleType.TEMPTING_PLAYER, current);
            }
        }
        if (current != null && HerdBehaviour.refusesTempt(entity, current)) {
            brain.eraseMemory(MemoryModuleType.TEMPTING_PLAYER);
        }
    }
}
