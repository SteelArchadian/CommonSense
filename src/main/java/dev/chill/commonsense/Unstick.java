package dev.chill.commonsense;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stuck recovery for walkers: a mob that is following a path but hasn't moved for a couple of seconds (a carpet or snow layer
 * at a doorway that its jump logic keeps tripping on, an odd block) is nudged onto the next path step when that spot is free,
 * else made to re-path, else given up on. Checked every 10 ticks and only for mobs that are walking, so it costs nothing at rest.
 */
public final class Unstick {

    private static final int STUCK_TICKS = 40;

    private static final class Watch {
        Vec3 last = Vec3.ZERO;
        int still;
        int tries;
        String note = "-";
    }

    /** Dev traces. */
    public static String debug(Mob mob) {
        Watch w = WATCHES.get(mob);
        return w == null ? "nowatch" : w.note + " still=" + w.still + " tries=" + w.tries;
    }

    private static final Map<Mob, Watch> WATCHES = new WeakHashMap<>();

    private Unstick() {
    }

    static void tick(Mob mob) {
        PathNavigation nav = mob.getNavigation();
        if (nav.isDone() || nav.getPath() == null || !mob.onGround() || mob.isPassenger() || mob.isLeashed() || !Courtesy.careful(mob)) {
            WATCHES.computeIfAbsent(mob, m -> new Watch()).note = "skip done=" + nav.isDone() + " path=" + (nav.getPath() != null) + " ground=" + mob.onGround() + " careful=" + Courtesy.careful(mob);
            if (false) {
                WATCHES.remove(mob);
            }
            return;
        }
        Watch w = WATCHES.computeIfAbsent(mob, m -> new Watch());
        Vec3 now = mob.position();
        if (now.distanceToSqr(w.last) > 0.01) {
            w.last = now;
            w.still = 0;
            w.tries = 0;
            return;
        }
        w.still += 10;
        if (w.still < STUCK_TICKS) {
            return;
        }
        w.still = 0;
        Path path = nav.getPath();
        if (w.tries++ >= 3) {
            nav.stop();   // really stuck: let the mob's own goals or brain pick something else
            w.tries = 0;
            return;
        }
        if (path.isDone()) {
            return;
        }
        Node next = path.getNextNode();
        // a carpet or snow layer lifts a villager just enough that its head no longer fits under the doorway's lintel (and back down on the
        // far side): stand it in the next cell at the lowest height where it fits
        for (int k = 0; k <= 10; k++) {
            Vec3 to = new Vec3(next.x + 0.5, next.y + k * 0.0625, next.z + 0.5);
            if (to.distanceToSqr(now) < 2.5 * 2.5 && mob.level().noCollision(mob, mob.getBoundingBox().move(to.subtract(now)))) {
                mob.setPos(to.x, to.y, to.z);
                path.advance();
                return;
            }
        }
        nav.recomputePath();
    }
}
