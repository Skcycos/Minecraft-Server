package com.tanrunn.tcth.impl.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Brewing-statistics tracker (phase 7D).
 *
 * <p>Listens to {@link BeveragePreparedEvent} and records per-player brewing
 * statistics into {@link BrewingStatsData} ({@code tcth_brewing_stats.dat}).
 * Only real players ({@code automated=false}) with a graded runtime tier
 * ({@code COMMON} or {@code T2}) are recorded; automated / null-player /
 * UNKNOWN / T3 / duplicate-event-id deliveries are skipped.
 *
 * <p>Independent from Jobs+/Arc and from brewer rewards: this module references
 * no third-party types and works even when Jobs+ is not installed.
 *
 * <p>Event-id deduplication uses a bounded cache with:
 * <ul>
 *   <li>hard cap (4096 entries, LRU eviction);</li>
 *   <li>~40-tick expiry (per-tick sweep);</li>
 *   <li>per-player cleanup on logout and full clear on server stopping;</li>
 *   <li>commit only AFTER the stats write succeeds, so a redelivered event can
 *       be retried after a failed write.</li>
 * </ul>
 *
 * <p>Config reads fail CLOSED: if {@code brewerStatsEnabled} cannot be read
 * (config exception), statistics are not recorded.
 */
public final class BrewingStatsTracker {

    private static final int MAX_TRACKED_EVENT_IDS = 4096;
    private static final int EVENT_ID_EXPIRY_TICKS = 40;

    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;
    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;

    /** event id -> (owning player, committed tick); access-ordered for LRU. */
    private static final Map<UUID, EventRecord> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);

    /** Idempotency cache record: owning player + committed tick. */
    private record EventRecord(UUID playerUuid, long committedTick) {
    }

    private static long currentTick = 0;

    private static boolean initialized = false;
    private static BooleanSupplier enabledSupplier = BrewingStatsTracker::brewerStatsEnabled;
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static Function<ServerLevel, BrewingStatsData> dataProvider = BrewingStatsData::current;

    private BrewingStatsTracker() {
    }

    /**
     * Registers the tracker on the game bus. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            return;
        }
        initialized = true;
        gameBus.addListener(BrewingStatsTracker::onBeveragePrepared);
        gameBus.addListener(BrewingStatsTracker::onServerTick);
        gameBus.addListener(BrewingStatsTracker::onPlayerLoggedOut);
        gameBus.addListener(BrewingStatsTracker::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] Brewing stats tracker registered");
    }

    static void onBeveragePrepared(BeveragePreparedEvent event) {
        if (!switchesEnabled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer)) {
            return; // automated production without an actor
        }
        if (event.isAutomated()) {
            return;
        }
        int count = event.getResult().getCount();
        if (count <= 0) {
            return;
        }
        BeverageTier tier = event.getTier();
        if (tier == null || tier == BeverageTier.UNKNOWN || tier == BeverageTier.T3) {
            return; // only graded COMMON/T2 events are archived (T3 is review-only)
        }
        // bounded event-id deduplication: commit only after a successful write.
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
        }
        BrewingStatsData data = dataProvider.apply(event.getLevel());
        PlayerBrewingStats stats = data.getOrCreate(event.getPlayer().getUUID());
        if (stats == null) {
            return; // cap reached; skip rather than grow unbounded
        }
        stats.record(tier, event.getDevice(),
                event.getResult().getItem().builtInRegistryHolder().key().location().toString(),
                count, System.currentTimeMillis());
        data.setDirty();
        // Commit AFTER the write succeeded; prune expired + cap within the lock.
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.put(event.getEventId(),
                    new EventRecord(event.getPlayer().getUUID(), currentTick));
            pruneExpiredLocked();
            while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
                var it = RECENT_EVENT_IDS.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
    }

    /**
     * Fail-closed switch check: a config read exception (either the
     * brewerStatsEnabled supplier or the framework switch) disables stats.
     */
    private static boolean switchesEnabled() {
        try {
            return enabledSupplier.getAsBoolean() && frameworkEnabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(
                    e -> currentTick - e.getValue().committedTick() > EVENT_ID_EXPIRY_TICKS);
        }
    }

    static void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        UUID uuid = event.getEntity().getUUID();
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> e.getValue().playerUuid().equals(uuid));
        }
        TCTHIntegration.LOGGER.debug("[TCTH] Brewing stats event-id cache cleared for logged-out player {}", uuid);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        TCTHIntegration.LOGGER.debug("[TCTH] Brewing stats event-id cache cleared");
    }

    private static void pruneExpiredLocked() {
        RECENT_EVENT_IDS.entrySet().removeIf(
                e -> currentTick - e.getValue().committedTick() > EVENT_ID_EXPIRY_TICKS);
    }

    /** Fail-closed config read: config exception → statistics off. */
    private static boolean brewerStatsEnabled() {
        try {
            return Config.BREWER_STATS_ENABLED.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- test hooks ----

    static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setDataProviderForTesting(Function<ServerLevel, BrewingStatsData> provider) {
        dataProvider = provider;
    }

    static int trackedEventIdCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static long currentTickForTesting() {
        return currentTick;
    }

    static void tickForTesting() {
        onServerTick(null);
    }

    static void stopForTesting() {
        onServerStopping(null);
    }

    static void resetForTesting() {
        initialized = false;
        enabledSupplier = BrewingStatsTracker::brewerStatsEnabled;
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        dataProvider = BrewingStatsData::current;
        currentTick = 0;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
    }
}
