package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;

import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tempting sensor normally only scans once per second, so brain based animals keep following for up to a second
 * after the item is put away, and react late when it is taken out. Scan every tick instead.
 */
@Mixin(Sensor.class)
public class SensorMixin {

    @Shadow private long timeToTick;

    @Inject(method = "tick", at = @At("HEAD"))
    private void commonsense$scanEveryTick(CallbackInfo ci) {
        if ((Object) this instanceof TemptingSensor && Config.on() && Config.RELIABLE_TEMPT.get()) {
            this.timeToTick = 0L;
        }
    }
}
