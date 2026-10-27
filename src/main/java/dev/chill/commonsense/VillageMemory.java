package dev.chill.commonsense;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

import javax.annotation.Nullable;
import java.util.Optional;

/** Saved with the mob: the villager's bed (kept even when vanilla forgets it), and the last village a golem/guard/villager belonged to. */
public class VillageMemory {

    public static final Codec<VillageMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.optionalFieldOf("bed").forGetter(m -> Optional.ofNullable(m.bed)),
            Codec.BOOL.optionalFieldOf("bed_abandoned", false).forGetter(m -> m.bedAbandoned),
            GlobalPos.CODEC.optionalFieldOf("anchor").forGetter(m -> Optional.ofNullable(m.anchor))
    ).apply(i, (bed, abandoned, anchor) -> {
        VillageMemory m = new VillageMemory();
        m.bed = bed.orElse(null);
        m.bedAbandoned = abandoned;
        m.anchor = anchor.orElse(null);
        return m;
    }));

    /** The villager's bed. Only cleared when the bed is gone or the villager claims a different one. */
    @Nullable
    public GlobalPos bed;
    /** Set when a player leads the villager away from this bed: the old village no longer counts as home. */
    public boolean bedAbandoned;
    /** Last place this mob was inside a village. */
    @Nullable
    public GlobalPos anchor;

    // Runtime only.
    public long lastLedTick = -100000;
    public long lostSinceTick = -1;
    public long lastHomingTick = -1000;
    public boolean lured;
    /** After a lure is put away: who held it, and until when the villager is kept from drifting back to them. */
    @Nullable
    public java.util.UUID lurePlayer;
    public long lureCooldownUntil = -1;

    @Nullable
    public BlockPos bedPos() {
        return this.bed == null ? null : this.bed.pos();
    }
}
