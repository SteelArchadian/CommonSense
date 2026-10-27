package dev.chill.commonsense;

import dev.chill.commonsense.mixin.LivingEntityDamageAccessor;
import dev.chill.commonsense.mixin.MobGoalsAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.List;

/**
 * Herd instinct: when an animal is hurt, others of its kind nearby take fright and run. Monsters are never involved. Of the
 * animals that can fight back (llamas, polar bears...), only the single nearest one within fight-back range turns on the
 * attacker, so a caravan or pack doesn't all charge at once. Alerts never cause more alerts (no damage is dealt).
 * Inspired by Darkhax's Herd Mentality; this is an independent implementation.
 * <p>
 * The alert is a "recently hurt by this attacker" record with no damage and no knockback, so every mob reacts through its
 * own vanilla behaviour: goal-based animals panic (PanicGoal reads the last damage source) and brain-based ones panic through
 * their hurt memory. Pets and mobs in {@code commonsense:excluded} are never alerted.
 */
public final class HerdInstinct {

    /** Caps the work for one hit in a crowded farm. */
    private static final int MAX_ALERTED = 24;

    private HerdInstinct() {
    }

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (!Config.on() || !Config.HERD_INSTINCT.get()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == victim || attacker.getType() == victim.getType()
                || attacker.isSpectator() || !canBeAlerted(victim)) {
            return;
        }
        double range = Config.HERD_RANGE.get();
        List<Mob> herd = level.getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(range), m -> m != victim
                && m.getType() == victim.getType() && m.isAlive() && !m.isNoAi() && canBeAlerted(m) && m.distanceToSqr(victim) <= range * range);
        herd.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(victim)));
        double fightRange = Config.HERD_FIGHT_RANGE.get();
        Mob defender = null;
        if (Config.HERD_FIGHT_BACK.get()) {
            for (Mob m : herd) {
                if (m.distanceToSqr(victim) > fightRange * fightRange) {
                    break;
                }
                if (canFightBack(m) && m.canAttack(attacker)) {
                    defender = m;   // the nearest fighter only, so a caravan or pack doesn't all charge
                    break;
                }
            }
        }
        int alerted = 0;
        for (Mob member : herd) {
            if (alerted++ >= MAX_ALERTED) {
                break;
            }
            if (member == defender) {
                fightBack(member, attacker);
            } else {
                frighten(member, source, level);
            }
        }
    }

    /** Animals only: never monsters (hoglins count as monsters), never pets. */
    private static boolean canBeAlerted(Mob mob) {
        return (mob instanceof Animal || mob instanceof WaterAnimal) && !(mob instanceof Enemy)
                && !mob.getType().is(CommonSense.EXCLUDED) && !(mob instanceof OwnableEntity pet && pet.getOwnerUUID() != null);
    }

    /** Has a revenge goal (llamas, polar bears, wolves, bees, pandas...) or an anger memory (brain-driven mobs). */
    private static boolean canFightBack(Mob mob) {
        if (mob instanceof NeutralMob || mob.getBrain().checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED)) {
            return true;
        }
        for (var goal : ((MobGoalsAccessor) mob).commonsense$targetSelector().getAvailableGoals()) {
            if (goal.getGoal() instanceof HurtByTargetGoal) {
                return true;
            }
        }
        return false;
    }

    private static void fightBack(Mob member, LivingEntity attacker) {
        member.setLastHurtByMob(attacker);   // the revenge goal takes it from here
        {
            if (member instanceof NeutralMob neutral) {
                neutral.setPersistentAngerTarget(attacker.getUUID());
                neutral.startPersistentAngerTimer();
            }
            member.setTarget(attacker);
            Brain<?> brain = member.getBrain();   // brain-driven fighters (piglins, hoglins...) ignore setTarget
            if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                brain.setMemory(MemoryModuleType.ATTACK_TARGET, attacker);
            }
            if (brain.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED)) {
                brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, attacker.getUUID(), 600L);
            }
        }
    }

    /** Feel hurt without taking damage, so the mob's own panic behaviour kicks in. No attacker is recorded, so nobody retaliates. */
    private static void frighten(Mob member, DamageSource source, ServerLevel level) {
        LivingEntityDamageAccessor hurt = (LivingEntityDamageAccessor) member;
        hurt.commonsense$setLastDamageSource(source);
        hurt.commonsense$setLastDamageStamp(level.getGameTime());
        Brain<?> brain = member.getBrain();
        if (brain.checkMemory(MemoryModuleType.HURT_BY, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.HURT_BY, source);   // react this tick rather than at the next sensor scan
        }
    }
}
