package com.tanrunn.tcth.api.brewing;

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
 * Fired on {@code NeoForge.EVENT_BUS} (game bus) whenever a beverage is
 * prepared/finished, regardless of the producing device (phase 7B).
 *
 * <p>This is TCTH's unified beverage-completion event. It mirrors
 * {@link com.tanrunn.tcth.api.cooking.DishCookedEvent}: defensive copies,
 * unique event id, and <em>no</em> reward / experience / settled fields —
 * consumers keep their own idempotency state keyed by {@link #getEventId()}.
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this event's fields and
 * methods may change without notice until 1.0.0.
 */
public class BeveragePreparedEvent extends Event {

    private final UUID eventId;
    @Nullable
    private final ServerPlayer player;
    @Nullable
    private final ResourceLocation recipeId;
    private final ItemStack result;
    private final BeverageDevice device;
    private final BeverageTier tier;
    private final boolean automated;
    private final ServerLevel level;
    @Nullable
    private final BlockPos position;

    /**
     * @param eventId   unique id for this prepared beverage; must not be null
     * @param player    the acting player, or {@code null} for automated
     *                  production
     * @param recipeId  the recipe id, or {@code null} when the device does not
     *                  expose one (e.g. BAC Keg — no pouring recipe id)
     * @param result    the prepared beverage item; must not be null
     *                  (defensively copied)
     * @param device    the producing device; must not be null
     * @param tier      the beverage tier; must not be null
     * @param automated whether the beverage was prepared by an automated
     *                  device (no player interaction / FakePlayer)
     * @param level     the server level the beverage was prepared in; must
     *                  not be null
     * @param position  the block position of the device, or {@code null}
     * @throws NullPointerException if {@code eventId}, {@code result},
     *                              {@code device}, {@code tier} or
     *                              {@code level} is null
     */
    public BeveragePreparedEvent(UUID eventId, @Nullable ServerPlayer player, @Nullable ResourceLocation recipeId,
                                 ItemStack result, BeverageDevice device, BeverageTier tier, boolean automated,
                                 ServerLevel level, @Nullable BlockPos position) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.player = player;
        this.recipeId = recipeId;
        this.result = Objects.requireNonNull(result, "result").copy();
        this.device = Objects.requireNonNull(device, "device");
        this.tier = Objects.requireNonNull(tier, "tier");
        this.automated = automated;
        this.level = Objects.requireNonNull(level, "level");
        this.position = position;
    }

    /** Unique id for this beverage; stable across consumers. */
    public UUID getEventId() {
        return this.eventId;
    }

    /** Acting player, or {@code null} for automated production. */
    @Nullable
    public ServerPlayer getPlayer() {
        return this.player;
    }

    /** Recipe id, or {@code null} when the device does not expose one. */
    @Nullable
    public ResourceLocation getRecipeId() {
        return this.recipeId;
    }

    /** The prepared beverage item; a defensive copy on every access. */
    public ItemStack getResult() {
        return this.result.copy();
    }

    /** The producing device. */
    public BeverageDevice getDevice() {
        return this.device;
    }

    /** The beverage tier. */
    public BeverageTier getTier() {
        return this.tier;
    }

    /** True when prepared by an automated device or FakePlayer. */
    public boolean isAutomated() {
        return this.automated;
    }

    /** The server level the beverage was prepared in. */
    public ServerLevel getLevel() {
        return this.level;
    }

    /** Block position of the device, or {@code null}. */
    @Nullable
    public BlockPos getPosition() {
        return this.position;
    }
}
