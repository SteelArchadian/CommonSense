package dev.chill.commonsense.mixin;

import dev.chill.commonsense.Config;
import dev.chill.commonsense.LeashLogic;
import dev.chill.commonsense.CommonSense;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Mob its own lead pull so drag mode (past 10 blocks) can pull harder; normal range stays vanilla. */
@Mixin(Mob.class)
public abstract class MobMixin implements Leashable {

    @Override
    public void elasticRangeLeashBehaviour(Entity leashHolder, float distance) {
        Mob self = (Mob) (Object) this;
        // Within 10 blocks: vanilla's pull (made safe afterwards by EntityMixin). Past 10, only reached in drag mode: a stronger drag.
        if (distance > 10.0F && Config.on() && !self.getType().is(CommonSense.EXCLUDED) && !self.level().isClientSide()) {
            LeashLogic.elastic(self, leashHolder, distance);
        } else {
            LeashLogic.vanillaElastic(self, leashHolder, distance);
        }
    }
}
