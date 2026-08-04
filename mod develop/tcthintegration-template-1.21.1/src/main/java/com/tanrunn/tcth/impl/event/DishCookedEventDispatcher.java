package com.tanrunn.tcth.impl.event;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Central publishing entry point for {@link DishCookedEvent}.
 *
 * <p>All TCTH cooking detectors must go through this dispatcher so that:
 * <ul>
 *   <li>a fresh {@link UUID} is generated per cooked dish and attached to the
 *       event;</li>
 *   <li>nothing is posted when the framework master switch
 *       ({@code Config.ENABLED}) is {@code false};</li>
 *   <li>nothing is posted outside a server context (client-side levels are
 *       rejected);</li>
 *   <li>the outcome is explicit via {@link Result}.</li>
 * </ul>
 *
 * <p>This class deliberately contains no experience, cooldown, rate-limit or
 * profession logic, and uses no global {@code settled}-style mechanism —
 * consumers keep their own idempotency state keyed by
 * {@link DishCookedEvent#getEventId()}.
 */
public final class DishCookedEventDispatcher {

    /**
     * Result of a dispatch attempt.
     */
    public enum Result {
        /** The event was generated and posted to the game bus. */
        POSTED,
        /** The framework master switch is disabled; nothing was posted. */
        FRAMEWORK_DISABLED,
        /** Not a server-side context; nothing was posted. */
        INVALID_CONTEXT
    }

    private static BooleanSupplier enabledSupplier = DishCookedEventDispatcher::readConfigEnabled;

    /** Test-injectable game bus; when null, {@link NeoForge#EVENT_BUS} is used. */
    private static IEventBus gameBus;

    private static boolean initialized = false;

    private DishCookedEventDispatcher() {
    }

    /**
     * One-time initialization hook, called from the mod constructor.
     *
     * <p>Idempotent: calling this more than once is safe and registers nothing
     * twice. In the current phase no internal bus listeners are registered.
     */
    public static void init() {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] DishCookedEventDispatcher.init called more than once; ignoring");
            return;
        }
        initialized = true;
        TCTHIntegration.LOGGER.debug("[TCTH] DishCookedEventDispatcher initialized");
    }

    /**
     * Generates an event id, builds a {@link DishCookedEvent} and posts it to
     * the game bus.
     *
     * @param player    the acting player, or {@code null}
     * @param recipeId  the recipe id, or {@code null}
     * @param result    the cooked dish item; must not be null
     * @param device    the producing device; must not be null
     * @param quality   the dish quality; must not be null
     * @param automated whether the production was automated
     * @param level     the server level; must not be null and must be a
     *                  server-side level
     * @param position  the device position, or {@code null}
     * @return the dispatch result; only {@link Result#POSTED} means the event
     *         was actually posted
     */
    public static Result publish(@Nullable ServerPlayer player, @Nullable ResourceLocation recipeId, ItemStack result,
                                 CookingDevice device, DishQuality quality, boolean automated, ServerLevel level,
                                 @Nullable BlockPos position) {
        if (!enabledSupplier.getAsBoolean()) {
            return Result.FRAMEWORK_DISABLED;
        }
        if (level == null || level.isClientSide()) {
            return Result.INVALID_CONTEXT;
        }
        IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
        DishCookedEvent event = new DishCookedEvent(UUID.randomUUID(), player, recipeId, result, device, quality,
                automated, level, position);
        bus.post(event);
        return Result.POSTED;
    }

    private static boolean readConfigEnabled() {
        return Config.ENABLED.get();
    }

    // ---- test hooks (not part of the public API) ----

    /**
     * Test hook: overrides the framework-enabled supplier.
     * Not part of the public API.
     */
    public static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    /**
     * Test hook: overrides the game bus. Not part of the public API.
     */
    public static void setGameBusForTesting(IEventBus bus) {
        gameBus = bus;
    }

    /**
     * Test hook: restores defaults. Not part of the public API.
     */
    public static void resetForTesting() {
        enabledSupplier = DishCookedEventDispatcher::readConfigEnabled;
        gameBus = null;
        initialized = false;
    }
}
