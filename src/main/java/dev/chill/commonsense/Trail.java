package dev.chill.commonsense;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/** A player's recent footsteps on safe ground, newest last. Companions use it to find a safe way down and safe places to land. */
public class Trail {

    private static final int MAX = 64;

    public final Deque<Vec3> crumbs = new ArrayDeque<>();
    public Vec3 lastPos;
    /** Horizontal speed over the last tick, measured from position (server-side player motion is not reliable). */
    public double speed;
    public boolean safe;

    public void add(Vec3 pos) {
        Vec3 last = this.crumbs.peekLast();
        if (last == null || last.distanceToSqr(pos) >= 1.44) {
            this.crumbs.addLast(pos);
            if (this.crumbs.size() > MAX) {
                this.crumbs.removeFirst();
            }
        }
    }

    public Vec3 latest() {
        return this.crumbs.peekLast();
    }
}
