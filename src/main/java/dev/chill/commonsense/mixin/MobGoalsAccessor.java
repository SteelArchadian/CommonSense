package dev.chill.commonsense.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the herd instinct tell animals that fight back (they have a revenge goal) from ones that only run. */
@Mixin(Mob.class)
public interface MobGoalsAccessor {

    @Accessor("targetSelector")
    GoalSelector commonsense$targetSelector();
}
