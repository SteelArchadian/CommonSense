package dev.chill.commonsense;

import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/** Per-mob runtime bookkeeping. */
public class CompanionState {

    public boolean malusApplied;
    public final Map<PathType, Float> savedMalus = new EnumMap<>(PathType.class);

    public long lastPullTick = -1000;
    public long lastTeleportTick = -1000;
    public long lastRescueTick = -1000;
    public long pulledDuringFallSince = -1;

    public double boost;
    public float lastLeaderDist;
    public boolean stepAssist;

    public Vec3 lastSafePos;
    public Vec3 lastSpeedPos;
    public Vec3 stuckCheckPos = Vec3.ZERO;
    public int stuckTicks;
    public long lastRepathTick = -1000;
    /** The ledge-descent path being protected from replanning, and since when (gives up after a few seconds). */
    public net.minecraft.world.level.pathfinder.Path descentPath;
    public long descentSince;
}
