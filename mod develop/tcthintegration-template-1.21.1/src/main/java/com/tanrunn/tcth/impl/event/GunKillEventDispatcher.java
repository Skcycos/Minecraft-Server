package com.tanrunn.tcth.impl.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Central publishing entry point for {@link GunKillEvent}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>check the framework master switch ({@link Config#ENABLED}) and the
 *       gunner switch ({@link Config#GUNNER_INTEGRATION_ENABLED});</li>
 *   <li>reject non-server contexts;</li>
 *   <li>bounded, expiring idempotency so one kill posts at most once.</li>
 * </ul>
 *
 * <p>No reward, cooldown or rate-limit logic lives here — consumers keep their
 * own state keyed by {@link GunKillEvent#getEventId()}.
 */
public final class GunKillEventDispatcher {

    /**
     * Result of a dispatch attempt.
     */
    public enum Result {
        /** The event was generated and posted to the game bus. */
        POSTED,
        /** The framework master switch is disabled. */
        FRAMEWORK_DISABLED,
        /** The gunner switch is disabled. */
        GUNNER_DISABLED,
        /** Not a server-side context (or level null). */
        INVALID_CONTEXT,
        /** The event was already posted for this kill (idempotency). */
        DUPLICATE
    }

    /** How long an event id stays in the idempotency cache (ticks). */
    static final int IDEMPOTENCY_EXPIRY_TICKS = 100;
    /** Hard cap so the cache can never grow without bound. */
    static final int MAX_TRACKED_KILLS = 4096;

    private static final Map<UUID, Long> RECENT_KILLS = new LinkedHashMap<>(64, 0.75f, true);
    /**
     * Victim-UUID dedup: one death settles at most once even if the death event
     * fires twice or two modules confirm the same kill with different event ids.
     * Same bounds/TTL as the event-id cache.
     */
    private static final Map<UUID, Long> RECENT_VICTIMS = new LinkedHashMap<>(64, 0.75f, true);
    private static long currentTick = 0;

    private static BooleanSupplier enabledSupplier = () -> Config.ENABLED.get();
    private static BooleanSupplier gunnerEnabledSupplier = () -> Config.GUNNER_INTEGRATION_ENABLED.get();

    private static IEventBus gameBus;
    private static boolean initialized = false;

    private GunKillEventDispatcher() {
    }

    /**
     * One-time registration of lifecycle listeners (server tick cleanup +
     * stop cleanup). Idempotent.
     */
    public static void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] GunKillEventDispatcher.init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus = bus;
        bus.addListener(GunKillEventDispatcher::onServerTick);
        bus.addListener(GunKillEventDispatcher::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] GunKillEventDispatcher initialized");
    }

    /**
     * Posts a {@link GunKillEvent} to the game bus.
     *
     * @param event the gun-kill event to post
     * @return the dispatch result; only {@link Result#POSTED} means the event
     *         was posted
     */
    public static Result publish(GunKillEvent event) {
        if (!enabledSupplier.getAsBoolean()) {
            return Result.FRAMEWORK_DISABLED;
        }
        if (!gunnerEnabledSupplier.getAsBoolean()) {
            return Result.GUNNER_DISABLED;
        }
        if (event.getLevel() == null || event.getLevel().isClientSide()) {
            return Result.INVALID_CONTEXT;
        }
        UUID eventId = event.getEventId();
        UUID victimUuid = event.getTargetUuid();
        synchronized (RECENT_KILLS) {
            if (RECENT_KILLS.containsKey(eventId)) {
                return Result.DUPLICATE;
            }
            if (RECENT_VICTIMS.containsKey(victimUuid)) {
                return Result.DUPLICATE; // same target death settled already
            }
        }
        IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
        bus.post(event);
        synchronized (RECENT_KILLS) {
            RECENT_KILLS.put(eventId, currentTick);
            RECENT_VICTIMS.put(victimUuid, currentTick);
            pruneExpiredLocked(currentTick);
        }
        // 5A.2 acceptance debug output (log-only; DEBUG level).
        TCTHIntegration.LOGGER.debug("[TCTH][GUN] event={} weapon={} target={} tier={} dist={} player={} auto={} result=POSTED",
                eventId, event.getWeaponId(), event.getTargetId(), event.getTargetTier(),
                event.getDistance(), event.getPlayer().getUUID(), event.isAutomated());
        return Result.POSTED;
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_KILLS) {
            RECENT_KILLS.entrySet().removeIf(e -> currentTick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
            RECENT_VICTIMS.entrySet().removeIf(e -> currentTick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_KILLS) {
            RECENT_KILLS.clear();
            RECENT_VICTIMS.clear();
        }
        currentTick = 0;
    }

    /** Capacity + expiry cleanup. Caller must hold the monitor. */
    private static void pruneExpiredLocked(long tick) {
        RECENT_KILLS.entrySet().removeIf(e -> tick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
        RECENT_VICTIMS.entrySet().removeIf(e -> tick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
        while (RECENT_KILLS.size() > MAX_TRACKED_KILLS) {
            UUID eldest = RECENT_KILLS.keySet().iterator().next();
            RECENT_KILLS.remove(eldest);
        }
        while (RECENT_VICTIMS.size() > MAX_TRACKED_KILLS) {
            UUID eldest = RECENT_VICTIMS.keySet().iterator().next();
            RECENT_VICTIMS.remove(eldest);
        }
    }

    // ---- test hooks (not part of the public API) ----

    public static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    public static void setGunnerEnabledSupplierForTesting(BooleanSupplier supplier) {
        gunnerEnabledSupplier = supplier;
    }

    public static void setGameBusForTesting(IEventBus bus) {
        gameBus = bus;
    }

    public static int trackedKillCountForTesting() {
        synchronized (RECENT_KILLS) {
            return RECENT_KILLS.size();
        }
    }

    public static void resetForTesting() {
        initialized = false;
        enabledSupplier = () -> Config.ENABLED.get();
        gunnerEnabledSupplier = () -> Config.GUNNER_INTEGRATION_ENABLED.get();
        gameBus = null;
        synchronized (RECENT_KILLS) {
            RECENT_KILLS.clear();
            RECENT_VICTIMS.clear();
        }
        currentTick = 0;
    }
}
