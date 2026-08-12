package com.tanrunn.tcth.impl.shadow;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Central publishing entry point for {@link ShadowTheftEvent} (phase 8B).
 *
 * <p>Responsibilities are strictly limited:
 * <ul>
 *   <li>only posts <em>final</em> attempt events — it never generates
 *       rewards, never mutates the thief or the target, never rolls random
 *       numbers and never executes transfers;</li>
 *   <li>rejects non-server contexts and FakePlayer thieves (the production
 *       coordinator must reject FakePlayers before they ever reach this
 *       point);</li>
 *   <li>defensive: a listener exception is isolated and never breaks the
 *       server tick;</li>
 *   <li>does not reference Jobs+/Arc, Lightman's Currency or Open Parties
 *       and Claims.</li>
 * </ul>
 *
 * <p>Lifecycle registration ({@link #init(IEventBus)}) is idempotent.
 */
public final class ShadowTheftEventDispatcher {

    /**
     * Result of a dispatch attempt.
     */
    public enum Result {
        /** The event was posted to the game bus. */
        POSTED,
        /** Not a server-side context (or a FakePlayer thief). */
        INVALID_CONTEXT,
        /** The shadow thief framework master switch is disabled. */
        FRAMEWORK_DISABLED
    }

    private static IEventBus gameBus;
    private static boolean initialized = false;
    private static java.util.function.BooleanSupplier enabledSupplier = ShadowTheftEventDispatcher::readEnabled;

    private ShadowTheftEventDispatcher() {
    }

    /**
     * One-time registration of the game bus reference. Idempotent.
     */
    public static void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] ShadowTheftEventDispatcher.init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus = bus;
        TCTHIntegration.LOGGER.debug("[TCTH] ShadowTheftEventDispatcher initialized");
    }

    /**
     * Posts a final {@link ShadowTheftEvent} to the game bus.
     *
     * @param event the final attempt event; {@code null} is explicitly
     *              rejected with {@link Result#INVALID_CONTEXT}
     * @return the dispatch result; only {@link Result#POSTED} means the event
     *         was posted
     */
    public static Result publish(ShadowTheftEvent event) {
        if (event == null) {
            return Result.INVALID_CONTEXT; // explicit null rejection (8B.1)
        }
        if (!enabledSupplier.getAsBoolean()) {
            return Result.FRAMEWORK_DISABLED;
        }
        if (event.getLevel() == null || event.getLevel().isClientSide()
                || event.getThief() instanceof FakePlayer) {
            return Result.INVALID_CONTEXT;
        }
        IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
        try {
            bus.post(event);
        } catch (RuntimeException | LinkageError e) {
            // The NeoForge bus rethrows listener exceptions; isolate them so
            // the server tick never breaks. Throttled: a broken listener must
            // not spam the log 20 times per second.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft event dispatch failed (event {}): {}",
                    event.getEventId(), e.toString());
            return Result.INVALID_CONTEXT;
        }
        return Result.POSTED;
    }

    private static boolean readEnabled() {
        try {
            return Config.ENABLED.get() && Config.SHADOW_THIEF_INTEGRATION_ENABLED.get();
        } catch (RuntimeException | LinkageError e) {
            return false; // config read failure fails closed
        }
    }

    // ---- test hooks (not part of the public API) ----

    public static void setEnabledSupplierForTesting(java.util.function.BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    public static void setGameBusForTesting(IEventBus bus) {
        gameBus = bus;
    }

    public static void resetForTesting() {
        initialized = false;
        gameBus = null;
        enabledSupplier = ShadowTheftEventDispatcher::readEnabled;
    }
}
