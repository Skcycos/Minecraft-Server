package com.tanrunn.tcth.impl.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTierManager;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

/**
 * Cooking-statistics tracker.
 *
 * <p>Listens to {@link DishCookedEvent} and records per-player statistics. Only
 * counts events with a non-null player, {@code automated=false}, passing
 * {@link DishClassifier}, with a non-duplicate event id.
 *
 * <p>Independent from Jobs+/Arc: this module references no third-party types
 * and works even when Jobs+ is not installed. It re-uses {@link DishTierManager}
 * (a TCTH-internal, dependency-free tier resolver) for tier classification and
 * registers its reload listener itself so tier data loads even without Jobs+.
 *
 * <p>Event-id deduplication uses a bounded cache.
 */
public final class CookingStatsTracker {

    private static final int MAX_TRACKED_EVENT_IDS = 4096;

    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;

    private static final Map<UUID, Boolean> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);

    private static boolean initialized = false;
    private static BooleanSupplier enabledSupplier = () -> Config.COOKING_STATS_ENABLED.get();
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static java.util.function.Function<net.minecraft.server.level.ServerLevel, CookingStatsData> dataProvider =
            CookingStatsData::current;

    private CookingStatsTracker() {
    }

    /**
     * Registers the tracker on the game bus. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            return;
        }
        initialized = true;
        gameBus.addListener(CookingStatsTracker::onDishCooked);
        TCTHIntegration.LOGGER.debug("[TCTH] Cooking stats tracker registered");
    }

    static void onDishCooked(DishCookedEvent event) {
        if (!enabledSupplier.getAsBoolean() || !frameworkEnabledSupplier.getAsBoolean()) {
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
        if (!DishClassifier.isDish(event.getResult())) {
            return;
        }
        // bounded event-id deduplication
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
            RECENT_EVENT_IDS.put(event.getEventId(), Boolean.TRUE);
            while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
                UUID eldest = RECENT_EVENT_IDS.keySet().iterator().next();
                RECENT_EVENT_IDS.remove(eldest);
            }
        }
        DishTier tier = DishTierManager.resolve(event.getRecipeId(), event.getResult())
                .map(def -> def.tier()).orElse(null);
        CookingStatsData data = dataProvider.apply(event.getLevel());
        PlayerCookingStats stats = data.getOrCreate(event.getPlayer().getUUID());
        if (stats == null) {
            return; // cap reached; skip rather than grow unbounded
        }
        stats.record(event.getDevice(), tier, event.getQuality(),
                event.getResult().getItem().builtInRegistryHolder().key().location().toString(),
                count, System.currentTimeMillis());
        data.setDirty();
    }

    // ---- test hooks ----

    static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setDataProviderForTesting(java.util.function.Function<net.minecraft.server.level.ServerLevel, CookingStatsData> provider) {
        dataProvider = provider;
    }

    static int trackedEventIdCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static void resetForTesting() {
        initialized = false;
        enabledSupplier = () -> Config.COOKING_STATS_ENABLED.get();
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        dataProvider = CookingStatsData::current;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
    }
}
