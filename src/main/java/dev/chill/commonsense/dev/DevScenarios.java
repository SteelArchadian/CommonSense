package dev.chill.commonsense.dev;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** Dev-only scenario runner (enabled by -Dcommonsense.devtests=true). Never registered in normal play. */
public final class DevScenarios {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Deque<Scenario> QUEUE = new ArrayDeque<>();
    private static Scenario current;
    private static int index;

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sctest").requires(s -> s.hasPermission(2)).executes(ctx -> {
            QUEUE.clear();
            int repeat = Math.max(1, Integer.getInteger("sctest.repeat", 1));
            for (int r = 0; r < repeat; r++) queueAll();
            String only = System.getProperty("sctest.only", "");
            if (only.contains("perf_")) {   // load tests are opt-in: they take a while and only mean something A/B
                for (int r = 0; r < repeat; r++) {
                    QUEUE.add(new Perf("perf_crowd_off", false));
                    QUEUE.add(new Perf("perf_crowd_on", true));
                }
            }
            if (!only.isEmpty()) {
                java.util.List<String> prefixes = java.util.Arrays.asList(only.split(","));   // comma-separated name prefixes
                QUEUE.removeIf(sc -> prefixes.stream().noneMatch(sc.name::startsWith));
            }
            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("queued " + QUEUE.size()), false);
            return 1;
        }));
    }

    private static void queueAll() {
        QUEUE.add(new LeadWalk("lead_walk_armadillo", EntityType.ARMADILLO));
        QUEUE.add(new LeadWalk("lead_walk_cow", EntityType.COW));
        QUEUE.add(new LeadIce());
        QUEUE.add(new LeadCliff(false));
        QUEUE.add(new LeadCliff(true));
        QUEUE.add(new GolemLava());
        QUEUE.add(new PetMidFall());
        QUEUE.add(new PetDrowning());
        QUEUE.add(new VillagerCave("villager_lost_in_cave", true, false));
        QUEUE.add(new VillagerCave("villager_stolen_into_cave", true, true));
        QUEUE.add(new VillagerCave("villager_no_bed_in_cave", false, false));
        QUEUE.add(new GolemHoming("golem_walks_home"));
        QUEUE.add(new GolemHoming("golem_walks_home_2"));
        QUEUE.add(new GolemHoming("golem_walks_home_3"));
        QUEUE.add(new VillagerCave("villager_lure_holds_it", true, false, true));
        QUEUE.add(new VillagerCave("villager_lure_control_no_lure", true, false, false) {
            Vec3 playerStart() { return rel(84.5, -26, 24.5); }
        });
        QUEUE.add(new VillagerLure("villager_follows_lure", true, false));
        QUEUE.add(new VillagerLure("villager_stops_when_lure_put_away", true, true));
        QUEUE.add(new VillagerLure("villager_stops_when_lure_put_away_2", true, true));
        QUEUE.add(new VillagerLure("villager_stops_when_lure_put_away_3", true, true));
        QUEUE.add(new VillagerLure("villager_ignores_player_without_lure", false, false));
        QUEUE.add(new SleepingRender());
        QUEUE.add(new Herd("herd_cows_run", EntityType.COW, true, false));
        QUEUE.add(new Herd("herd_cows_control_off", EntityType.COW, false, false));
        QUEUE.add(new Herd("herd_spiders_ignored", EntityType.SPIDER, true, false));   // monsters are never part of a herd
        QUEUE.add(new Herd("herd_spiders_control_off", EntityType.SPIDER, false, false));
        QUEUE.add(new Herd("herd_wolves_fight", EntityType.WOLF, true, false));
        QUEUE.add(new Herd("herd_pets_never_alerted", EntityType.WOLF, true, true));
        QUEUE.add(new Herd("herd_goats_panic", EntityType.GOAT, true, false));
        QUEUE.add(new Herd("herd_llamas_one_defender", EntityType.LLAMA, true, false));   // a caravan doesn't all charge
        QUEUE.add(new HerdWary("herd_cows_bolt_together_and_stay_wary"));
        QUEUE.add(new TemptReach("tempt_range_bonus_reaches_12", true, false));
        QUEUE.add(new TemptReach("tempt_range_bonus_control_off", false, false));
        QUEUE.add(new TemptReach("herd_follow_straggler", true, true));
        QUEUE.add(new TemptReach("herd_follow_control_off", true, false) { { this.followOff = true; this.straggler = true; } });
        QUEUE.add(new FriendlyFire("manners_no_friendly_fire"));
        QUEUE.add(new GroundTie("manners_ground_tie_walks_back", true));
        QUEUE.add(new GroundTie("manners_ground_tie_control_off", false));
        QUEUE.add(new DodgeCreeper("manners_dodge_creeper", true));
        QUEUE.add(new DodgeCreeper("manners_dodge_creeper_control_off", false));
        QUEUE.add(new Trample("manners_no_trampling", true));
        QUEUE.add(new Trample("manners_trampling_control_off", false));
        QUEUE.add(new Restraint("manners_pet_restraint", true));
        QUEUE.add(new Restraint("manners_pet_restraint_control_off", false));
        QUEUE.add(new Portal("manners_portal_follow"));
        QUEUE.add(new GiveUp("manners_give_up_unreachable", true));
        QUEUE.add(new GiveUp("manners_give_up_control_off", false));
        QUEUE.add(new Shove("manners_no_pet_shove", true));
        QUEUE.add(new Shove("manners_no_pet_shove_control_off", false));
        QUEUE.add(new ShootThrough("manners_arrow_through_pet", true));
        QUEUE.add(new ShootThrough("manners_arrow_through_pet_control_off", false));
        QUEUE.add(new Lurk("hostile_zombie_lurks", true));
        QUEUE.add(new Lurk("hostile_zombie_lurks_control_off", false));
        QUEUE.add(new DoorWatch("hostile_pillager_waits_at_door", EntityType.PILLAGER, true, false));
        QUEUE.add(new DoorWatch("hostile_pillager_watches_window", EntityType.PILLAGER, true, true));
        QUEUE.add(new DoorWatch("hostile_pillager_door_control_off", EntityType.PILLAGER, false, false));
        QUEUE.add(new DoorWatch("hostile_skeleton_shoots_through_window", EntityType.SKELETON, true, true));
        QUEUE.add(new DoorWatch("hostile_skeleton_window_control_off", EntityType.SKELETON, false, true));
        QUEUE.add(new DoorWatch("hostile_creeper_keeps_away", EntityType.CREEPER, true, false));
        QUEUE.add(new Ambush("hostile_spider_ambushes", true));
        QUEUE.add(new Ambush("hostile_spider_ambush_control_off", false));
        QUEUE.add(new VillagerHazard("village_avoid_wither_roses", true));
        QUEUE.add(new VillagerHazard("village_avoid_wither_roses_control_off", false));
        QUEUE.add(new CactusGap("clear_golem_cactus_gap", EntityType.IRON_GOLEM, true));
        QUEUE.add(new CactusGap("clear_golem_cactus_gap_control_off", EntityType.IRON_GOLEM, false));
        QUEUE.add(new CactusGap("clear_villager_cactus_gap", EntityType.VILLAGER, true));
        QUEUE.add(new CactusGap("clear_villager_cactus_gap_control_off", EntityType.VILLAGER, false));
        QUEUE.add(new Bumped("clear_horse_bumped", true));
        QUEUE.add(new Cliff("clear_cliff_edge", "drop", true));
        QUEUE.add(new Cliff("clear_cliff_edge_control_off", "drop", false));
        QUEUE.add(new Cliff("clear_cliff_short_drop_cuts_corners", "short", true));
        QUEUE.add(new Cliff("clear_cliff_falling_lava", "lava", true));
        QUEUE.add(new Cliff("clear_cliff_falling_lava_control_off", "lava", false));
        QUEUE.add(new Bumped("clear_horse_bumped_control_off", false));
        QUEUE.add(new FirePat("fire_villager_pats_out", EntityType.VILLAGER, true));
        QUEUE.add(new FirePat("fire_pillager_pats_out", EntityType.PILLAGER, true));
        QUEUE.add(new FirePat("fire_pat_control_off", EntityType.VILLAGER, false));
        QUEUE.add(new DoorTurns("door_villagers_take_turns", true));
        QUEUE.add(new DoorTurns("door_villagers_control_off", false));
        QUEUE.add(new DoorCrush("door_crush_zombie_scare", true));
        QUEUE.add(new DoorCrush("door_crush_control_off", false));
        QUEUE.add(new PetTeleportSafety("pet_teleport_not_by_lava", true));
        QUEUE.add(new PetTeleportSafety("pet_teleport_control_off", false));
        QUEUE.add(new HideScene("flee_chicken_hides_in_hole", "hole", true));
        QUEUE.add(new HideScene("flee_chicken_hides_in_foliage", "foliage", true));
        QUEUE.add(new HideScene("flee_chicken_ignores_small_attacker", "small", true));
        QUEUE.add(new HideScene("flee_villager_runs_through_door", "door", true));
        QUEUE.add(new HideScene("flee_hole_control_off", "hole", false));
        QUEUE.add(new HideScene("flee_door_control_off", "door", false));
        QUEUE.add(new FirePat("fire_cleric_throws_water", EntityType.VILLAGER, true) {
            @Override void spawn() {
                super.spawn();
                var v = (net.minecraft.world.entity.npc.Villager) this.mob;
                v.setVillagerData(v.getVillagerData().setProfession(net.minecraft.world.entity.npc.VillagerProfession.CLERIC));
            }
        });
        QUEUE.add(new FirePat("fire_guard_reaches_far", EntityType.PILLAGER, true) {
            @Override void build() {
                box(-24, -1, -24, 24, -1, 24, Blocks.STONE);
                this.level.setBlock(at(16, 0, 0), Blocks.FIRE.defaultBlockState(), 2);
            }
            @Override void spawn() { super.spawn(); this.mob.addTag(dev.chill.commonsense.Courtesy.GUARD_TAG); }
            @Override void script() {
                if (this.tick < 300) this.mob.setTarget(null);
                if (this.gone < 0 && !this.level.getBlockState(at(16, 0, 0)).is(Blocks.FIRE)) this.gone = this.tick;
                if (this.tick == 310) { this.measured = "patOutTick=" + this.gone; this.ok = this.gone >= 0; }
            }
        });
        QUEUE.add(new FirePat("fire_far_control_unmarked", EntityType.PILLAGER, true) {
            @Override void build() {
                box(-24, -1, -24, 24, -1, 24, Blocks.STONE);
                this.level.setBlock(at(16, 0, 0), Blocks.FIRE.defaultBlockState(), 2);
            }
            @Override void script() {
                if (this.tick < 300) this.mob.setTarget(null);
                if (this.gone < 0 && !this.level.getBlockState(at(16, 0, 0)).is(Blocks.FIRE)) this.gone = this.tick;
                if (this.tick == 310) { this.measured = "patOutTick=" + this.gone; this.ok = this.gone < 0; }
            }
        });
        QUEUE.add(new GolemFire("fire_golem_cannot_burn", true));
        QUEUE.add(new GolemFire("fire_golem_control_off", false));
        QUEUE.add(new PathPartial("path_villager_crosses_carpet", "carpet"));
        QUEUE.add(new PathPartial("path_villager_crosses_snow_layers", "snow"));
        QUEUE.add(new PathPartial("path_villager_crosses_slabs", "slab"));
        QUEUE.add(new PathPartial("path_villager_crosses_mixed_doorway", "mixed"));
    }

    /** Send any mob somewhere: brain mobs by walk target, goal mobs by navigation. */
    static void sendTo(Mob mob, Vec3 to) {
        var brain = mob.getBrain();
        if (brain.checkMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED)) {
            brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(to, 0.6F, 0));
        } else {
            mob.getNavigation().moveTo(to.x, to.y, to.z, 0.7);
        }
    }

    /**
     * A wall with a one-block gap flanked by cactus, and somewhere the mob wants to be on the other side. An iron golem (1.4 wide)
     * can't fit and must not grind into the cactus trying; a villager (0.6) may go through but only dead centre, unhurt.
     */
    static class CactusGap extends Scenario {
        final boolean on;
        final EntityType<? extends Mob> type;
        boolean was;
        Mob mob;
        boolean crossed;
        String measured = "not measured";
        boolean ok;

        CactusGap(String name, EntityType<? extends Mob> type, boolean on) { super(name, 300); this.type = type; this.on = on; }

        void build() {
            box(-11, -1, -9, 11, -1, 9, Blocks.STONE);
            box(-11, 0, -9, 11, 2, -9, Blocks.STONE);
            box(-11, 0, 9, 11, 2, 9, Blocks.STONE);
            box(-11, 0, -9, -11, 2, 9, Blocks.STONE);
            box(11, 0, -9, 11, 2, 9, Blocks.STONE);
            // cactus pops if anything solid is beside it, so the wall stops a block short and the pockets beside it are
            // one-wide cactus gaps too (cactus | air | stone)
            box(-10, 0, 0, -3, 2, 0, Blocks.STONE);
            box(3, 0, 0, 10, 2, 0, Blocks.STONE);
            box(-1, -2, 0, 1, -2, 0, Blocks.STONE);   // sand falls into the void otherwise
            box(-1, -1, 0, 1, -1, 0, Blocks.SAND);
            for (int y = 0; y <= 2; y++) {
                this.level.setBlock(at(-1, y, 0), Blocks.CACTUS.defaultBlockState(), 2 | 16);
                this.level.setBlock(at(1, y, 0), Blocks.CACTUS.defaultBlockState(), 2 | 16);
            }
        }

        Vec3 playerStart() { return rel(-9.5, 0, 7.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HAZARD_AVOIDANCE.get();
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.on);
            this.mob = mob(this.type, 0.5, 0, -5.5);
        }

        void script() {
            if (this.tick % 20 == 0 && this.mob.getZ() < this.o.getZ() + 3.0) sendTo(this.mob, rel(0.5, 0, 6.5));
            if (this.mob.getZ() > this.o.getZ() + 1.5) this.crossed = true;
            if (this.tick % 60 == 0) LOG.info("[SCTRACE] {} t={} at=({}) hp={}", this.name, this.tick,
                    String.format("%.2f,%.2f", this.mob.getX() - this.o.getX(), this.mob.getZ() - this.o.getZ()), this.mob.getHealth());
            if (this.tick == 290) {
                float lost = this.mob.getMaxHealth() - this.mob.getHealth();
                boolean cactus = this.level.getBlockState(at(-1, 0, 0)).is(Blocks.CACTUS) && this.level.getBlockState(at(1, 0, 0)).is(Blocks.CACTUS);
                this.measured = String.format("lost=%.1f crossed=%s cactusIntact=%s", lost, this.crossed, cactus);
                boolean wide = this.mob.getBbWidth() > 1.0F;
                this.ok = !this.on || (cactus && lost <= 0.0F && (wide || this.crossed));
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.was);
            super.cleanup();
        }
    }

    /** A cow walks the lip of a 3-wide strip: a deep drop (or falling lava) keeps it half a block off the edge, a short drop doesn't. */
    static class Cliff extends Scenario {
        final String kind;
        final boolean on;
        boolean was;
        Mob mob;
        double minGap = 9.0;
        boolean arrived;
        String measured = "not measured";
        boolean ok;

        Cliff(String name, String kind, boolean on) { super(name, 400); this.kind = kind; this.on = on; }

        void build() {
            box(-1, -1, -9, 1, -1, 9, Blocks.STONE);   // the strip; its east lip is at x=2.0
            box(-6, -20, -9, 6, -20, 9, Blocks.STONE);   // far below, so a slip is survivable to measure
            if (this.kind.equals("short")) {
                box(2, -3, -9, 6, -3, 9, Blocks.STONE);   // a 2-block drop
            }
            if (this.kind.equals("lava")) {
                box(2, -1, -9, 2, -1, -9, Blocks.STONE);
                this.level.setBlock(at(2, 0, -2), Blocks.LAVA.defaultBlockState(), 3);   // pours over the lip as falling lava
                this.level.setBlock(at(2, 0, 2), Blocks.LAVA.defaultBlockState(), 3);
                this.level.setBlock(at(2, 0, 6), Blocks.LAVA.defaultBlockState(), 3);
            }
        }

        Vec3 playerStart() { return rel(-0.5, 0, -8.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HAZARD_AVOIDANCE.get();
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.on);
            this.mob = mob(EntityType.COW, 1.2, 0, -8.0);
        }

        void script() {
            if (this.tick % 20 == 0) sendTo(this.mob, rel(1.5, 0, 8.5));
            if (this.mob.onGround() && this.mob.getZ() > this.o.getZ() - 7.5) {
                this.minGap = Math.min(this.minGap, 2.0 - (this.mob.getBoundingBox().maxX - this.o.getX()));
            }
            if (this.mob.getZ() > this.o.getZ() + 5.0) this.arrived = true;
            if (this.tick % 60 == 0) LOG.info("[SCTRACE] {} t={} at=({}) gap={}", this.name, this.tick,
                    String.format("%.2f,%.2f", this.mob.getX() - this.o.getX(), this.mob.getZ() - this.o.getZ()), String.format("%.2f", this.minGap));
            if (this.tick == 380) {
                float lost = this.mob.getMaxHealth() - this.mob.getHealth();
                boolean fell = this.mob.getY() < this.o.getY() - 1.0;
                this.measured = String.format("minGap=%.2f arrived=%s lost=%.1f fell=%s", this.minGap, this.arrived, lost, fell);
                this.ok = switch (this.kind) {
                    case "short" -> lost <= 0.0F;   // free to hug the lip of a short drop (even to hop down it: 2 blocks hurts nothing)
                    default -> !this.on || (this.minGap >= 0.3 && !fell && lost <= 0.0F);
                };
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.was);
            super.cleanup();
        }
    }

    /** An iron golem set alight stops burning; a cow set alight (control) keeps burning. */
    static class GolemFire extends Scenario {
        final boolean on;
        boolean was;
        Mob mob;
        int burning;
        String measured = "not measured";
        boolean ok;

        GolemFire(String name, boolean on) { super(name, 100); this.on = on; }

        void build() { box(-6, -1, -6, 6, -1, 6, Blocks.STONE); }

        Vec3 playerStart() { return rel(0.5, 0, -4.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.GOLEM_FIREPROOF.get();
            dev.chill.commonsense.Config.GOLEM_FIREPROOF.set(this.on);
            this.mob = mob(this.on ? EntityType.IRON_GOLEM : EntityType.IRON_GOLEM, 0.5, 0, 0.5);
        }

        void script() {
            if (this.tick < 40 && this.tick % 5 == 0) this.mob.setRemainingFireTicks(100);
            if (this.tick >= 10 && this.tick < 40 && this.tick % 5 == 3 && this.mob.isOnFire()) this.burning++;
            if (this.tick == 90) {
                this.measured = String.format("burningTicksSampled=%d onFireAtEnd=%s", this.burning, this.mob.isOnFire());
                this.ok = this.on ? (this.burning == 0 && !this.mob.isOnFire()) : this.burning >= 4;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.GOLEM_FIREPROOF.set(this.was);
            super.cleanup();
        }
    }

    /** A horse grazing right up against a cactus gets jostled toward it now and then; it shouldn't get pricked. */
    static class Bumped extends Scenario {
        final boolean on;
        boolean was;
        Mob horse;
        String measured = "not measured";
        boolean ok;

        Bumped(String name, boolean on) { super(name, 260); this.on = on; }

        void build() {
            box(-8, -2, -8, 8, -2, 8, Blocks.STONE);   // sand falls into the void otherwise
            box(-8, -1, -8, 8, -1, 8, Blocks.SAND);
            for (int x = -8; x <= 8; x += 2) {   // cactus can't stand side by side
                this.level.setBlock(at(x, 0, 2), Blocks.CACTUS.defaultBlockState(), 2 | 16);
                this.level.setBlock(at(x, 1, 2), Blocks.CACTUS.defaultBlockState(), 2 | 16);
            }
        }

        Vec3 playerStart() { return rel(-7.5, 0, -7.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HAZARD_AVOIDANCE.get();
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.on);
            // box edge 0.05 short of the cactus row's block space (it pricks anything inside its block, not just its spines)
            this.horse = mob(EntityType.HORSE, 0.5, 0, 2.0 - 0.05 - EntityType.HORSE.getWidth() / 2.0);
        }

        void script() {
            if (this.tick < 3 || this.tick % 50 == 0) LOG.info("[SCTRACE] {} t={} cactus={} horseZ={} hp={}/{}", this.name, this.tick,
                    this.level.getBlockState(at(0, 0, 2)), String.format("%.2f", this.horse.getZ() - this.o.getZ()), this.horse.getHealth(), this.horse.getMaxHealth());
            if (this.tick >= 40 && this.tick % 40 == 0) {
                this.horse.setDeltaMovement(this.horse.getDeltaMovement().add(0, 0, 0.1));   // a nudge, like another animal shouldering past
            }
            if (this.tick == 250) {
                float lost = this.horse.getMaxHealth() - this.horse.getHealth();
                double gap = 2.0 - (this.horse.getBoundingBox().maxZ - this.o.getZ());
                var src = this.horse.getLastDamageSource();
                this.measured = String.format("lost=%.1f gap=%.2f by=%s", lost, gap, src == null ? "-" : src.getMsgId());
                this.ok = !this.on || lost <= 0.0F;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.was);
            super.cleanup();
        }
    }

    /** A small fire on the stone floor nearby gets patted out; the netherrack fireplace is left burning. */
    static class FirePat extends Scenario {
        final boolean on;
        final EntityType<? extends Mob> type;
        boolean was;
        boolean fireTick;
        Mob mob;
        int gone = -1;
        String measured = "not measured";
        boolean ok;

        FirePat(String name, EntityType<? extends Mob> type, boolean on) { super(name, 320); this.type = type; this.on = on; }

        void build() {
            box(-8, -1, -8, 8, -1, 8, Blocks.STONE);
            this.level.setBlock(at(4, 0, 0), Blocks.FIRE.defaultBlockState(), 2);
            this.level.setBlock(at(-4, -1, 0), Blocks.NETHERRACK.defaultBlockState(), 2);
            this.level.setBlock(at(-4, 0, 0), Blocks.FIRE.defaultBlockState(), 2);
        }

        Vec3 playerStart() { return rel(0.5, 0, -7.5); }

        void spawn() {
            var rule = this.level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
            this.fireTick = rule.get();
            rule.set(false, this.level.getServer());   // no burning out on its own: only patting puts it out
            this.was = dev.chill.commonsense.Config.FIRE_PATTING.get();
            dev.chill.commonsense.Config.FIRE_PATTING.set(this.on);
            this.mob = mob(this.type, 0.5, 0, 0.5);
            if (this.type == EntityType.PILLAGER) {
                this.mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
                // calm: it would target the test player every tick before the script could clear it (a raider at rest, not a fight)
                ((dev.chill.commonsense.mixin.MobGoalsAccessor) this.mob).commonsense$targetSelector().removeAllGoals(g -> true);
            }
        }

        void script() {
            if (this.tick < 300) this.mob.setTarget(null);   // calm: nothing to fight
            if (this.gone < 0 && !this.level.getBlockState(at(4, 0, 0)).is(Blocks.FIRE)) this.gone = this.tick;
            if (this.gone < 0 && this.tick % 20 == 0) LOG.info("[SCTRACE] {} t={} at=({}) target={} {}", this.name, this.tick,
                    String.format("%.1f,%.1f", this.mob.getX() - this.o.getX(), this.mob.getZ() - this.o.getZ()), this.mob.getTarget(),
                    dev.chill.commonsense.Courtesy.fireDebug(this.mob));
            if (this.tick == 310) {
                boolean hearth = this.level.getBlockState(at(-4, 0, 0)).is(Blocks.FIRE);
                this.measured = String.format("patOutTick=%d hearthKept=%s hp=%.1f", this.gone, hearth, this.mob.getHealth());
                this.ok = this.on ? this.gone >= 0 && hearth && this.mob.getHealth() >= this.mob.getMaxHealth() : this.gone < 0;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.FIRE_PATTING.set(this.was);
            this.level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(this.fireTick, this.level.getServer());
            super.cleanup();
        }
    }

    /** A villager sent across partial-height blocks (carpet, snow layers, slabs) and through a door: must get across, not stall. */
    static class PathPartial extends Scenario {
        final String kind;
        Mob villager;
        int doneAt = -1;
        String measured = "not measured";
        boolean ok;

        PathPartial(String name, String kind) { super(name, 400); this.kind = kind; }

        void build() {
            box(-9, -1, -3, 11, -1, 3, Blocks.STONE);
            box(0, 0, -3, 0, 2, 3, Blocks.STONE);
            box(-9, 0, -3, 11, 2, -3, Blocks.STONE);
            box(-9, 0, 3, 11, 2, 3, Blocks.STONE);
            var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
            this.level.setBlock(at(0, 0, 0), lower, 2);
            this.level.setBlock(at(0, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            var snow = Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 3);
            var slab = Blocks.OAK_SLAB.defaultBlockState();
            for (int z = -2; z <= 2; z++) {
                switch (this.kind) {
                    case "carpet" -> { this.level.setBlock(at(-1, 0, z), Blocks.RED_CARPET.defaultBlockState(), 2); this.level.setBlock(at(1, 0, z), Blocks.RED_CARPET.defaultBlockState(), 2); }
                    case "snow" -> { this.level.setBlock(at(-1, 0, z), snow, 2); this.level.setBlock(at(1, 0, z), snow, 2); this.level.setBlock(at(3, 0, z), snow, 2); }
                    case "slab" -> { this.level.setBlock(at(-2, 0, z), slab, 2); this.level.setBlock(at(2, 0, z), slab, 2); this.level.setBlock(at(5, 0, z), slab, 2); }
                    default -> {
                        this.level.setBlock(at(-1, 0, z), snow, 2);
                        this.level.setBlock(at(1, 0, z), Blocks.RED_CARPET.defaultBlockState(), 2);
                        this.level.setBlock(at(2, 0, z), slab, 2);
                        this.level.setBlock(at(4, 0, z), snow, 2);
                    }
                }
            }
        }

        Vec3 playerStart() { return rel(-8.5, 0, 2.0); }

        void spawn() { this.villager = mob(EntityType.VILLAGER, -5.5, 0, 0.5); }

        void script() {
            if (this.doneAt < 0 && this.tick % 10 == 0) sendTo(this.villager, rel(8.5, 0, 0.5));
            if (this.doneAt < 0 && this.villager.getX() > this.o.getX() + 7.0) this.doneAt = this.tick;
            if (this.tick % 40 == 0) LOG.info("[SCTRACE] {} t={} x={} y={} door={} navDone={} path={} wt={} unstick={}", this.name, this.tick, String.format("%.2f", this.villager.getX() - this.o.getX()), String.format("%.3f", this.villager.getY() - this.o.getY()), this.level.getBlockState(at(0, 0, 0)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), this.villager.getNavigation().isDone(), this.villager.getNavigation().getPath() == null ? "null" : this.villager.getNavigation().getPath().getNextNodeIndex() + "/" + this.villager.getNavigation().getPath().getNodeCount() + " end=" + this.villager.getNavigation().getPath().getEndNode() + " reach=" + this.villager.getNavigation().getPath().canReach(), this.villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).isPresent(), dev.chill.commonsense.Unstick.debug(this.villager));
            if (this.tick == 390) {
                this.measured = String.format("crossedAt=%d", this.doneAt);
                this.ok = this.doneAt >= 0;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }
    }

    /** Two villagers want through the same door from opposite sides: one waits, one goes, then the other. */
    static class DoorTurns extends Scenario {
        final boolean on;
        boolean was;
        Mob west;
        Mob east;
        int westAt = -1;
        int eastAt = -1;
        String measured = "not measured";
        boolean ok;

        DoorTurns(String name, boolean on) { super(name, 400); this.on = on; }

        void build() {
            box(-9, -1, -4, 9, -1, 4, Blocks.STONE);
            box(0, 0, -4, 0, 2, 4, Blocks.STONE);
            box(-9, 0, -4, 9, 2, -4, Blocks.STONE);
            box(-9, 0, 4, 9, 2, 4, Blocks.STONE);
            var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
            this.level.setBlock(at(0, 0, 0), lower, 2);
            this.level.setBlock(at(0, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        }

        Vec3 playerStart() { return rel(-8.5, 0, 3.0); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.DOOR_TURNS.get();
            dev.chill.commonsense.Config.DOOR_TURNS.set(this.on);
            this.west = mob(EntityType.VILLAGER, -3.5, 0, 0.5);
            this.east = mob(EntityType.VILLAGER, 4.5, 0, 0.5);
        }

        void script() {
            if (this.westAt < 0 && this.tick % 10 == 0) sendTo(this.west, rel(6.5, 0, 0.5));
            if (this.eastAt < 0 && this.tick % 10 == 0) sendTo(this.east, rel(-5.5, 0, 0.5));
            if (this.westAt < 0 && this.west.getX() > this.o.getX() + 4.0) this.westAt = this.tick;
            if (this.eastAt < 0 && this.east.getX() < this.o.getX() - 3.0) this.eastAt = this.tick;
            if (this.tick % 40 == 0) LOG.info("[SCTRACE] {} t={} west={} east={}", this.name, this.tick,
                    String.format("%.1f", this.west.getX() - this.o.getX()), String.format("%.1f", this.east.getX() - this.o.getX()));
            if (this.tick == 390) {
                this.measured = String.format("westThrough=%d eastThrough=%d", this.westAt, this.eastAt);
                this.ok = !this.on || (this.westAt >= 0 && this.eastAt >= 0);
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.DOOR_TURNS.set(this.was);
            super.cleanup();
        }
    }

    /**
     * Five villagers in a room with a zombie boxed in its corner, the only way out one door, and one more villager
     * outside wanting in: everyone crams the doorway at once. Counts who gets through and how often the door flips.
     */
    static class DoorCrush extends Scenario {
        final boolean on;
        boolean was;
        final List<Mob> inside = new ArrayList<>();
        Mob comer;
        Mob zombie;
        int out = 0;
        int lastOut = -1;
        int comerIn = -1;
        int flips = 0;
        boolean wasOpen;
        String measured = "not measured";
        boolean ok;

        DoorCrush(String name, boolean on) { super(name, 600); this.on = on; }

        void build() {
            box(-8, -1, -4, 9, -1, 4, Blocks.STONE);
            box(0, 0, -4, 0, 2, 4, Blocks.STONE);
            box(-8, 0, -4, 9, 2, -4, Blocks.STONE);
            box(-8, 0, 4, 9, 2, 4, Blocks.STONE);
            box(-8, 0, -4, -8, 2, 4, Blocks.STONE);
            box(9, 0, -4, 9, 2, 4, Blocks.STONE);
            box(-8, 3, -4, 0, 3, 4, Blocks.STONE);   // roofed room: the zombie doesn't burn
            box(-6, 0, -3, -6, 2, -3, Blocks.GLASS);   // glass cell in the corner: seen, can't get out
            box(-7, 0, -2, -6, 2, -2, Blocks.GLASS);
            var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
            this.level.setBlock(at(0, 0, 0), lower, 2);
            this.level.setBlock(at(0, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        }

        Vec3 playerStart() { return rel(7.5, 0, 3.0); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.DOOR_TURNS.get();
            dev.chill.commonsense.Config.DOOR_TURNS.set(this.on);
            this.zombie = mob(EntityType.ZOMBIE, -6.5, 0, -2.5);
            for (int i = 0; i < 5; i++) {
                this.inside.add(mob(EntityType.VILLAGER, -4.5 + (i % 3) * 1.5, 0, -1.5 + (i / 3) * 2.5));
            }
            this.comer = mob(EntityType.VILLAGER, 5.5, 0, 0.5);
        }

        void script() {
            if (this.tick % 10 == 0) {
                for (Mob v : this.inside) {
                    if (v.getX() < this.o.getX() + 3.0) sendTo(v, rel(7.0, 0, 0.5));   // out, away from the zombie
                }
                if (this.comerIn < 0) sendTo(this.comer, rel(-3.5, 0, 2.5));
            }
            int now = 0;
            for (Mob v : this.inside) if (v.getX() > this.o.getX() + 2.0) now++;
            if (now > this.out) { this.out = now; this.lastOut = this.tick; }
            if (this.comerIn < 0 && this.comer.getX() < this.o.getX() - 1.5) this.comerIn = this.tick;
            boolean open = this.level.getBlockState(at(0, 0, 0)).getOptionalValue(net.minecraft.world.level.block.DoorBlock.OPEN).orElse(false);
            if (this.tick > 0 && open != this.wasOpen) this.flips++;
            this.wasOpen = open;
            if (this.tick % 40 == 0) LOG.info("[SCTRACE] {} t={} out={} comerX={} flips={}", this.name, this.tick, now,
                    String.format("%.1f", this.comer.getX() - this.o.getX()), this.flips);
            if (this.tick == 590) {
                this.measured = String.format("out=%d/5 lastOutTick=%d comerInTick=%d doorFlips=%d", this.out, this.lastOut, this.comerIn, this.flips);
                this.ok = !this.on || (this.out == 5 && this.comerIn >= 0);
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.DOOR_TURNS.set(this.was);
            super.cleanup();
        }
    }

    /**
     * You stand on a little island in a lava pool far from your dog: it must not teleport in next to the lava. Then you step
     * onto safe ground and it comes.
     */
    static class PetTeleportSafety extends Scenario {
        final boolean on;
        boolean was;
        Mob dog;
        double closestLava = 99;
        int landings;
        Vec3 prev;
        int arrived = -1;
        String measured = "not measured";
        boolean ok;

        PetTeleportSafety(String name, boolean on) { super(name, 400); this.on = on; }

        void build() {
            box(-4, -1, -4, 44, -1, 4, Blocks.STONE);
            box(-4, -1, -8, 44, -1, 8, Blocks.STONE);
            box(15, -1, -7, 29, -1, 7, Blocks.LAVA);   // the pool
            box(19, -1, -3, 25, -1, 3, Blocks.STONE);   // 7x7 island: vanilla lands anywhere on it, only its middle is 4+ from lava
        }

        Vec3 playerStart() { return rel(22.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.SAFE_PET_TELEPORT.get();
            dev.chill.commonsense.Config.SAFE_PET_TELEPORT.set(this.on);
            net.minecraft.world.entity.animal.Wolf w = mob(EntityType.WOLF, 0.5, 0, 0.5);
            w.tame(this.player);
            this.dog = w;
        }

        void script() {
            Vec3 where = this.tick < 200 ? rel(22.5, 0, 0.5) : rel(40.5, 0, 0.5);
            moveTo(where.x, where.y, where.z);
            boolean jumped = this.prev != null && this.dog.position().distanceTo(this.prev) > 4.0;   // a teleport, not a walk
            this.prev = this.dog.position();
            if (this.tick < 200 && jumped) {
                // a teleport landing: how near (box distance, like the hazard radius) the closest lava is
                BlockPos at = this.dog.blockPosition();
                for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -2; dy <= 1; dy++) {
                    if (this.level.getFluidState(at.offset(dx, dy, dz)).is(net.minecraft.tags.FluidTags.LAVA)) {
                        this.closestLava = Math.min(this.closestLava, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
                this.landings++;
            } else if (this.arrived < 0 && this.dog.distanceToSqr(where) < 6 * 6) {
                this.arrived = this.tick;
            }
            if (this.tick % 40 == 0) LOG.info("[SCTRACE] {} t={} dog=({}) hp={}", this.name, this.tick,
                    String.format("%.1f,%.1f", this.dog.getX() - this.o.getX(), this.dog.getZ() - this.o.getZ()), this.dog.getHealth());
            if (this.tick == 390) {
                this.measured = String.format("landingsByPool=%d nearestLava=%.0f arrivedTick=%d hp=%.1f", this.landings, this.closestLava, this.arrived, this.dog.getHealth());
                this.ok = this.on ? this.closestLava > 3 && this.arrived >= 0 && this.dog.getHealth() >= this.dog.getMaxHealth()
                        : this.landings > 0 && this.closestLava <= 3;   // calibration: vanilla must land it by the lava, or the scene proves nothing
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.SAFE_PET_TELEPORT.set(this.was);
            super.cleanup();
        }
    }

    /** Attacked mobs run for cover: a hole or foliage (small mobs) or a door (villagers). "small" = hole present but the attacker is small too. */
    static class HideScene extends Scenario {
        final String kind;
        final boolean on;
        boolean was;
        Mob victim;
        Mob attacker;
        String measured = "not measured";
        boolean ok;

        HideScene(String name, String kind, boolean on) { super(name, 300); this.kind = kind; this.on = on; }

        void build() {
            box(-8, -1, -16, 24, -1, 12, this.kind.equals("foliage") ? Blocks.GRASS_BLOCK : Blocks.STONE);
            if (this.kind.equals("hole") || this.kind.equals("small")) {
                box(10, 0, -1, 12, 1, 1, Blocks.STONE);   // a 3-long block with a 1x1 tunnel along x
                box(10, 0, 0, 12, 0, 0, Blocks.AIR);
            } else if (this.kind.equals("foliage")) {
                for (int x = 9; x <= 11; x++) {
                    this.level.setBlock(at(x, 0, 0), Blocks.TALL_GRASS.defaultBlockState(), 2 | 16);
                    this.level.setBlock(at(x, 1, 0), Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2 | 16);
                }
            } else {
                box(2, 0, -12, 8, 3, -6, Blocks.STONE);   // a closed room north of the start with one door in its south wall
                box(3, 0, -11, 7, 2, -7, Blocks.AIR);
                this.level.setBlock(at(4, 0, -6), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.SOUTH), 2 | 16);
                this.level.setBlock(at(4, 1, -6), Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.SOUTH)
                        .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2 | 16);
            }
        }

        Vec3 playerStart() { return rel(-12, 0, 8); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HIDE_FROM_ATTACKERS.get();
            dev.chill.commonsense.Config.HIDE_FROM_ATTACKERS.set(this.on);
            if (this.kind.equals("door")) {
                this.victim = mob(EntityType.VILLAGER, 0.5, 0, 0.5);
                this.attacker = mob(EntityType.ZOMBIE, -4.5, 0, 4.5);
            } else {
                this.victim = mob(EntityType.CHICKEN, 0.5, 0, 0.5);
                this.attacker = this.kind.equals("small") ? mob(EntityType.RABBIT, -4.5, 0, 0.5) : mob(EntityType.PILLAGER, -4.5, 0, 0.5);
            }
            this.attacker.setNoAi(true);
            this.attacker.setSilent(true);
        }

        private boolean passedDoor;

        void script() {
            Vec3 hold = rel(-12, 0, 8);
            moveTo(hold.x, hold.y, hold.z);
            if (this.tick == 5 || this.tick == 25) {
                this.victim.hurt(this.level.damageSources().mobAttack(this.attacker), 0.5F);
                this.victim.setLastHurtByMob(this.attacker);
            }
            if (this.kind.equals("door") && this.victim.getZ() - this.o.getZ() < -6.1) this.passedDoor = true;   // it may run on after the door
            if (this.tick % 40 == 0) LOG.info("[SCTRACE] {} t={} v=({}) hp={}", this.name, this.tick,
                    String.format("%.1f,%.1f", this.victim.getX() - this.o.getX(), this.victim.getZ() - this.o.getZ()), this.victim.getHealth());
            if (this.tick == 280) {
                double x = this.victim.getX() - this.o.getX();
                double z = this.victim.getZ() - this.o.getZ();
                boolean there = switch (this.kind) {
                    case "hole" -> x >= 10 && x <= 13 && Math.abs(z) < 1.0;
                    case "foliage" -> x >= 8.5 && x <= 12.5 && Math.abs(z) < 1.0;
                    case "door" -> this.passedDoor && this.victim.isAlive();
                    default -> !(x >= 10 && x <= 13 && Math.abs(z) < 1.0);
                };
                this.measured = String.format("victimAt=(%.1f,%.1f) %s", x, z, there ? "covered" : "exposed");
                this.ok = !this.on || there;
                if (!this.on) this.ok = true;   // control: vanilla result is only reported
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HIDE_FROM_ATTACKERS.set(this.was);
            super.cleanup();
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (current == null) {
            if (QUEUE.isEmpty()) {
                return;
            }
            current = QUEUE.poll();
            current.start(server, server.overworld(), new BlockPos(3000 + 200 * index++, 100, 0));
        }
        try {
            current.step();
        } catch (Exception e) {
            current.result = "FAIL exception " + e;
        }
        if (current.result != null || current.tick >= current.duration) {
            String verdict = current.result != null ? current.result : current.verdict();
            LOG.info("[SCTEST] {} {} | playerMoved={}", current.name, verdict, String.format("%.1f", current.player.position().distanceTo(current.startPos)));
            if (verdict.startsWith("FAIL") && current.traced != null) {
                LOG.info("[SCTRACE] {} {}\n{}", current.name, current.fallTrace != null ? "fell at t=" + current.firstFallTick : "last ticks", current.fallTrace != null ? current.fallTrace : String.join("\n", current.trace));
            }
            if (verdict.startsWith("FAIL") && !current.history.isEmpty()) {
                LOG.info("[SCTRACE] {} history\n{}", current.name, String.join("\n", current.history));
            }
            current.cleanup();
            current = null;
            if (QUEUE.isEmpty()) {
                LOG.info("[SCTEST] DONE");
            }
        }
    }

    // ------------------------------------------------------------------ framework

    abstract static class Scenario {
        final String name;
        final int duration;
        int tick;
        String result;
        ServerLevel level;
        BlockPos o;
        ServerPlayer player;
        Vec3 cursor;
        Vec3 startPos;
        final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
        Mob traced;

        void traceTick() {
            if (this.traced == null) return;
            Mob m = this.traced;
            var st = m.getExistingDataOrNull(dev.chill.commonsense.CommonSense.STATE);
            var nav = m.getNavigation();
            var path = nav.getPath();
            String t = String.format("t=%d pos=(%.2f,%.2f,%.2f) vel=(%.3f,%.3f,%.3f) ground=%s dist=%.2f boost=%.2f spd=%.3f step=%.1f path=%s%s",
                    this.tick, m.getX() - this.o.getX(), m.getY() - this.o.getY(), m.getZ() - this.o.getZ(),
                    m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z, m.onGround(), m.distanceTo(this.player),
                    st == null ? 0 : st.boost, m.getSpeed(), m.maxUpStep(),
                    path == null ? "none" : (path.isDone() ? "done" : (path.getNextNodeIndex() + "/" + path.getNodeCount() + (path.canReach() ? "" : "!"))),
                    nav.isDone() ? " navDone" : "");
            this.trace.addLast(t);
            if (this.trace.size() > 30) this.trace.removeFirst();
            if (this.tick % 5 == 0) {
                String extra = historyExtra();
                if (extra != null) this.history.add(String.format("t=%d pos=(%.2f,%.2f,%.2f) player=(%.2f,%.2f,%.2f) dist=%.2f ", this.tick,
                        m.getX() - this.o.getX(), m.getY() - this.o.getY(), m.getZ() - this.o.getZ(),
                        this.player.getX() - this.o.getX(), this.player.getY() - this.o.getY(), this.player.getZ() - this.o.getZ(), m.distanceTo(this.player)) + extra);
            }
            if (this.firstFallTick < 0 && m.getY() < this.o.getY() - 1.5) { this.firstFallTick = this.tick; this.fallTrace = String.join("\n", this.trace); }
        }
        int firstFallTick = -1;
        String fallTrace;
        /** Whole-run trace sampled every 5 ticks (only for scenarios that override historyExtra), printed on failure. */
        final java.util.List<String> history = new java.util.ArrayList<>();

        String historyExtra() { return null; }

        String rel(net.minecraft.core.BlockPos p) { return p == null ? "-" : (p.getX() - this.o.getX()) + "," + (p.getY() - this.o.getY()) + "," + (p.getZ() - this.o.getZ()); }

        String describe(net.minecraft.world.entity.ai.behavior.PositionTracker t) {
            if (t == null) return "-";
            if (t instanceof net.minecraft.world.entity.ai.behavior.EntityTracker e) return e.getEntity() == this.player ? "PLAYER" : e.getEntity().getType().toShortString();
            return rel(t.currentBlockPosition());
        }

        String brainInfo(Mob m) {
            var b = m.getBrain();
            var walk = b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            var look = b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET);
            var inter = b.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.INTERACTION_TARGET);
            var path = m.getNavigation().getPath();
            return String.format("act=%s walk=%s look=%s inter=%s path=%s spd=%.3f",
                    b.getActiveNonCoreActivity().map(a -> a.getName()).orElse("-"),
                    walk.map(w -> describe(w.getTarget()) + "@" + w.getSpeedModifier()).orElse("-"),
                    look.map(this::describe).orElse("-"),
                    inter.map(e -> e == this.player ? "PLAYER" : e.getType().toShortString()).orElse("-"),
                    path == null ? "none" : (path.isDone() ? "done" : path.getNextNodeIndex() + "/" + path.getNodeCount() + "->" + rel(path.getTarget()) + (path.canReach() ? "" : "!")),
                    m.getSpeed());
        }

        Scenario(String name, int duration) {
            this.name = name;
            this.duration = duration;
        }

        void start(MinecraftServer server, ServerLevel level, BlockPos origin) {
            this.level = level;
            this.o = origin;
            for (int cx = (origin.getX() >> 4) - 3; cx <= (origin.getX() >> 4) + 8; cx++) {
                for (int cz = -4; cz <= 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            build();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "sc" + index), false);
            this.player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, this.player, cookie);
            this.player.setGameMode(GameType.SURVIVAL);
            Vec3 start = playerStart();
            this.startPos = start;
            moveTo(start.x, start.y, start.z);
            spawn();
        }

        void step() {
            script();
            traceTick();
            if (this.cursor != null) {
                this.player.connection.teleport(this.cursor.x, this.cursor.y, this.cursor.z, this.player.getYRot(), this.player.getXRot());
            }
            try {
                this.player.doTick();
            } catch (Exception ignored) {
            }
            this.tick++;
        }

        void moveTo(double x, double y, double z) {
            this.cursor = new Vec3(x, y, z);
            this.player.connection.teleport(x, y, z, this.player.getYRot(), this.player.getXRot());
        }

        /** Walk the player toward a point at a given speed (blocks per tick). Returns true once there. */
        boolean walk(double x, double y, double z, double speed) {
            // Scripted route position, not the player's physics position (the fake player still runs gravity and collisions).
            Vec3 p = this.cursor;
            Vec3 d = new Vec3(x - p.x, y - p.y, z - p.z);
            double len = d.length();
            if (len <= speed) {
                moveTo(x, y, z);
                return true;
            }
            Vec3 n = p.add(d.scale(speed / len));
            this.player.setYRot((float) (Math.atan2(-d.x, d.z) * 180.0 / Math.PI));
            moveTo(n.x, n.y, n.z);
            return false;
        }

        BlockPos at(int x, int y, int z) {
            return this.o.offset(x, y, z);
        }

        void box(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        this.level.setBlock(at(x, y, z), block.defaultBlockState(), 2);
                    }
                }
            }
        }

        <T extends Mob> T mob(EntityType<T> type, double x, double y, double z) {
            T mob = type.create(this.level);
            mob.moveTo(this.o.getX() + x, this.o.getY() + y, this.o.getZ() + z, 0, 0);
            mob.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
            mob.setPersistenceRequired();
            this.level.addFreshEntity(mob);
            return mob;
        }

        Vec3 rel(double x, double y, double z) {
            return new Vec3(this.o.getX() + x, this.o.getY() + y, this.o.getZ() + z);
        }

        void cleanup() {
            this.player.getServer().getPlayerList().remove(this.player);
            for (var e : this.level.getEntities((net.minecraft.world.entity.Entity) null, new AABB(this.o).inflate(120), e -> !(e instanceof ServerPlayer))) {
                e.discard();
            }
        }

        abstract void build();

        abstract Vec3 playerStart();

        abstract void spawn();

        abstract void script();

        abstract String verdict();
    }

    static String health(Mob m) {
        return String.format("hp=%.1f/%.1f alive=%s", m.getHealth(), m.getMaxHealth(), m.isAlive());
    }

    // ------------------------------------------------------------------ scenarios

    /** Walk 40 blocks, then sprint 30, then stop. The leashed mob should keep up without the lead snapping. */
    static class LeadWalk extends Scenario {
        final EntityType<? extends Mob> type;
        Mob mob;
        double maxDist;

        LeadWalk(String name, EntityType<? extends Mob> type) {
            super(name, 400);
            this.type = type;
        }

        void build() { box(-5, -1, -3, 110, -1, 3, Blocks.STONE); }
        Vec3 playerStart() { return rel(5.5, 0, 0.5); }
        void spawn() { this.mob = mob(this.type, 2.5, 0, 0.5); this.mob.setLeashedTo(this.player, true); this.traced = this.mob; }

        void script() {
            if (this.tick == 120 || this.tick == 250) LOG.info("[SCTRACE] {} mid-walk\n{}", this.name, String.join("\n", this.trace));
            if (this.tick < 190) walk(this.o.getX() + 46, this.o.getY(), this.o.getZ() + 0.5, 0.216);
            else if (this.tick < 300) walk(this.o.getX() + 77, this.o.getY(), this.o.getZ() + 0.5, 0.28);
            if (this.mob.isLeashed()) {
                float d = this.mob.distanceTo(this.player);
                this.maxDist = Math.max(this.maxDist, d);
                this.sumDist += d;
                if (d > 6.0F) this.yankTicks++;
            }
        }

        double sumDist;
        int yankTicks;

        String verdict() {
            boolean ok = this.mob.isLeashed() && this.yankTicks < 20;
            return (ok ? "PASS" : "FAIL") + String.format(" leashed=%s avgDist=%.1f maxDist=%.1f yankedTicks=%d", this.mob.isLeashed(), this.sumDist / Math.max(1, this.tick), this.maxDist, this.yankTicks);
        }
    }

    /** Fast travel across ice (like a boat): the lead should stretch and drag, not snap. */
    static class LeadIce extends Scenario {
        Mob mob;
        double maxDist;

        LeadIce() { super("lead_ice_fast", 200); }

        void build() { box(-5, -1, -3, 110, -1, 3, Blocks.PACKED_ICE); }
        Vec3 playerStart() { return rel(5.5, 0, 0.5); }
        void spawn() { this.mob = mob(EntityType.COW, 2.5, 0, 0.5); this.mob.setLeashedTo(this.player, true); }

        void script() {
            if (this.tick < 110) walk(this.o.getX() + 80, this.o.getY(), this.o.getZ() + 0.5, 0.65);
            if (this.mob.isLeashed()) this.maxDist = Math.max(this.maxDist, this.mob.distanceTo(this.player));
        }

        String verdict() {
            boolean ok = this.mob.isLeashed();
            return (ok ? "PASS" : "FAIL") + String.format(" leashed=%s endDist=%.1f maxDist=%.1f %s", this.mob.isLeashed(), this.mob.distanceTo(this.player), this.maxDist, health(this.mob));
        }
    }

    /**
     * A plateau with a 10-block cliff and a staircase down its side. Descend: the mob must not get pulled off the cliff.
     * Ascend (reversed): the mob should walk up and stay leashed.
     */
    static class LeadCliff extends Scenario {
        final boolean up;
        Mob mob;
        int leg;

        LeadCliff(boolean up) {
            super(up ? "lead_cliff_up" : "lead_cliff_down", 520);
            this.up = up;
        }

        void build() {
            box(0, 9, 0, 10, 9, 10, Blocks.STONE);              // plateau, standing height y=10
            for (int i = 0; i < 10; i++) box(11, 8 - i, i, 12, 8 - i, i, Blocks.STONE); // stairs along z, down to y=-1
            box(11, -1, 9, 13, -1, 40, Blocks.STONE);            // ground at the bottom, standing height y=0
        }

        Vec3 playerStart() { return this.up ? rel(12.0, 0, 30.5) : rel(4.5, 10, 2.5); }

        void spawn() {
            this.mob = this.up ? mob(EntityType.COW, 12.0, 0, 34.5) : mob(EntityType.COW, 1.5, 10, 9.5);
            this.mob.setLeashedTo(this.player, true);
            this.traced = this.mob;
        }

        void script() {
            double x = this.o.getX() + 12.0, z0 = this.o.getZ(), y0 = this.o.getY();
            if (!this.up) {
                if (this.leg == 0 && walk(x, y0 + 10, z0 + 0.5, 0.22)) this.leg = 1;
                else if (this.leg >= 1 && this.leg <= 10) { if (walk(x, y0 + 10 - this.leg, z0 + this.leg - 0.5 + 1, 0.22)) this.leg++; }
                else if (this.leg == 11 && walk(x, y0, z0 + 30.5, 0.22)) this.leg = 12;
            } else {
                if (this.leg == 0 && walk(x, y0, z0 + 9.5, 0.2)) this.leg = 1;
                else if (this.leg >= 1 && this.leg <= 9) { if (walk(x, y0 + this.leg, z0 + 9.5 - this.leg, 0.12)) this.leg++; }
                else if (this.leg == 10 && walk(this.o.getX() + 4.5, y0 + 10, z0 + 2.5, 0.15)) this.leg = 11;
            }
        }

        String historyExtra() {
            Mob m = this.mob;
            var st = m.getExistingDataOrNull(dev.chill.commonsense.CommonSense.STATE);
            var path = m.getNavigation().getPath();
            double px = this.player.getX() - m.getX(), pz = this.player.getZ() - m.getZ();
            Vec3 foot = dev.chill.commonsense.LeashLogic.nextFootstep(this.player.getData(dev.chill.commonsense.CommonSense.TRAIL), m);
            return String.format("leashed=%s leg=%d ground=%s hcol=%s vel=(%.3f,%.3f,%.3f) unsafeTowardPlayer=%s foot=%s crumbs=%d path=%s repath=%d stuck=%d",
                    m.isLeashed(), this.leg, m.onGround(), m.horizontalCollision, m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z,
                    dev.chill.commonsense.LeashLogic.unsafeDropAhead(m, px, pz),
                    foot == null ? "-" : String.format("(%.1f,%.1f,%.1f)", foot.x - this.o.getX(), foot.y - this.o.getY(), foot.z - this.o.getZ()),
                    this.player.getData(dev.chill.commonsense.CommonSense.TRAIL).crumbs.size(),
                    path == null ? "none" : (path.isDone() ? "done" : path.getNextNodeIndex() + "/" + path.getNodeCount() + "->" + rel(path.getTarget()) + (path.canReach() ? "" : "!") + " next=" + rel(path.getNextNodePos())),
                    st == null ? -1 : this.level.getGameTime() - st.lastRepathTick, st == null ? -1 : st.stuckTicks);
        }

        String verdict() {
            boolean ok = this.mob.isAlive() && this.mob.getHealth() >= this.mob.getMaxHealth() && this.mob.isLeashed() && this.mob.distanceTo(this.player) < 5;
            return (ok ? "PASS" : "FAIL") + String.format(" leg=%d leashed=%s dist=%.1f %s mobY=%.1f", this.leg, this.mob.isLeashed(), this.mob.distanceTo(this.player), health(this.mob), this.mob.getY() - this.o.getY());
        }
    }

    /** A narrow walkway along lava, with jostling. A golem should not end up in the lava. */
    static class GolemLava extends Scenario {
        IronGolem golem;
        boolean burned;

        GolemLava() { super("golem_lava_walkway", 400); }

        void build() {
            box(-2, -2, -1, 30, -2, 6, Blocks.STONE);
            box(-2, -1, -1, 30, -1, 6, Blocks.STONE);
            box(4, -1, 0, 26, -1, 1, Blocks.LAVA);           // lava channel alongside the walkway
            box(-2, 0, 4, 30, 2, 4, Blocks.STONE);           // wall: walkway is z=2..3 next to the lava
            box(-2, 0, 0, 3, 2, 1, Blocks.STONE);
            box(27, 0, 0, 30, 2, 1, Blocks.STONE);
        }

        Vec3 playerStart() { return rel(-1.5, 0, 2.5); }
        void spawn() { this.golem = mob(EntityType.IRON_GOLEM, 0.5, 0, 2.6); this.golem.setPersistenceRequired(); }

        void script() {
            if (this.tick % 20 == 0) this.golem.getNavigation().moveTo(this.o.getX() + 29.5, this.o.getY(), this.o.getZ() + 2.6, 0.8);
            if (this.tick % 15 == 7) this.golem.push(0, 0, -0.12);  // jostle toward the lava
            this.burned |= this.golem.isOnFire() || this.golem.isInLava();
        }

        String verdict() {
            return (!this.burned && this.golem.isAlive() ? "PASS" : "FAIL") + String.format(" burned=%s x=%.1f %s", this.burned, this.golem.getX() - this.o.getX(), health(this.golem));
        }
    }

    /** Owner and pet on a high ledge; the pet gets knocked off. Vanilla teleports it back up mid-fall with its fall distance kept. */
    static class PetMidFall extends Scenario {
        Wolf wolf;

        PetMidFall() { super("pet_knocked_off_ledge", 200); }

        void build() {
            box(6, 19, -2, 14, 19, 2, Blocks.STONE);          // high ledge, standing height y=20
            box(-30, -41, -10, 40, -41, 10, Blocks.STONE);     // ground far below (60 blocks down)
        }

        Vec3 playerStart() { return rel(11.5, 20, 0.5); }

        void spawn() {
            this.wolf = mob(EntityType.WOLF, 7.5, 20, 0.5);
            this.wolf.tame(this.player);
            this.wolf.setHealth(this.wolf.getMaxHealth());
        }

        void script() {
            if (this.tick == 20) this.wolf.setDeltaMovement(-0.6, 0.2, 0);   // knocked off the edge
        }

        String verdict() {
            boolean ok = this.wolf.isAlive() && this.wolf.getHealth() >= this.wolf.getMaxHealth();
            return (ok ? "PASS" : "FAIL") + String.format(" wolfY=%.1f ", this.wolf.getY() - this.o.getY()) + health(this.wolf);
        }
    }

    /** Owner walks onto land, then swims out into deep water. The pet is trapped underwater far away and must be rescued. */
    static class PetDrowning extends Scenario {
        Wolf wolf;
        int leg;

        PetDrowning() { super("pet_drowning_owner_swimming", 900); }

        void build() {
            box(-2, -1, -3, 11, -1, 3, Blocks.STONE);         // land
            box(12, -10, -6, 26, -10, 6, Blocks.STONE);       // pool floor
            box(12, -9, -6, 26, -1, 6, Blocks.WATER);         // deep pool, surface at y=-1
            box(40, -6, -2, 44, -1, 2, Blocks.GLASS);         // sealed glass cell...
            box(41, -5, -1, 43, -2, 1, Blocks.WATER);         // ...full of water
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
        }

        void script() {
            double y = this.o.getY();
            if (this.leg == 0 && walk(this.o.getX() + 10.5, y, this.o.getZ() + 0.5, 0.2)) this.leg = 1;
            else if (this.leg == 1 && walk(this.o.getX() + 19.5, y - 1.4, this.o.getZ() + 0.5, 0.12)) this.leg = 2;
            else if (this.leg == 2 && this.wolf == null) {
                // The pet only gets trapped once the owner is already out swimming.
                this.wolf = mob(EntityType.WOLF, 42.5, -5, 0.5);
                this.wolf.tame(this.player);
                this.wolf.setHealth(this.wolf.getMaxHealth());
                this.wolf.setAirSupply(0);
            }
        }

        String verdict() {
            if (this.wolf == null) return "FAIL owner never reached the water";
            boolean ok = this.wolf.isAlive() && !this.wolf.isUnderWater();
            return (ok ? "PASS" : "FAIL") + String.format(" underwater=%s ownerInWater=%s ", this.wolf.isUnderWater(), this.player.isInWater()) + health(this.wolf);
        }
    }
    // ------------------------------------------------------------------ villages

    /** A small village (bed + bell) on a platform, a stone bridge east, and a sealed cave pocket deep inside a stone block 80 blocks away. */
    abstract static class VillageScene extends Scenario {
        BlockPos bedHead;
        BlockPos bell;

        VillageScene(String name, int duration) { super(name, duration); }

        void buildVillage() {
            box(0, -1, 0, 19, -1, 19, Blocks.STONE);
            box(20, -1, 8, 110, -1, 11, Blocks.STONE);         // bridge east
            BlockPos foot = at(5, 0, 6);
            this.bedHead = at(5, 0, 5);
            var dir = net.minecraft.world.level.block.BedBlock.FACING;
            var part = net.minecraft.world.level.block.BedBlock.PART;
            this.level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(dir, net.minecraft.core.Direction.NORTH).setValue(part, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
            this.level.setBlock(this.bedHead, Blocks.RED_BED.defaultBlockState().setValue(dir, net.minecraft.core.Direction.NORTH).setValue(part, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
            this.bell = at(12, 0, 12);
            this.level.setBlock(this.bell, Blocks.BELL.defaultBlockState(), 2);
            this.level.setBlock(at(14, 0, 4), Blocks.COMPOSTER.defaultBlockState(), 2);
            box(78, -30, -2, 92, -5, 12, Blocks.STONE);       // big rock with a cave pocket inside, thick roof
            box(82, -26, 2, 86, -24, 6, Blocks.AIR);
        }

        void giveBed(net.minecraft.world.entity.npc.Villager v) {
            var poi = this.level.getPoiManager();
            poi.take(t -> t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), (t, p) -> p.equals(this.bedHead), this.bedHead, 1);
            v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME, net.minecraft.core.GlobalPos.of(this.level.dimension(), this.bedHead));
            v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT, net.minecraft.core.GlobalPos.of(this.level.dimension(), this.bell));
        }

        Vec3 cave() { return rel(84.5, -26, 4.5); }

        double distToBell(Mob m) { return Math.sqrt(m.blockPosition().distSqr(this.bell)); }
    }

    /** hasBed=false is a freshly cured / bedless villager. stolen=true: a player leads it away on a lead and leaves it in the cave. */
    static class VillagerCave extends VillageScene {
        final boolean hasBed, stolen;
        boolean lure;
        net.minecraft.world.entity.npc.Villager v;
        int leg;

        VillagerCave(String name, boolean hasBed, boolean stolen) {
            super(name, 1900);
            this.hasBed = hasBed;
            this.stolen = stolen;
        }

        /** A player 20 blocks away (past the escort radius) holding an emerald block, like a follow/lure mod. */
        VillagerCave(String name, boolean hasBed, boolean stolen, boolean lure) {
            this(name, hasBed, stolen);
            this.lure = lure;
        }

        void build() {
            buildVillage();
            if (!this.hasBed) {   // a cured villager with no bed to claim
                this.level.removeBlock(this.bedHead, false);
                this.level.removeBlock(this.bedHead.south(), false);
            }
        }
        Vec3 playerStart() { return this.lure ? rel(84.5, -26, 24.5) : this.stolen ? rel(8.5, 0, 9.5) : rel(-60.5, 0, 9.5); }

        void spawn() {
            this.v = mob(EntityType.VILLAGER, 8.5, 0, 9.5);
            if (this.hasBed) giveBed(this.v);
            if (this.stolen) this.v.setLeashedTo(this.player, true);
            if (this.lure) this.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD_BLOCK));
            this.traced = this.v;
        }

        void script() {
            if (!this.stolen) {
                if (this.tick == 60) { Vec3 c = cave(); this.v.teleportTo(c.x, c.y, c.z); }   // wandered off into a cave
                return;
            }
            if (this.leg == 0 && walk(this.o.getX() + 84.5, this.o.getY(), this.o.getZ() + 9.5, 0.18)) this.leg = 1;
            else if (this.leg == 1) {                              // shoved into the cave and the lead removed
                this.v.dropLeash(true, false);
                Vec3 c = cave();
                this.v.teleportTo(c.x, c.y, c.z);
                this.leg = 2;
            } else if (this.leg == 2 && walk(this.o.getX() + 50.5, this.o.getY(), this.o.getZ() + 9.5, 0.2)) this.leg = 3;
        }

        String verdict() {
            boolean home = distToBell(this.v) < 14;
            boolean wantHome = this.hasBed && !this.stolen && !this.lure;
            boolean ok = this.v.isAlive() && home == wantHome;
            return (ok ? "PASS" : "FAIL") + String.format(" wentHome=%s expected=%s distToBell=%.1f inCave=%s ", home, wantHome, distToBell(this.v),
                    this.v.blockPosition().distSqr(BlockPos.containing(cave())) < 25) + health(this.v);
        }
    }

    /** A golem that belongs to the village, dropped 90 blocks east on the bridge. It should walk back. */
    static class GolemHoming extends VillageScene {
        IronGolem g;

        GolemHoming(String name) { super(name, 1700); }

        void build() { buildVillage(); }
        Vec3 playerStart() { return rel(-60.5, 0, 9.5); }

        void spawn() {
            this.g = mob(EntityType.IRON_GOLEM, 9.5, 0, 9.5);
            this.traced = this.g;
            // Vanilla only counts a place as a village while villagers have claimed its beds/job blocks.
            giveBed(mob(EntityType.VILLAGER, 6.5, 0, 9.5));
        }

        void script() {
            if (this.tick == 120) this.g.teleportTo(this.o.getX() + 105.5, this.o.getY(), this.o.getZ() + 9.5);
            if (this.tick % 150 == 0) {
                var mem = this.g.getExistingDataOrNull(dev.chill.commonsense.CommonSense.VILLAGE);
                var path = this.g.getNavigation().getPath();
                LOG.info("[SCTRACE] {} t={} x={} anchor={} lastHoming={} path={} pathTarget={} target={} inVillage={}", this.name, this.tick,
                        String.format("%.1f", this.g.getX() - this.o.getX()), mem == null ? null : mem.anchor, mem == null ? -1 : mem.lastHomingTick,
                        path == null ? "none" : (path.isDone() ? "done" : path.getNextNodeIndex() + "/" + path.getNodeCount()),
                        path == null ? null : path.getTarget().offset(-this.o.getX(), -this.o.getY(), -this.o.getZ()), this.g.getTarget(), this.level.isVillage(this.g.blockPosition()));
            }
        }

        String verdict() {
            double d = distToBell(this.g);
            return (d < 48 ? "PASS" : "FAIL") + String.format(" distToVillage=%.1f ", d) + health(this.g);
        }
    }
    /** A player walks across a field; with an emerald block in hand the villager should follow, and stop once it's put away. */
    static class VillagerLure extends Scenario {
        final boolean lure, putAway;
        net.minecraft.world.entity.npc.Villager v;

        VillagerLure(String name, boolean lure, boolean putAway) {
            super(name, 600);
            this.lure = lure;
            this.putAway = putAway;
        }

        void build() { box(-5, -1, -4, 110, -1, 4, Blocks.GRASS_BLOCK); }
        Vec3 playerStart() { return rel(8.5, 0, 0.5); }

        /** Where the villager was when the lure was put away (proves it followed), and whether the lure ever pulled it again. */
        double xAtPutAway = Double.NaN, distAtPutAway = Double.NaN;
        boolean lureAgain;

        void spawn() {
            this.v = mob(EntityType.VILLAGER, 3.5, 0, 0.5);
            if (this.lure) this.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD_BLOCK));
            this.traced = this.v;
        }

        void script() {
            if (this.tick < 300) {
                walk(this.o.getX() + 45.5, this.o.getY(), this.o.getZ() + 0.5, 0.15);
            } else {
                if (this.tick == 300 && this.putAway) {
                    this.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    this.xAtPutAway = this.v.getX() - this.o.getX();
                    this.distAtPutAway = this.v.distanceTo(this.player);
                }
                // Walk on well past vanilla's 8-block look range: a vanilla villager that idly strolls near a player walks up to
                // them, which isn't this mod's lure and shouldn't decide the test.
                if (this.putAway) walk(this.o.getX() + 100.5, this.o.getY(), this.o.getZ() + 0.5, 0.216);
                if (this.putAway && this.tick > 306) {   // tickLure notices the put-away within 5 ticks
                    var mem = this.v.getExistingDataOrNull(dev.chill.commonsense.CommonSense.VILLAGE);
                    var walkTarget = this.v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
                    this.lureAgain |= (mem != null && mem.lured) || walkTarget.filter(w -> w.getSpeedModifier() >= 0.69F
                            && w.getTarget() instanceof net.minecraft.world.entity.ai.behavior.EntityTracker e && e.getEntity() == this.player).isPresent();
                }
            }
        }

        String historyExtra() {
            var mem = this.v.getExistingDataOrNull(dev.chill.commonsense.CommonSense.VILLAGE);
            return brainInfo(this.v) + String.format(" lured=%s holder=%s cooldown=%d",
                    mem != null && mem.lured, dev.chill.commonsense.VillageLogic.lureHolder(this.v) != null,
                    mem == null ? -1 : mem.lureCooldownUntil - this.level.getGameTime());
        }

        String verdict() {
            double vx = this.v.getX() - this.o.getX();
            double d = this.v.distanceTo(this.player);
            boolean ok = !this.lure ? vx < 20 : this.putAway ? (this.xAtPutAway > 30 && this.distAtPutAway < 6 && !this.lureAgain && d > 9) : d < 6;
            return (ok ? "PASS" : "FAIL") + String.format(" villagerX=%.1f distToPlayer=%.1f", vx, d)
                    + (this.putAway ? String.format(" atPutAway: x=%.1f dist=%.1f lureAgain=%s", this.xAtPutAway, this.distAtPutAway, this.lureAgain) : "");
        }
    }
    /** A villager asleep in a bed: is it still drawn from 15, 30 and 60 blocks away? (Awake it would be, out to ~67.) */
    static class SleepingRender extends Scenario {
        net.minecraft.world.entity.npc.Villager v;
        String result15, result30, result60;

        SleepingRender() { super("sleeping_villager_render_distance", 60); }

        void build() {
            box(-2, -1, -2, 6, -1, 6, Blocks.STONE);
            var dir = net.minecraft.world.level.block.BedBlock.FACING;
            var part = net.minecraft.world.level.block.BedBlock.PART;
            this.level.setBlock(at(2, 0, 3), Blocks.RED_BED.defaultBlockState().setValue(dir, net.minecraft.core.Direction.NORTH).setValue(part, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
            this.level.setBlock(at(2, 0, 2), Blocks.RED_BED.defaultBlockState().setValue(dir, net.minecraft.core.Direction.NORTH).setValue(part, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
        }

        Vec3 playerStart() { return rel(-40.5, 0, 0.5); }
        void spawn() { this.v = mob(EntityType.VILLAGER, 2.5, 0, 2.5); }

        String measured = "not measured";
        boolean ok;

        void script() {
            if (this.tick == 1) this.level.setDayTime(18000);   // midnight, so the villager's AI keeps it in bed
            if (this.tick == 5) { this.v.setNoAi(true); this.v.startSleeping(at(2, 0, 2)); }   // no AI: it stays in bed so the render check sees a sleeper
            if (this.tick == 8) {
                boolean asleep = this.v.isSleeping();
                boolean d15 = this.v.shouldRenderAtSqrDistance(15 * 15), d30 = this.v.shouldRenderAtSqrDistance(30 * 30), d60 = this.v.shouldRenderAtSqrDistance(60 * 60);
                this.ok = asleep && d15 && d30 && d60;
                this.measured = String.format("asleep=%s hitbox=%.2f drawnAt15=%s drawnAt30=%s drawnAt60=%s", asleep, this.v.getBoundingBox().getSize(), d15, d30, d60);
            }
        }

        String verdict() {
            return (this.ok ? "PASS " : "FAIL ") + this.measured;
        }
    }

    /**
     * Herd instinct: the player hits one mob of a group; the others should run (passive) or go after the player (hostile/neutral).
     * enabled=false is the control with the feature off. pets=true: the group (except the one hit) is tamed by the player and
     * must never turn on its owner.
     */
    /** Hit one cow: the herd bolts the same way and stays bunched, then backs away from the player and ignores their wheat. */
    static class HerdWary extends Scenario {
        final java.util.List<Mob> herd = new java.util.ArrayList<>();
        Vec3 startCenter;
        String measured = "not measured";
        boolean boltOk, keepOk, temptOk;

        HerdWary(String name) { super(name, 300); }

        void build() { box(-34, -1, -34, 34, -1, 34, Blocks.GRASS_BLOCK); }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                this.herd.add(mob(EntityType.COW, 4.5 + Math.cos(a) * 1.5, 0, 0.5 + Math.sin(a) * 1.5));
            }
            this.startCenter = center();
        }

        Vec3 center() {
            Vec3 c = Vec3.ZERO;
            for (Mob m : this.herd) c = c.add(m.position());
            return c.scale(1.0 / this.herd.size());
        }

        double spread() {
            Vec3 c = center();
            double max = 0;
            for (Mob m : this.herd) max = Math.max(max, Math.sqrt(m.position().distanceToSqr(c.x, m.getY(), c.z)));
            return max;
        }

        void script() {
            if (this.tick == 5) this.herd.get(0).hurt(this.level.damageSources().playerAttack(this.player), 1.0F);
            if (this.tick == 100) {
                double moved = center().subtract(this.startCenter).horizontalDistance();
                double spread = spread();
                this.boltOk = moved >= 8 && spread <= 6;
                this.measured = String.format("bolt: moved=%.1f spread=%.1f", moved, spread);
            }
            if (this.tick == 140) {
                Vec3 c = center();
                Vec3 dir = c.subtract(this.player.position()).multiply(1, 0, 1).normalize();
                Vec3 p = c.subtract(dir.scale(4));
                moveTo(p.x, this.o.getY(), p.z);   // walk right up to the herd
            }
            if (this.tick == 200) {
                int kept = 0;
                for (Mob m : this.herd) if (m.distanceTo(this.player) >= 6) kept++;
                this.keepOk = kept >= this.herd.size() - 1;
                this.measured += String.format(" | keepDistance=%d/%d", kept, this.herd.size());
                this.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT));
            }
            if (this.tick == 280) {
                int came = 0;
                for (Mob m : this.herd) if (m.distanceTo(this.player) < 4) came++;
                this.temptOk = came == 0;
                this.measured += String.format(" | cameForWheat=%d", came);
            }
        }

        String historyExtra() {
            StringBuilder b = new StringBuilder();
            for (Mob m : this.herd) {
                String goals = ((dev.chill.commonsense.mixin.MobGoalsAccessor) m).commonsense$goalSelector().getAvailableGoals().stream()
                        .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning).map(w -> w.getGoal().getClass().getSimpleName()).collect(java.util.stream.Collectors.joining(","));
                b.append(String.format("[d=%.1f %s] ", m.distanceTo(this.player), goals));
            }
            return b.toString();
        }

        String verdict() { return (this.boltOk && this.keepOk && this.temptOk ? "PASS " : "FAIL ") + this.measured; }
    }

    /** Wheat at 11.5 blocks (vanilla range 10, now 12); with straggler, a second cow 4 blocks further back follows the first. */
    static class TemptReach extends Scenario {
        final boolean bonus;
        boolean straggler, followOff;
        Mob lead, back;
        Vec3 leadStart, backStart;
        int wasBonus;
        boolean wasFollow;
        String measured = "not measured";
        boolean ok;

        TemptReach(String name, boolean bonus, boolean straggler) {
            super(name, 120);
            this.bonus = bonus;
            this.straggler = straggler;
        }

        void build() { box(-4, -1, -4, 24, -1, 4, Blocks.GRASS_BLOCK); }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.wasBonus = dev.chill.commonsense.Config.TEMPT_RANGE_BONUS.get();
            this.wasFollow = dev.chill.commonsense.Config.HERD_FOLLOW.get();
            dev.chill.commonsense.Config.TEMPT_RANGE_BONUS.set(this.bonus ? 2 : 0);
            dev.chill.commonsense.Config.HERD_FOLLOW.set(!this.followOff);
            this.lead = mob(EntityType.COW, this.straggler ? 11.5 : 12.0, 0, 0.5);
            this.leadStart = this.lead.position();
            if (this.straggler) {
                this.back = mob(EntityType.COW, 15.0, 0, 0.5);   // 3.5 blocks behind, 2 past the 12-block tempt range
                this.backStart = this.back.position();
            }
            this.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT));
        }

        void script() {
            // single cow: judged early so a random stroll into vanilla range can't decide the control
            if (this.tick == (this.straggler ? 100 : 50)) {
                double leadGain = this.leadStart.distanceTo(this.player.position()) - this.lead.distanceTo(this.player);
                if (!this.straggler) {
                    this.measured = String.format("leadCameCloser=%.1f", leadGain);
                    this.ok = this.bonus ? leadGain >= 4 : leadGain < 3;
                } else {
                    double backGain = this.backStart.distanceTo(this.player.position()) - this.back.distanceTo(this.player);
                    this.measured = String.format("leadCameCloser=%.1f stragglerCameCloser=%.1f", leadGain, backGain);
                    this.ok = leadGain >= 4 && (this.followOff ? backGain < 3 : backGain >= 4);
                }
            }
        }

        String historyExtra() {
            Mob m = this.back != null ? this.back : this.lead;
            var st = m.getExistingDataOrNull(dev.chill.commonsense.CommonSense.STATE);
            var leadSt = this.lead.getExistingDataOrNull(dev.chill.commonsense.CommonSense.STATE);
            String goals = ((dev.chill.commonsense.mixin.MobGoalsAccessor) m).commonsense$goalSelector().getAvailableGoals().stream()
                    .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning).map(w -> w.getGoal().getClass().getSimpleName()).collect(java.util.stream.Collectors.joining(","));
            return String.format("x=%.1f leadX=%.1f goals=[%s] navDone=%s temptedAgo=%d leadTemptedAgo=%d follow=%s", m.getX() - this.o.getX(), this.lead.getX() - this.o.getX(),
                    goals, m.getNavigation().isDone(), st == null ? -1 : this.level.getGameTime() - st.temptedTick,
                    leadSt == null ? -1 : this.level.getGameTime() - leadSt.temptedTick, dev.chill.commonsense.Config.HERD_FOLLOW.get());
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.TEMPT_RANGE_BONUS.set(this.wasBonus);
            dev.chill.commonsense.Config.HERD_FOLLOW.set(this.wasFollow);
            super.cleanup();
        }
    }

    /** Your hit on your own wolf does nothing, your wolf biting your other wolf does nothing, a sneaking hit lands. */
    static class FriendlyFire extends Scenario {
        net.minecraft.world.entity.animal.Wolf a, b;
        String measured = "not measured";
        boolean ok;

        FriendlyFire(String name) { super(name, 20); }

        void build() { box(-4, -1, -4, 8, -1, 4, Blocks.STONE); }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.a = mob(EntityType.WOLF, 2.5, 0, 0.5);
            this.b = mob(EntityType.WOLF, 2.5, 0, 2.5);
            this.a.tame(this.player);
            this.b.tame(this.player);
        }

        void script() {
            if (this.tick == 5) {
                float full = this.a.getHealth();
                this.a.hurt(this.level.damageSources().playerAttack(this.player), 4.0F);
                float afterOwn = this.a.getHealth();
                this.b.hurt(this.level.damageSources().mobAttack(this.a), 4.0F);
                float afterPet = this.b.getHealth();
                this.player.setShiftKeyDown(true);
                this.player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                this.a.hurt(this.level.damageSources().playerAttack(this.player), 4.0F);
                float afterSneak = this.a.getHealth();
                this.measured = String.format("full=%.0f ownHit=%.0f petBite=%.0f sneakHit=%.0f", full, afterOwn, afterPet, afterSneak);
                this.ok = afterOwn == full && afterPet == this.b.getMaxHealth() && afterSneak < full;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }
    }

    /** Get off a saddled horse, then shove it 7 blocks away: it walks back to where you left it. */
    static class GroundTie extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.horse.Horse horse;
        net.minecraft.core.BlockPos tiePos;
        String measured = "not measured";
        boolean ok;

        GroundTie(String name, boolean on) { super(name, 280); this.on = on; }

        void build() { box(-12, -1, -12, 20, -1, 12, Blocks.GRASS_BLOCK); }

        Vec3 playerStart() { return rel(0.5, 0, -6.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.GROUND_TIE.get();
            dev.chill.commonsense.Config.GROUND_TIE.set(this.on);
            this.horse = mob(EntityType.HORSE, 2.5, 0, 0.5);
            this.horse.setTamed(true);
            this.horse.equipSaddle(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE), null);
        }

        void script() {
            if (this.tick == 5) {
                this.player.startRiding(this.horse, true);
                this.player.stopRiding();
                this.tiePos = this.horse.blockPosition();
                moveTo(this.o.getX() + 0.5, this.o.getY(), this.o.getZ() - 6.5);
            }
            if (this.tick == 40) this.horse.teleportTo(this.horse.getX() + 7, this.horse.getY(), this.horse.getZ());
            if (this.tick == 270) {
                double d = Math.sqrt(this.horse.blockPosition().distSqr(this.tiePos));
                var t = dev.chill.commonsense.Manners.tie(this.horse);
                this.measured = String.format("fromTie=%.1f tie=%s", d, t == null ? "none" : rel(t));
                this.ok = this.on ? t != null && d <= 2.5 : t == null && d >= 4;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.GROUND_TIE.set(this.was);
            super.cleanup();
        }
    }

    /** A creeper two blocks from your standing wolf starts to hiss: the wolf gets clear before the blast. */
    static class DodgeCreeper extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.Wolf wolf;
        net.minecraft.world.entity.monster.Creeper creeper;
        String measured = "not measured";
        boolean ok;

        DodgeCreeper(String name, boolean on) { super(name, 40); this.on = on; }

        void build() { box(-12, -1, -12, 16, -1, 12, Blocks.STONE); }

        Vec3 playerStart() { return rel(0.5, 0, 5.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.DODGE_CREEPERS.get();
            dev.chill.commonsense.Config.DODGE_CREEPERS.set(this.on);
            this.wolf = mob(EntityType.WOLF, 3.5, 0, 0.5);
            this.wolf.tame(this.player);
            this.creeper = mob(EntityType.CREEPER, 5.5, 0, 0.5);
            this.creeper.setNoAi(true);
        }

        void script() {
            if (this.tick == 5) this.creeper.ignite();
            if (this.tick == 28) {
                double d = this.wolf.distanceTo(this.creeper);
                this.measured = String.format("wolfToCreeper=%.1f", d);
                this.ok = this.on ? d >= 5 : d < 4;
                this.creeper.discard();   // before it blows
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.DODGE_CREEPERS.set(this.was);
            super.cleanup();
        }
    }

    /** A cow drops three blocks onto farmland: the farmland stays. */
    static class Trample extends Scenario {
        final boolean on;
        boolean was;
        String measured = "not measured";
        boolean ok;

        Trample(String name, boolean on) { super(name, 40); this.on = on; }

        void build() {
            box(-4, -1, -4, 8, -1, 4, Blocks.DIRT);
            box(3, -1, 0, 3, -1, 0, Blocks.FARMLAND);
            box(3, -1, 2, 3, -1, 2, Blocks.WATER);
        }

        Vec3 playerStart() { return rel(0.5, 0, -3.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.NO_TRAMPLING.get();
            dev.chill.commonsense.Config.NO_TRAMPLING.set(this.on);
            mob(EntityType.COW, 3.5, 3.0, 0.5);
        }

        void script() {
            if (this.tick == 35) {
                boolean farmland = this.level.getBlockState(at(3, -1, 0)).is(Blocks.FARMLAND);
                this.measured = "farmland=" + farmland;
                this.ok = farmland == this.on;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.NO_TRAMPLING.set(this.was);
            super.cleanup();
        }
    }

    /** You hit a villager by accident: your wolf doesn't go for it (control: it does). */
    static class Restraint extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.Wolf wolf;
        net.minecraft.world.entity.npc.Villager villager;
        String measured = "not measured";
        boolean ok;

        Restraint(String name, boolean on) { super(name, 40); this.on = on; }

        void build() { box(-8, -1, -8, 8, -1, 8, Blocks.STONE); }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.PET_RESTRAINT.get();
            dev.chill.commonsense.Config.PET_RESTRAINT.set(this.on);
            this.wolf = mob(EntityType.WOLF, -2.5, 0, 0.5);
            this.wolf.tame(this.player);
            this.villager = mob(EntityType.VILLAGER, 2.5, 0, 0.5);
            this.villager.setNoAi(true);
        }

        void script() {
            if (this.tick == 5) {
                this.villager.hurt(this.level.damageSources().playerAttack(this.player), 0.5F);
                this.player.setLastHurtMob(this.villager);
            }
            if (this.tick == 35) {
                boolean went = this.wolf.getTarget() == this.villager;
                this.measured = "wolfTargetsVillager=" + went;
                this.ok = went != this.on;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.PET_RESTRAINT.set(this.was);
            super.cleanup();
        }
    }

    /**
     * Load test, run as an A/B pair: 160 cows, 40 wolves following you, 30 villagers and 20 sheep on a lead-free pen while
     * you walk laps. Reports the server's mean tick time over the last 10 s, with the mod on or off.
     */
    static class Perf extends Scenario {
        final boolean on;
        boolean was;
        long sumNanos;
        int samples;
        String measured = "not measured";

        Perf(String name, boolean on) { super(name, 500); this.on = on; }

        void build() {
            box(-24, -1, -24, 24, -1, 24, Blocks.GRASS_BLOCK);
            box(-25, 0, -25, 25, 1, -25, Blocks.OAK_FENCE);
            box(-25, 0, 25, 25, 1, 25, Blocks.OAK_FENCE);
            box(-25, 0, -25, -25, 1, 25, Blocks.OAK_FENCE);
            box(25, 0, -25, 25, 1, 25, Blocks.OAK_FENCE);
            // a real field: tall grass everywhere and a few berry bushes, so the hazard checks have blocks to look at
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < 400; i++) this.level.setBlock(at(r.nextInt(48) - 24, 0, r.nextInt(48) - 24), Blocks.SHORT_GRASS.defaultBlockState(), 2);
            for (int i = 0; i < 25; i++) this.level.setBlock(at(r.nextInt(48) - 24, 0, r.nextInt(48) - 24),
                    Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3), 2);
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.ENABLED.get();
            dev.chill.commonsense.Config.ENABLED.set(this.on);
            java.util.Random r = new java.util.Random(42);
            for (int i = 0; i < 160; i++) mob(EntityType.COW, r.nextInt(44) - 22 + 0.5, 0, r.nextInt(44) - 22 + 0.5);
            for (int i = 0; i < 20; i++) mob(EntityType.SHEEP, r.nextInt(44) - 22 + 0.5, 0, r.nextInt(44) - 22 + 0.5);
            for (int i = 0; i < 30; i++) mob(EntityType.VILLAGER, r.nextInt(44) - 22 + 0.5, 0, r.nextInt(44) - 22 + 0.5);
            for (int i = 0; i < 40; i++) {
                net.minecraft.world.entity.animal.Wolf w = mob(EntityType.WOLF, r.nextInt(10) - 5 + 0.5, 0, r.nextInt(10) - 5 + 0.5);
                w.tame(this.player);
            }
        }

        void script() {
            // walk a square lap, 16 blocks a side, so followers and herds keep working
            int phase = (this.tick / 80) % 4;
            double t = (this.tick % 80) / 80.0;
            double x = phase == 0 ? -8 + 16 * t : phase == 1 ? 8 : phase == 2 ? 8 - 16 * t : -8;
            double z = phase == 0 ? -8 : phase == 1 ? -8 + 16 * t : phase == 2 ? 8 : 8 - 16 * t;
            moveTo(this.o.getX() + x + 0.5, this.o.getY(), this.o.getZ() + z + 0.5);
            if (this.tick == 300) {
                dev.chill.commonsense.Courtesy.profNanos = 0;
                dev.chill.commonsense.Courtesy.profile = true;
            }
            if (this.tick > 300) {
                long[] times = this.level.getServer().getTickTimesNanos();
                this.sumNanos += times[(this.level.getServer().getTickCount() - 1) % times.length];
                this.samples++;
            }
            if (this.tick == 490) {
                dev.chill.commonsense.Courtesy.profile = false;
                this.measured = String.format("meanTickMs=%.2f over %d ticks, mobs=%d courtesyUsPerTick=%.1f", this.sumNanos / 1.0e6 / Math.max(1, this.samples),
                        this.samples, this.level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(this.o).inflate(30)).size(),
                        dev.chill.commonsense.Courtesy.profNanos / 1.0e3 / Math.max(1, this.samples));
            }
        }

        String verdict() { return "INFO " + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.ENABLED.set(this.was);
            super.cleanup();
        }
    }

    /** A villager plans a walk across a row of wither roses: it goes around them (vanilla walks straight through). */
    static class VillagerHazard extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.npc.Villager villager;
        String measured = "not measured";
        boolean ok;

        VillagerHazard(String name, boolean on) { super(name, 50); this.on = on; }

        void build() {
            box(-10, -1, -10, 10, -1, 10, Blocks.GRASS_BLOCK);
            box(0, 0, -5, 0, 0, 5, Blocks.WITHER_ROSE);
        }

        Vec3 playerStart() { return rel(-8.5, 0, -8.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HAZARD_AVOIDANCE.get();
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.on);
            this.villager = mob(EntityType.VILLAGER, -3.5, 0, 0.5);
            this.villager.setNoAi(true);
        }

        void script() {
            if (this.tick == 45) {
                this.villager.setOnGround(true);   // no-AI mobs never settle, and ground navigation won't plan from mid-air
                net.minecraft.world.level.pathfinder.Path path = this.villager.getNavigation().createPath(at(3, 0, 0), 0);
                int roses = 0;
                for (int i = 0; path != null && i < path.getNodeCount(); i++) {
                    if (this.level.getBlockState(path.getNodePos(i)).is(Blocks.WITHER_ROSE)) roses++;
                }
                this.measured = "path=" + (path == null ? "none" : path.getNodeCount() + " nodes, reach=" + path.canReach()) + " roseNodes=" + roses;
                this.ok = path != null && path.canReach() && (this.on ? roses == 0 : roses > 0);
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HAZARD_AVOIDANCE.set(this.was);
            super.cleanup();
        }
    }

    /** Your wolf is set on a zombie sealed in a glass box: it gives up after about 5 s; with the option off it keeps at it. */
    static class GiveUp extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.Wolf wolf;
        net.minecraft.world.entity.monster.Zombie zombie;
        String measured = "not measured";
        boolean ok;

        GiveUp(String name, boolean on) { super(name, 180); this.on = on; }

        void build() {
            box(-8, -1, -8, 10, -1, 8, Blocks.STONE);
            box(4, 0, -2, 8, 3, 2, Blocks.GLASS);
            box(5, 0, -1, 7, 2, 1, Blocks.AIR);
        }

        Vec3 playerStart() { return rel(-4.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.GIVE_UP_UNREACHABLE.get();
            dev.chill.commonsense.Config.GIVE_UP_UNREACHABLE.set(this.on);
            this.wolf = mob(EntityType.WOLF, -1.5, 0, 0.5);
            this.wolf.tame(this.player);
            this.zombie = mob(EntityType.ZOMBIE, 6.5, 0, 0.5);
            this.zombie.setNoAi(true);
        }

        void script() {
            if (this.tick == 5) this.wolf.setTarget(this.zombie);
            if (this.tick == 170) {
                boolean still = this.wolf.getTarget() == this.zombie;
                this.measured = "wolfStillTargets=" + still;
                this.ok = still != this.on;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.GIVE_UP_UNREACHABLE.set(this.was);
            super.cleanup();
        }
    }

    /** Your tamed wolf, squeezed against you, bumps you every tick: with the option on only the wolf moves. */
    static class Shove extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.Wolf wolf;
        String measured = "not measured";
        boolean ok;

        Shove(String name, boolean on) { super(name, 30); this.on = on; }

        void build() {
            box(-4, -1, -4, 4, -1, 4, Blocks.STONE);
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.NO_PET_SHOVE.get();
            dev.chill.commonsense.Config.NO_PET_SHOVE.set(this.on);
            this.wolf = mob(EntityType.WOLF, 0.8, 0, 0.5);
            this.wolf.tame(this.player);
            this.wolf.setNoAi(true);
        }

        void script() {
            if (this.tick == 10) {
                this.player.setDeltaMovement(Vec3.ZERO);
                this.wolf.setDeltaMovement(Vec3.ZERO);
                this.wolf.push(this.player);    // the wolf's own push pass
                this.player.push(this.wolf);    // yours
                double you = this.player.getDeltaMovement().horizontalDistance();
                double pet = this.wolf.getDeltaMovement().horizontalDistance();
                this.measured = String.format("youNudged=%.3f petNudged=%.3f", you, pet);
                this.ok = pet > 0.001 && (this.on ? you < 1.0E-6 : you > 0.001);
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.NO_PET_SHOVE.set(this.was);
            super.cleanup();
        }
    }

    /** You shoot at a cow with your own wolf in the way: the arrow flies through the wolf and hits the cow. */
    static class ShootThrough extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.animal.Wolf wolf;
        net.minecraft.world.entity.animal.Cow cow;
        String measured = "not measured";
        boolean ok;

        ShootThrough(String name, boolean on) { super(name, 60); this.on = on; }

        void build() {
            box(-6, -1, -3, 8, -1, 3, Blocks.STONE);
        }

        Vec3 playerStart() { return rel(-4.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.NO_FRIENDLY_FIRE.get();
            dev.chill.commonsense.Config.NO_FRIENDLY_FIRE.set(this.on);
            this.wolf = mob(EntityType.WOLF, 0.5, 0, 0.5);
            this.wolf.tame(this.player);
            this.wolf.setNoAi(true);
            this.cow = mob(EntityType.COW, 4.5, 0, 0.5);
            this.cow.setNoAi(true);
        }

        void script() {
            if (this.tick == 10) {
                net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(this.level, this.player,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
                arrow.setPos(rel(-2.0, 0.45, 0.5));
                arrow.setNoGravity(true);
                arrow.shoot(1, 0, 0, 1.5F, 0.0F);
                this.level.addFreshEntity(arrow);
            }
            if (this.tick == 50) {
                boolean cowHit = this.cow.getHealth() < this.cow.getMaxHealth();
                this.measured = "cowHit=" + cowHit + " wolfHealth=" + this.wolf.getHealth();
                this.ok = this.on ? cowHit && this.wolf.getHealth() >= this.wolf.getMaxHealth() : !cowHit;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.NO_FRIENDLY_FIRE.set(this.was);
            super.cleanup();
        }
    }

    /** You hide in a sealed stone hut; a husk 20 blocks off that was after you comes over and hangs around instead of wandering off. */
    static class Lurk extends Scenario {
        final boolean on;
        boolean was;
        net.minecraft.world.entity.monster.Husk husk;
        String measured = "not measured";
        boolean ok;

        Lurk(String name, boolean on) { super(name, 320); this.on = on; }

        void build() {
            box(-6, -1, -6, 26, -1, 6, Blocks.STONE);
            box(-2, 0, -2, 2, 3, 2, Blocks.STONE);
            box(-1, 0, -1, 1, 2, 1, Blocks.AIR);
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HOSTILE_WITS.get();
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.on);
            this.husk = mob(EntityType.HUSK, 20.5, 0, 0.5);
        }

        void script() {
            if (this.tick < 40) this.husk.setTarget(this.player);   // it saw you run inside
            if (this.tick == 310) {
                double d = this.husk.position().distanceTo(rel(0.5, 0, 0.5));
                this.measured = String.format("huskDist=%.1f target=%s", d, this.husk.getTarget() == this.player);
                this.ok = this.on ? d <= 7.0 : true;   // control: vanilla wanders off; reported only
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.was);
            super.cleanup();
        }
    }

    /**
     * You're inside a roofed stone hut: a pillager that can't open the door waits on your doorstep, or, when the hut has an
     * open window instead of a door, comes round to where it can see you through it.
     */
    static class DoorWatch extends Scenario {
        final boolean on;
        final boolean window;
        final EntityType<? extends Mob> type;
        boolean was;
        boolean everSaw;
        Mob pillager;
        String measured = "not measured";
        boolean ok;

        DoorWatch(String name, EntityType<? extends Mob> type, boolean on, boolean window) {
            super(name, 400);
            this.type = type;
            this.on = on;
            this.window = window;
        }

        void build() {
            box(-12, -1, -12, 12, -1, 12, Blocks.STONE);
            box(-3, 0, -3, 3, 3, 3, Blocks.STONE);
            box(-2, 0, -2, 2, 2, 2, Blocks.AIR);
            if (this.window) {
                box(0, 1, 3, 0, 1, 3, Blocks.AIR);   // a one-block hole at eye height on the south wall
            } else {
                var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.WEST);
                this.level.setBlock(at(-3, 0, 0), lower, 2);
                this.level.setBlock(at(-3, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            }
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HOSTILE_WITS.get();
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.on);
            this.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 1000, 4));
            this.pillager = mob(this.type, 9.5, 0, -9.5);
            if (this.type == EntityType.SKELETON) {   // noon test: a helmet keeps it from burning (burning mobs skip the wait)
                this.pillager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
            }
            if (this.type == EntityType.PILLAGER) {
                this.pillager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);   // watch, don't shoot
            }
        }

        void script() {
            if (this.tick < 40) this.pillager.setTarget(this.player);
            if (this.tick % 40 == 0) hostileTrace(this.name, this.tick, this.pillager, this.o);
            if (this.tick > 60 && this.pillager.hasLineOfSight(this.player)) this.everSaw = true;
            if (this.tick == 390) {
                double door = this.pillager.position().distanceTo(rel(-3.5, 0, 0.5));
                boolean sees = this.pillager.hasLineOfSight(this.player);
                this.measured = String.format("doorDist=%.1f seesYou=%s everSaw=%s at=%s", door, sees, this.everSaw,
                        this.pillager.blockPosition().subtract(this.o).toShortString());
                double hut = this.pillager.position().distanceTo(rel(0.5, 0, 0.5));
                this.measured += String.format(" hutDist=%.1f", hut);
                if (this.type == EntityType.CREEPER) {
                    this.ok = !this.on || (door >= 4.0 && hut >= 6.0 && hut <= 20.0);   // dim: hangs about, but well back from the door
                } else {
                    this.ok = !this.on || (this.window ? this.everSaw : door <= 2.5);
                }
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.was);
            super.cleanup();
        }
    }

    static void hostileTrace(String name, int tick, Mob mob, net.minecraft.core.BlockPos o) {
        StringBuilder running = new StringBuilder();
        for (var wrapped : ((dev.chill.commonsense.mixin.MobGoalsAccessor) mob).commonsense$goalSelector().getAvailableGoals()) {
            if (wrapped.isRunning()) running.append(wrapped.getGoal().getClass().getSimpleName()).append(',');
            if (wrapped.isRunning() && wrapped.getGoal() instanceof dev.chill.commonsense.HostileWits.AmbushGoal ambush && ambush.perch() != null) {
                running.append("perch=").append(ambush.perch().subtract(o).toShortString()).append(',');
            }
            if (wrapped.getGoal() instanceof dev.chill.commonsense.HostileWits.WaitOutGoal wait) {
                running.append(wrapped.isRunning() ? wait.debug() : "waitExit=" + wait.exitWhy()).append(',');
            }
        }
        LOG.info("[SCTRACE] {} t={} at={} y={} target={} fire={} goals={}", name, tick,
                mob.blockPosition().subtract(o).toShortString(), String.format("%.1f", mob.getY() - o.getY()),
                mob.getTarget() != null, mob.isOnFire(), running);
    }

    /**
     * Night; you're shut in a stone hut with a door. A spider that was after you climbs up and waits on the roof edge above the
     * door, out of sight; when you step outside it drops on you.
     */
    static class Ambush extends Scenario {
        final boolean on;
        boolean was;
        long time;
        double perchY = -99;
        boolean tagged;
        double closest = 99;
        net.minecraft.world.entity.monster.Spider spider;
        String measured = "not measured";
        boolean ok;

        Ambush(String name, boolean on) { super(name, 400); this.on = on; }

        void build() {
            box(-12, -1, -12, 12, -1, 12, Blocks.STONE);
            box(-3, 0, -3, 3, 3, 3, Blocks.STONE);
            box(-2, 0, -2, 2, 2, 2, Blocks.AIR);
            var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.WEST);
            this.level.setBlock(at(-3, 0, 0), lower, 2);
            this.level.setBlock(at(-3, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.was = dev.chill.commonsense.Config.HOSTILE_WITS.get();
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.on);
            this.time = this.level.getDayTime();
            this.level.setDayTime(18000);   // spiders only keep after you in the dark
            this.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 1000, 4));
            this.spider = mob(EntityType.SPIDER, 9.5, 0, -9.5);
        }

        void script() {
            if (this.tick < 40) this.spider.setTarget(this.player);
            if (this.tick % 20 == 0) hostileTrace(this.name, this.tick, this.spider, this.o);
            if (this.tick > 60 && this.tick < 320) {
                this.perchY = Math.max(this.perchY, this.spider.getY() - this.o.getY());
                this.tagged |= this.spider.getTags().contains(dev.chill.commonsense.HostileWits.AMBUSH_TAG);
            }
            if (this.tick == 320) {
                this.measured = String.format("perchY=%.1f tagged=%s at=%s", this.perchY, this.tagged,
                        this.spider.blockPosition().subtract(this.o).toShortString());
                Vec3 out = rel(-5.5, 0, 0.5);
                moveTo(out.x, out.y, out.z);   // you step out of the door
            }
            if (this.tick > 320) {
                this.closest = Math.min(this.closest, this.spider.distanceTo(this.player));
            }
            if (this.tick == 390) {
                this.measured += String.format(" closest=%.1f", this.closest);
                this.ok = !this.on || (this.perchY >= 2.0 && this.tagged && this.closest <= 1.8);
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HOSTILE_WITS.set(this.was);
            this.level.setDayTime(this.time);
            super.cleanup();
        }
    }

    /** Step into the Nether: your following wolf and the cow on your lead come too; your sitting wolf stays. */
    static class Portal extends Scenario {
        net.minecraft.world.entity.animal.Wolf wolf, sitter;
        net.minecraft.world.entity.animal.Cow cow;
        ServerLevel nether;
        net.minecraft.core.BlockPos np;
        String measured = "not measured";
        boolean ok;

        Portal(String name) { super(name, 40); }

        void build() { box(-6, -1, -6, 6, -1, 6, Blocks.STONE); }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.wolf = mob(EntityType.WOLF, 2.5, 0, 0.5);
            this.wolf.tame(this.player);
            this.sitter = mob(EntityType.WOLF, -2.5, 0, 0.5);
            this.sitter.tame(this.player);
            this.sitter.setOrderedToSit(true);
            this.cow = mob(EntityType.COW, 0.5, 0, 2.5);
            this.cow.setLeashedTo(this.player, true);
        }

        void script() {
            if (this.tick == 5) {
                this.nether = this.level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                this.np = new net.minecraft.core.BlockPos(this.o.getX() / 8, 100, 0);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    this.nether.setBlock(this.np.offset(x, -1, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
                    for (int y = 0; y < 3; y++) this.nether.setBlock(this.np.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                this.cursor = null;
                this.player.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(this.nether, net.minecraft.world.phys.Vec3.atBottomCenterOf(this.np),
                        net.minecraft.world.phys.Vec3.ZERO, 0, 0, net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
            }
            if (this.tick == 30) {
                var box = new AABB(this.np).inflate(6);
                var wolves = this.nether.getEntitiesOfClass(net.minecraft.world.entity.animal.Wolf.class, box, w -> true);
                var cows = this.nether.getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, box, c -> true);
                boolean cowLeashed = !cows.isEmpty() && cows.get(0).getLeashHolder() == this.player;
                this.measured = "wolvesInNether=" + wolves.size() + " cowsInNether=" + cows.size() + " cowLeashed=" + cowLeashed + " sitterHome=" + this.sitter.isAlive();
                this.ok = wolves.size() == 1 && cows.size() == 1 && cowLeashed && this.sitter.isAlive() && this.sitter.level() == this.level;
            }
        }

        String verdict() { return (this.ok ? "PASS " : "FAIL ") + this.measured; }

        @Override
        void cleanup() {
            if (this.nether != null) {
                for (var e : this.nether.getEntities((net.minecraft.world.entity.Entity) null, new AABB(this.np).inflate(16), e -> !(e instanceof ServerPlayer))) e.discard();
            }
            super.cleanup();
        }
    }

    static class Herd extends Scenario {
        final EntityType<? extends Mob> type;
        final boolean enabled, pets;
        final java.util.List<Mob> herd = new java.util.ArrayList<>();
        final java.util.List<Vec3> starts = new java.util.ArrayList<>();
        boolean wasEnabled;
        String measured = "not measured";
        boolean ok;

        Herd(String name, EntityType<? extends Mob> type, boolean enabled, boolean pets) {
            super(name, 70);
            this.type = type;
            this.enabled = enabled;
            this.pets = pets;
        }

        void build() {
            box(-16, -1, -16, 16, -1, 16, Blocks.STONE);
        }

        Vec3 playerStart() { return rel(0.5, 0, 0.5); }

        void spawn() {
            this.wasEnabled = dev.chill.commonsense.Config.HERD_INSTINCT.get();
            dev.chill.commonsense.Config.HERD_INSTINCT.set(this.enabled);
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                Mob m = mob(this.type, 4.5 + Math.cos(a) * 1.5, 0, 0.5 + Math.sin(a) * 1.5);
                if (this.pets && i > 0 && m instanceof net.minecraft.world.entity.TamableAnimal t) {
                    t.tame(this.player);
                    t.setOrderedToSit(false);
                }
                this.herd.add(m);
                this.starts.add(m.position());
            }
        }

        void script() {
            if (this.tick == 1) this.level.setDayTime(6000);   // noon: daylight spiders leave players alone unless provoked
            if (this.tick == 5) {
                Mob victim = this.herd.get(0);
                victim.hurt(this.level.damageSources().playerAttack(this.player), 1.0F);
            }
            if (this.tick == 60) {
                int ran = 0, targeting = 0, angryAtOwner = 0, panicking = 0, hurtOthers = 0;
                for (int i = 1; i < this.herd.size(); i++) {
                    Mob m = this.herd.get(i);
                    if (m.position().distanceTo(this.starts.get(i)) > 2.5) ran++;
                    if (m.getTarget() == this.player) targeting++;
                    if (m instanceof net.minecraft.world.entity.NeutralMob n && this.player.getUUID().equals(n.getPersistentAngerTarget())) angryAtOwner++;
                    if (m.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.IS_PANICKING)
                            || m.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY)) panicking++;
                    if (m.getHealth() < m.getMaxHealth()) hurtOthers++;
                }
                int others = this.herd.size() - 1;
                this.measured = String.format("others=%d ran=%d targetingPlayer=%d angryAtPlayer=%d brainPanic=%d tookDamage=%d", others, ran, targeting, angryAtOwner, panicking, hurtOthers);
                boolean noDamage = hurtOthers == 0;
                if (this.pets) {
                    this.ok = targeting == 0 && angryAtOwner == 0 && noDamage;
                } else if (!this.enabled) {
                    // Off: nobody targets the player, and grazing animals don't bolt (spiders wander on their own, so only targeting counts for them).
                    this.ok = targeting == 0 && noDamage && (this.type != EntityType.COW || ran <= 1);
                } else if (this.type == EntityType.COW) {
                    this.ok = ran >= others - 1 && noDamage;
                } else if (this.type == EntityType.GOAT) {
                    this.ok = (ran >= others - 1 || panicking >= others - 1) && noDamage;
                } else if (this.type == EntityType.SPIDER) {
                    this.ok = targeting == 0 && noDamage;
                } else if (this.type == EntityType.LLAMA) {
                    int defenders = 0;
                    for (int i = 1; i < this.herd.size(); i++) if (this.herd.get(i).getLastHurtByMob() == this.player) defenders++;
                    this.measured += " defenders=" + defenders;
                    this.ok = defenders == 1;   // spit may hit a herd mate (vanilla), so damage doesn't count here
                } else {
                    this.ok = targeting >= 1 && noDamage;   // wolves: the nearest turns on you, and vanilla calls its pack
                }
            }
        }

        String verdict() {
            return (this.ok ? "PASS " : "FAIL ") + this.measured;
        }

        @Override
        void cleanup() {
            dev.chill.commonsense.Config.HERD_INSTINCT.set(this.wasEnabled);
            super.cleanup();
        }
    }
}
