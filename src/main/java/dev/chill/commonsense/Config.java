package dev.chill.commonsense;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED;

    public static final ModConfigSpec.BooleanValue HAZARD_AVOIDANCE;

    public static final ModConfigSpec.BooleanValue SPEED_MATCHING;
    public static final ModConfigSpec.DoubleValue MAX_SPEED_BOOST;
    public static final ModConfigSpec.DoubleValue SPEED_CAP;
    public static final ModConfigSpec.BooleanValue STEP_ASSIST;
    public static final ModConfigSpec.BooleanValue DRAG_INSTEAD_OF_SNAP;
    public static final ModConfigSpec.DoubleValue DRAG_SNAP_DISTANCE;
    public static final ModConfigSpec.BooleanValue CAREFUL_DESCENT;
    public static final ModConfigSpec.BooleanValue LEASH_FALL_PROTECTION;

    public static final ModConfigSpec.BooleanValue SAFE_PET_TELEPORT;
    public static final ModConfigSpec.IntValue TELEPORT_HAZARD_RADIUS;
    public static final ModConfigSpec.BooleanValue TELEPORT_AVOID_LEDGES;
    public static final ModConfigSpec.BooleanValue WAIT_WHILE_OWNER_UNSAFE;
    public static final ModConfigSpec.BooleanValue PET_RESCUE;

    public static final ModConfigSpec.BooleanValue VILLAGE_HOMING;
    public static final ModConfigSpec.BooleanValue VILLAGER_TELEPORT_HOME;
    public static final ModConfigSpec.IntValue LOST_SECONDS;
    public static final ModConfigSpec.DoubleValue ESCORT_RADIUS;
    public static final ModConfigSpec.DoubleValue LURE_RADIUS;
    public static final ModConfigSpec.BooleanValue VILLAGER_LURE_FOLLOW;
    public static final ModConfigSpec.DoubleValue LURE_FOLLOW_RANGE;

    public static final ModConfigSpec.BooleanValue HERD_INSTINCT;
    public static final ModConfigSpec.DoubleValue HERD_RANGE;
    public static final ModConfigSpec.DoubleValue HERD_FIGHT_RANGE;
    public static final ModConfigSpec.BooleanValue HERD_FIGHT_BACK;

    public static final ModConfigSpec.BooleanValue RELIABLE_TEMPT;

    public static final ModConfigSpec.BooleanValue SLEEPING_RENDER_FIX;

    static {
        ENABLED = B.comment("Master switch.").define("enabled", true);

        B.comment("Companions = tamed/owned mobs, golems, anything on a lead, and the commonsense:companions tag.").push("pathing");
        HAZARD_AVOIDANCE = B.comment("Companions keep a safe distance from lava, fire, cactus, berry bushes and powder snow when pathing.",
                "Wide mobs (iron golems...) are not allowed to walk where their body would hang over lava or fire.").define("hazard_avoidance", true);
        B.pop();

        B.push("following");
        SPEED_MATCHING = B.comment("Leashed mobs and following pets speed up when they fall behind you.").define("speed_matching", true);
        MAX_SPEED_BOOST = B.comment("Largest speed multiplier a companion can get from speed matching.").defineInRange("max_speed_boost", 2.5, 1.0, 4.0);
        SPEED_CAP = B.comment("Companions never get faster than this (blocks per tick) from matching. 0.28 is a sprinting player,",
                "so speed potions, horses, elytra and other speed tricks are not matched.").defineInRange("speed_cap_blocks_per_tick", 0.28, 0.1, 1.0);
        STEP_ASSIST = B.comment("While you're above them, companions can walk up full blocks instead of being yanked up the hill.").define("step_assist", true);
        B.pop();

        B.push("leads");
        DRAG_INSTEAD_OF_SNAP = B.comment("If you're in a boat, swimming, riding, or on ice, leads stretch and drag the mob along instead of snapping at 10 blocks.").define("drag_instead_of_snap", true);
        DRAG_SNAP_DISTANCE = B.comment("Distance a lead can stretch to in drag mode before it finally snaps.").defineInRange("drag_snap_distance", 24.0, 10.0, 64.0);
        CAREFUL_DESCENT = B.comment("Leads never pull a mob off a ledge with an unsafe drop; it walks down the safe way, following your footsteps if needed.").define("careful_descent", true);
        LEASH_FALL_PROTECTION = B.comment("Safety net: no fall damage from falls caused by a lead pull or by being dragged.").define("leash_fall_protection", true);
        B.pop();

        B.push("pets");
        SAFE_PET_TELEPORT = B.comment("Pets only teleport onto solid, hazard-free ground, and arrive with their fall distance reset",
                "(vanilla keeps the fall distance, so a pet teleported mid-fall takes the damage on arrival).").define("safe_teleport", true);
        TELEPORT_HAZARD_RADIUS = B.comment("Pets won't teleport anywhere with lava, fire, magma or a lit campfire this close (blocks). They keep following and try again until a safe spot turns up. 0 = off.").defineInRange("teleport_hazard_radius", 3, 0, 6);
        TELEPORT_AVOID_LEDGES = B.comment("Pets won't teleport onto a ledge with a deadly drop beside it (a one-block bridge over the void, a cliff edge), where you could bump them off.",
                "They keep following and try again until a safe spot turns up.").define("teleport_avoid_ledges", true);
        WAIT_WHILE_OWNER_UNSAFE = B.comment("Pets don't teleport to you while you're swimming, in a water column, falling, gliding or flying; they come once you're on solid ground.").define("wait_while_owner_unsafe", true);
        PET_RESCUE = B.comment("Pets (and leashed mobs) that are drowning, in lava or falling to their death are teleported to safe ground near you,",
                "or to the last safe spot you stood on.").define("rescue", true);
        B.pop();

        B.comment("Villagers, iron golems and guards (tag commonsense:village_mobs).").push("villages");
        VILLAGE_HOMING = B.comment("Village mobs stranded more than 48 blocks from home walk back in stages instead of giving up when home is out of path range.").define("homing", true);
        VILLAGER_TELEPORT_HOME = B.comment("Villagers lost underground far from their bed, with no player leading them, teleport back to the village bell (or their bed).",
                "Villagers led away by a player (lead, boat, minecart, walking with them) drop their old village, so moved villagers stay put.",
                "Villagers near their job block are never considered lost, and villagers without a bed (e.g. freshly cured) are left alone.").define("teleport_lost_villagers_home", true);
        LOST_SECONDS = B.comment("Seconds a villager must be lost, with no player leading it, before it teleports home.").defineInRange("lost_seconds", 60, 10, 3600);
        ESCORT_RADIUS = B.comment("Any player this close to a village mob counts as leading it (so follow mods and escorting are never overridden).").defineInRange("escort_radius", 16.0, 4.0, 64.0);
        VILLAGER_LURE_FOLLOW = B.comment("Villagers follow a player holding an item from the commonsense:villager_lures tag (emerald block by default).",
                "Sleeping, trading or panicking villagers ignore it.").define("villagers_follow_lures", true);
        LURE_FOLLOW_RANGE = B.comment("How close (blocks) a villager must be to notice a lure.").defineInRange("lure_follow_range", 10.0, 2.0, 32.0);
        LURE_RADIUS = B.comment("A player holding an item from the commonsense:villager_lures tag (emerald block by default) this close counts as leading.").defineInRange("lure_radius", 24.0, 4.0, 64.0);
        B.pop();

        B.comment("Herd instinct (idea from Darkhax's Herd Mentality): hurting a mob alerts others of its kind nearby.").push("herd");
        HERD_INSTINCT = B.comment("When an animal is hurt, others of the same kind nearby take fright and run. Animals only: monsters are never affected.",
                "Pets and mobs in commonsense:excluded are never alerted.").define("herd_instinct", true);
        HERD_RANGE = B.comment("How far (blocks) the alarm spreads.").defineInRange("range", 8.0, 1.0, 32.0);
        HERD_FIGHT_BACK = B.comment("Animals that can fight back (llamas, polar bears, wolves...): only the single nearest one turns on the attacker,",
                "so a whole caravan or pack doesn't. Off: everyone just runs.").define("fight_back", true);
        HERD_FIGHT_RANGE = B.comment("The animal that fights back must be this close (blocks) to the one that was hit.").defineInRange("fight_back_range", 6.0, 1.0, 32.0);
        B.pop();

        B.push("tempt");
        RELIABLE_TEMPT = B.comment("Animals follow the moment you hold their food and stop the moment you put it away, with no 5-second wait before they can be tempted again.",
                "Cats and ocelots you startle still keep their wait. Replaces the Reliable Tempt and No Animal Tempt Delay mods.").define("reliable_tempt", true);
        B.pop();

        B.push("visuals");
        SLEEPING_RENDER_FIX = B.comment("Sleeping mobs stay visible as far away as awake ones. Vanilla shrinks a sleeping mob's hitbox to 0.2 blocks,",
                "which makes sleeping villagers disappear past about 12 blocks even in plain view.").define("sleeping_render_fix", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    public static boolean on() {
        return SPEC.isLoaded() && ENABLED.get();
    }
}
