package com.tanrunn.tcth.api.guncombat;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * A confirmed real-player firearm kill performed on the server.
 *
 * <p>This is the unified firearm-kill event that the gunner profession, firearm
 * statistics and the ability tree must all reuse. It is posted by
 * {@code GunKillEventDispatcher} through the NeoForge game bus.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code eventId} is constant for one and the same kill behaviour and
 *       unique across different kills; consumers keep idempotency keyed by
 *       it;</li>
 *   <li>the event represents <strong>one confirmed kill</strong>, not the
 *       number of shots fired, hits landed, or damage events;</li>
 *   <li>{@code weapon} is a defensive copy of the firearm item stack at the
 *       moment of firing — {@link #getWeapon()} returns a fresh copy on every
 *       call, so callers can never mutate the original;</li>
 *   <li>{@code targetUuid} is the stable UUID of the killed entity (every
 *       Minecraft entity exposes one) and is required non-null — the event is
 *       only built after the target has been identified;</li>
 *   <li>{@code automated} describes the source (FakePlayer, turret or
 *       no-player kills are flagged {@code true}) and does <em>not</em> by
 *       itself decide whether rewards are granted;</li>
 *   <li>the event carries no reward / experience / profession state — those
 *       live in consumers.</li>
 * </ul>
 *
 * <p>This public API references no optional mod classes (no Scorched Guns,
 * Jobs+, Arc or GD656 types).
 */
public final class GunKillEvent extends Event {

    private final UUID eventId;
    private final ServerPlayer player;
    private final ResourceLocation weaponId;
    private final ItemStack weapon;
    private final ResourceLocation targetId;
    private final UUID targetUuid;
    private final GunTargetTier targetTier;
    private final float distance;
    private final boolean automated;
    private final ServerLevel level;
    private final BlockPos position;

    /**
     * @param eventId        id of this kill; must not be null
     * @param player         the acting player; must not be null
     * @param weaponId       item id of the firearm; must not be null
     * @param weapon         the firearm item stack at firing time; must not be
     *                       null (defensively copied)
     * @param targetId       entity type id of the killed target; must not be
     *                       null
     * @param targetUuid     stable UUID of the killed entity; must not be null
     * @param targetTier     difficulty tier of the target; must not be null
     * @param distance       distance from shooter to target at the time of the
     *                       killing blow (blocks); must be &ge; 0
     * @param automated      whether the kill came from an automated / fake-player
     *                       / no-player source
     * @param level          the server level; must not be null and must be a
     *                       server-side level
     * @param position       the position of the killed entity; must not be null
     *                       (stored immutable)
     */
    public GunKillEvent(UUID eventId, ServerPlayer player, ResourceLocation weaponId,
                        ItemStack weapon, ResourceLocation targetId, UUID targetUuid,
                        GunTargetTier targetTier, float distance, boolean automated,
                        ServerLevel level, BlockPos position) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.player = Objects.requireNonNull(player, "player");
        this.weaponId = Objects.requireNonNull(weaponId, "weaponId");
        this.weapon = Objects.requireNonNull(weapon, "weapon").copy();
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        this.targetTier = Objects.requireNonNull(targetTier, "targetTier");
        if (!Float.isFinite(distance) || distance < 0.0f) {
            throw new IllegalArgumentException(
                    "distance must be finite and >= 0 (got " + distance + ")");
        }
        this.distance = distance;
        this.automated = automated;
        this.level = Objects.requireNonNull(level, "level");
        this.position = Objects.requireNonNull(position, "position").immutable();
    }

    /** Stable id of this kill; identical across the same kill behaviour. */
    public UUID getEventId() {
        return eventId;
    }

    /** The acting player. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** Item id of the firearm used (e.g. {@code scguns:defender_pistol}). */
    public ResourceLocation getWeaponId() {
        return weaponId;
    }

    /**
     * Defensive copy of the firearm item stack at firing time.
     *
     * <p>Every call returns a fresh copy so callers can never mutate the
     * original.
     */
    public ItemStack getWeapon() {
        return weapon.copy();
    }

    /** Entity type id of the killed target (e.g. {@code minecraft:zombie}). */
    public ResourceLocation getTargetId() {
        return targetId;
    }

    /**
     * Stable UUID of the killed entity (every Minecraft entity exposes one).
     */
    public UUID getTargetUuid() {
        return targetUuid;
    }

    /** Difficulty tier of the killed target. */
    public GunTargetTier getTargetTier() {
        return targetTier;
    }

    /** Distance from shooter to target at the time of the killing blow (blocks). */
    public float getDistance() {
        return distance;
    }

    /** Whether the source was automated (fake player, turret, no player). */
    public boolean isAutomated() {
        return automated;
    }

    /** The server level the kill happened in. */
    public ServerLevel getLevel() {
        return level;
    }

    /** Position of the killed entity (immutable). */
    public BlockPos getPosition() {
        return position;
    }
}
