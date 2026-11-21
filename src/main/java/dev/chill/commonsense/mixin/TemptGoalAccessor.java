package dev.chill.commonsense.mixin;

import net.minecraft.world.entity.ai.goal.TemptGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The animal's own tempt speed, reused when it follows a tempted herd mate. */
@Mixin(TemptGoal.class)
public interface TemptGoalAccessor {

    @Accessor("speedModifier")
    double commonsense$speed();
}
