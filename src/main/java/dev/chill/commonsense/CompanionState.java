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

    /** Herd instinct: who this animal is wary of and until when, and where it bolts to first (with its herd). */
    public java.util.UUID waryOf;
    public long waryUntil;
    public Vec3 fleeTarget;
    public long fleeUntil;
    /** Last tick a player was tempting this animal directly; herd mates follow only animals tempted this way. */
    public long temptedTick = -1000;
}
