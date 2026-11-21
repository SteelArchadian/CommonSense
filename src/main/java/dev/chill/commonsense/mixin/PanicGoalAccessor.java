package dev.chill.commonsense.mixin;

import net.minecraft.world.entity.ai.goal.PanicGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The animal's own panic speed, reused when a scared herd bolts together. */
@Mixin(PanicGoal.class)
public interface PanicGoalAccessor {

    @Accessor("speedModifier")
    double commonsense$speed();
}
