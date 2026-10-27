package dev.chill.commonsense.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the herd instinct give a mob a "recently hurt by" record without dealing any damage. */
@Mixin(LivingEntity.class)
public interface LivingEntityDamageAccessor {

    @Accessor("lastDamageSource")
    void commonsense$setLastDamageSource(DamageSource source);

    @Accessor("lastDamageStamp")
    void commonsense$setLastDamageStamp(long stamp);
}
