package dev.chill.commonsense;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Pets that are following you (not told to sit) and mobs on your lead come with you through a portal, the way they'd follow
 * you through a door. They are moved one tick after you arrive, onto the spot you arrived at, and re-leashed.
 */
public final class PortalFollow {

    private record Departure(ResourceKey<Level> from, Vec3 pos) {
    }

    private record Pending(UUID player, ResourceKey<Level> from, Vec3 pos) {
    }

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, Departure> DEPARTURES = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    private PortalFollow() {
    }

    /** Last in line, so a trip another mod cancels is never recorded. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player && Config.on() && Config.PORTAL_FOLLOW.get()) {
            DEPARTURES.put(player.getUUID(), new Departure(player.level().dimension(), player.position()));
        }
    }

    @SubscribeEvent
    public static void onChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        Departure d = DEPARTURES.remove(event.getEntity().getUUID());   // a stale entry from a failed trip is dropped here too
        if (d != null && d.from().equals(event.getFrom())) {
            PENDING.add(new Pending(event.getEntity().getUUID(), d.from(), d.pos()));   // next tick, outside the player's own transfer
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> now = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : now) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(p.player());
            ServerLevel from = event.getServer().getLevel(p.from());
            if (player != null && from != null && player.level() != from) {
                bring(player, from, p.pos());
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DEPARTURES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        DEPARTURES.clear();
        PENDING.clear();
    }

    static boolean follows(Mob mob, ServerPlayer player) {
        if (mob.getType().is(CommonSense.EXCLUDED) || mob.isPassenger() || mob.isVehicle() || !mob.isAlive()) {
            return false;
        }
        if (mob.isLeashed()) {
            return mob.getLeashHolder() == player;
        }
        if (mob instanceof TamableAnimal pet) {
            return pet.isTame() && player.getUUID().equals(pet.getOwnerUUID()) && !pet.isOrderedToSit();
        }
        return false;
    }

    private static void bring(ServerPlayer player, ServerLevel from, Vec3 pos) {
        double r = Config.PORTAL_FOLLOW_RANGE.get();
        List<Mob> coming = from.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(r), m -> follows(m, player));
        ServerLevel to = player.serverLevel();
        for (Mob mob : coming) {
            boolean leashed = mob.isLeashed();
            if (leashed) {
                mob.dropLeash(true, false);
            }
            Entity moved;
            try {
                moved = mob.changeDimension(new DimensionTransition(to, player.position(), Vec3.ZERO, mob.getYRot(), mob.getXRot(),
                        DimensionTransition.DO_NOTHING));
            } catch (RuntimeException e) {
                // one modded mob that can't travel must not stop the rest, or take the server down
                LOG.error("Common Sense: couldn't bring {} through the portal", mob, e);
                moved = null;
            }
            if (moved == null && leashed && mob.isAlive()) {
                mob.setLeashedTo(player, true);   // stayed behind: give the lead back rather than dropping it
            }
            if (moved instanceof Mob arrived) {
                arrived.fallDistance = 0;
                Manners.setTie(arrived, null);   // a tie spot in the old dimension means nothing here
                if (leashed) {
                    arrived.setLeashedTo(player, true);
                }
            }
        }
    }
}
