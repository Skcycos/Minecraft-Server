package com.tanrunn.tcth.impl.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Gunner-statistics tracker (phase 5A.1).
 *
 * <p>Listens to {@link GunKillEvent} and records per-player statistics. Only
 * counts events with a real player, {@code automated=false}, and a
 * non-duplicate event id.
 *
 * <p>Settlement order (idempotency is committed <em>last</em>):
 * <ol>
 *   <li>switches (framework + gunner integration + gunner stats);</li>
 *   <li>player/automated checks;</li>
 *   <li>event-id duplicate check (no write yet);</li>
 *   <li>fetch SavedData, get/create player stats;</li>
 *   <li>successfully {@code record()} + {@code setDirty()};</li>
 *   <li>only now commit the event id.</li>
 * </ol>
 *
 * <p>Every event is processed in isolation: a {@link RuntimeException} or
 * {@link LinkageError} is logged (throttled) and never propagates to the event
 * bus, so a single stats failure can neither break the death event nor the
 * server tick, and the event id stays free for a safe retry.
 *
 * <p>Independent from Jobs+/Arc: this module references no third-party types.
 */
public final class GunnerStatsTracker {

    private static final int MAX_TRACKED_EVENT_IDS = 4096;
    /** How long an event id stays in the dedup cache (ticks). */
    private static final int EVENT_ID_EXPIRY_TICKS = 100;

    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;
    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;

    private static final Map<UUID, Long> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);
    private static long currentTick = 0;

    /** Throttle window for repeated stats-failure ERRORs (millis). */
    private static final long ERROR_THROTTLE_MS = 60_000L;
    private static long lastErrorAt = 0L;

    private static boolean initialized = false;
    private static BooleanSupplier enabledSupplier = () -> Config.GUNNER_STATS_ENABLED.get();
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static BooleanSupplier integrationEnabledSupplier =
            () -> Config.GUNNER_INTEGRATION_ENABLED.get();
    private static java.util.function.Function<net.minecraft.server.level.ServerLevel, GunnerStatsData> dataProvider =
            GunnerStatsData::current;

    private GunnerStatsTracker() {
    }

    /**
     * Registers the tracker on the game bus. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            return;
        }
        initialized = true;
        gameBus.addListener(GunnerStatsTracker::onGunKill);
        gameBus.addListener(GunnerStatsTracker::onServerTick);
        gameBus.addListener(GunnerStatsTracker::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] Gunner stats tracker registered");
    }

    static void onGunKill(GunKillEvent event) {
        try {
            settle(event);
        } catch (RuntimeException | LinkageError e) {
            // Single-event isolation: never break the death event or the tick.
            // Log throttled so a failing store cannot spam the log.
            errorThrottled("[TCTH] Gunner stats failed for event {}, player {}, weapon {}: {}",
                    safeEventId(event), safePlayerUuid(event), safeWeaponId(event), e.toString());
        }
    }

    private static void errorThrottled(String message, Object... args) {
        long now = System.currentTimeMillis();
        synchronized (GunnerStatsTracker.class) {
            if (now - lastErrorAt < ERROR_THROTTLE_MS) {
                return;
            }
            lastErrorAt = now;
        }
        TCTHIntegration.LOGGER.error(message, args);
    }

    private static void settle(GunKillEvent event) {
        if (!switchesEnabled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer)) {
            return; // no actor
        }
        if (event.isAutomated()) {
            return;
        }
        // Duplicate check FIRST — do not write yet.
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
        }
        // The actual work: any failure here leaves the event id uncommitted so
        // the same event can be retried safely.
        GunnerStatsData data = dataProvider.apply(event.getLevel());
        PlayerGunnerStats stats = data.getOrCreate(event.getPlayer().getUUID());
        if (stats == null) {
            return; // player cap reached; skip rather than grow unbounded
        }
        stats.record(
                event.getWeaponId().toString(),
                event.getTargetId().toString(),
                event.getTargetTier(),
                event.getDistance(),
                System.currentTimeMillis());
        data.setDirty();
        // Commit the idempotency ONLY after a successful write.
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.put(event.getEventId(), currentTick);
            pruneExpiredLocked();
        }
        // Gunner debug output (INFO, in-memory switch, default off).
        if (com.tanrunn.tcth.impl.debug.GunDebug.isEnabled()) {
            TCTHIntegration.LOGGER.info("[TCTH][GUN] stats event={} player={} weapon={} tier={} dist={} total={}",
                    event.getEventId(), event.getPlayer().getUUID(), event.getWeaponId(),
                    event.getTargetTier(), event.getDistance(), stats.getTotalGunKills());
        }
    }

    /** Framework + gunner integration + stats switches, fail-closed. */
    private static boolean switchesEnabled() {
        try {
            return frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && enabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        currentTick = 0;
    }

    /** Expiry + capacity cleanup. Caller must hold the monitor. */
    private static void pruneExpiredLocked() {
        RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
            UUID eldest = RECENT_EVENT_IDS.keySet().iterator().next();
            RECENT_EVENT_IDS.remove(eldest);
        }
    }

    private static String safeEventId(GunKillEvent event) {
        return event != null && event.getEventId() != null ? event.getEventId().toString() : "?";
    }

    private static String safePlayerUuid(GunKillEvent event) {
        return event != null && event.getPlayer() != null ? event.getPlayer().getUUID().toString() : "?";
    }

    private static String safeWeaponId(GunKillEvent event) {
        return event != null && event.getWeaponId() != null ? event.getWeaponId().toString() : "?";
    }

    // ---- test hooks ----

    static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setIntegrationEnabledSupplierForTesting(BooleanSupplier supplier) {
        integrationEnabledSupplier = supplier;
    }

    static void setDataProviderForTesting(java.util.function.Function<net.minecraft.server.level.ServerLevel, GunnerStatsData> provider) {
        dataProvider = provider;
    }

    static int trackedEventIdCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static void resetForTesting() {
        initialized = false;
        enabledSupplier = () -> Config.GUNNER_STATS_ENABLED.get();
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        integrationEnabledSupplier = () -> Config.GUNNER_INTEGRATION_ENABLED.get();
        dataProvider = GunnerStatsData::current;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        synchronized (GunnerStatsTracker.class) {
            lastErrorAt = 0L;
        }
        currentTick = 0;
    }
}
