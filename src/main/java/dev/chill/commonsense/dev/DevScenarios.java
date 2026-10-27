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
import java.util.Deque;
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
                    this.ok = targeting <= 1 && noDamage;
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
