package com.tanrunn.tcth.api.farming;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;

/**
 * A successful real-player crop harvest performed on the server.
 *
 * <p>This is the unified farming event that the farmer profession, farming
 * statistics, crop Field Guide and the ability tree must all reuse. It is
 * posted by {@code CropHarvestedEventDispatcher} through the NeoForge game
 * bus.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code eventId} is constant for one and the same harvest behaviour and
 *       unique across different harvests; consumers keep idempotency keyed by
 *       it;</li>
 *   <li>the event represents <strong>one successful harvest behaviour</strong>,
 *       not the number of dropped items — no {@code harvestedCount} is
 *       provided and settlement must not depend on drop counts;</li>
 *   <li>{@code automated} describes the source (mechanical/fake-player
 *       harvests are flagged {@code true}) and does <em>not</em> by itself
 *       decide whether rewards are granted;</li>
 *   <li>the event carries no reward / experience / profession state — those
 *       live in consumers.</li>
 * </ul>
 *
 * <p>This public API references no optional mod classes (no Arc, Jobs+,
 * Farmers Delight or Kaleidoscope Cookery types).
 */
public final class CropHarvestedEvent extends Event {

    private final UUID eventId;
    @Nullable
    private final ServerPlayer player;
    private final ResourceLocation cropId;
    private final BlockState harvestedState;
    private final BlockPos position;
    private final ServerLevel level;
    private final HarvestMethod method;
    private final boolean fullyGrown;
    private final boolean automated;

    /**
     * @param eventId        id of this harvest; must not be null
     * @param player         the acting player, or {@code null} when there is no
     *                       player context (automated source)
     * @param cropId         block id of the harvested crop; must not be null
     * @param harvestedState the crop block state at harvest time; must not be
     *                       null
     * @param position       the crop position; must not be null (stored
     *                       immutable)
     * @param level          the server level; must not be null and must be a
     *                       server-side level
     * @param method         the harvest method; must not be null
     * @param fullyGrown     whether the crop was mature at harvest time
     * @param automated      whether the harvest came from an automated /
     *                       fake-player source
     */
    public CropHarvestedEvent(UUID eventId, @Nullable ServerPlayer player, ResourceLocation cropId,
                              BlockState harvestedState, BlockPos position, ServerLevel level,
                              HarvestMethod method, boolean fullyGrown, boolean automated) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.player = player;
        this.cropId = Objects.requireNonNull(cropId, "cropId");
        this.harvestedState = Objects.requireNonNull(harvestedState, "harvestedState");
        this.position = Objects.requireNonNull(position, "position").immutable();
        this.level = Objects.requireNonNull(level, "level");
        this.method = Objects.requireNonNull(method, "method");
        this.fullyGrown = fullyGrown;
        this.automated = automated;
    }

    /** Stable id of this harvest; identical across the same harvest behaviour. */
    public UUID getEventId() {
        return eventId;
    }

    /** The acting player, or {@code null} for automated/no-player sources. */
    @Nullable
    public ServerPlayer getPlayer() {
        return player;
    }

    /** Block id of the harvested crop (e.g. {@code minecraft:wheat}). */
    public ResourceLocation getCropId() {
        return cropId;
    }

    /** The crop block state at harvest time. */
    public BlockState getHarvestedState() {
        return harvestedState;
    }

    /** Position of the harvested crop (immutable). */
    public BlockPos getPosition() {
        return position;
    }

    /** The server level the harvest happened in. */
    public ServerLevel getLevel() {
        return level;
    }

    /** How the harvest was performed. */
    public HarvestMethod getMethod() {
        return method;
    }

    /** Whether the crop was mature at harvest time. */
    public boolean isFullyGrown() {
        return fullyGrown;
    }

    /** Whether the source was automated (mechanical or fake player). */
    public boolean isAutomated() {
        return automated;
    }
}
