package com.tanrunn.tcth.api.cooking;

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
 * Fired on {@code NeoForge.EVENT_BUS} (game bus) whenever a dish is cooked or
 * finished, regardless of the producing device.
 *
 * <p>This is TCTH's unified cooking-completion event. It is produced by the
 * framework's detectors and consumed by compat modules (professions, bounties,
 * orders, economy, …) and by server integration packs.
 *
 * <p>Design notes:
 * <ul>
 *   <li>{@link #getResult()} returns a defensive copy both at construction and
 *       on every access, so listeners cannot mutate each other's view of the
 *       dish.</li>
 *   <li>{@link #getEventId()} is unique per cooked dish and stays constant for
 *       the lifetime of the event; consumers use it for their own idempotency
 *       tracking.</li>
 *   <li>This event intentionally carries <em>no</em> global settlement state
 *       (no {@code settled}/{@code cancelled}/{@code rewarded}): every consumer
 *       keeps its own settlement bookkeeping. If cancellation is ever needed it
 *       will be added as a separate cancellable pre-event.</li>
 *   <li>{@code player}, {@code recipeId} and {@code position} may be
 *       {@code null} — e.g. automated production has no actor, some devices do
 *       not expose a recipe id, and some contexts have no block position.</li>
 * </ul>
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this event's fields and
 * methods may change without notice until 1.0.0. See the API stability
 * statement in {@code com.tanrunn.tcth.api}.
 */
public class DishCookedEvent extends Event {

    private final UUID eventId;
    @Nullable
    private final ServerPlayer player;
    @Nullable
    private final ResourceLocation recipeId;
    private final ItemStack result;
    private final CookingDevice device;
    private final DishQuality quality;
    private final boolean automated;
    private final ServerLevel level;
    @Nullable
    private final BlockPos position;

    /**
     * @param eventId   unique id for this cooked dish; must not be null
     * @param player    the acting player, or {@code null} for automated
     *                  production
     * @param recipeId  the recipe id that produced the dish, or {@code null}
     *                  when the producing device does not expose one
     * @param result    the cooked dish item; must not be null (defensively
     *                  copied)
     * @param device    the producing device; must not be null
     * @param quality   the dish quality; must not be null
     * @param automated whether the dish was produced by an automated device
     *                  (no player interaction)
     * @param level     the server level the dish was cooked in; must not be
     *                  null
     * @param position  the block position of the device, or {@code null}
     *                  when not applicable
     * @throws NullPointerException if {@code eventId}, {@code result},
     *                              {@code device}, {@code quality} or
     *                              {@code level} is null
     */
    public DishCookedEvent(UUID eventId, @Nullable ServerPlayer player, @Nullable ResourceLocation recipeId,
                           ItemStack result, CookingDevice device, DishQuality quality, boolean automated,
                           ServerLevel level, @Nullable BlockPos position) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.player = player;
        this.recipeId = recipeId;
        this.result = Objects.requireNonNull(result, "result").copy();
        this.device = Objects.requireNonNull(device, "device");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.automated = automated;
        this.level = Objects.requireNonNull(level, "level");
        this.position = position;
    }

    /**
     * @return the unique id of this cooked dish; constant for the lifetime of
     *         the event
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * @return the acting player, or {@code null} for automated production
     */
    @Nullable
    public ServerPlayer getPlayer() {
        return player;
    }

    /**
     * @return the recipe id that produced the dish, or {@code null} when the
     *         producing device does not expose one
     */
    @Nullable
    public ResourceLocation getRecipeId() {
        return recipeId;
    }

    /**
     * @return a defensive copy of the cooked dish; mutating it does not affect
     *         this event or any other listener
     */
    public ItemStack getResult() {
        return result.copy();
    }

    /**
     * @return the device that produced the dish
     */
    public CookingDevice getDevice() {
        return device;
    }

    /**
     * @return the dish quality
     */
    public DishQuality getQuality() {
        return quality;
    }

    /**
     * @return whether the dish was produced by an automated device
     */
    public boolean isAutomated() {
        return automated;
    }

    /**
     * @return the server level the dish was cooked in
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * @return the block position of the producing device, or {@code null}
     *         when not applicable
     */
    @Nullable
    public BlockPos getPosition() {
        return position;
    }
}
