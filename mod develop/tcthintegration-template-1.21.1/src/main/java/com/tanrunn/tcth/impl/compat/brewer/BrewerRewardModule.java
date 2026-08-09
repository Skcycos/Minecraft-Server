package com.tanrunn.tcth.impl.compat.brewer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.compat.brewer.arc.BeverageActionDispatcher;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Brewer reward module — settlement side for beverage events (phase 7C).
 *
 * <ul>
 *   <li>master switch + brewer integration + brewer rewards (three-way);</li>
 *   <li>player=null or automated=true → no settlement;</li>
 *   <li>{@code UNKNOWN} tier → no settlement (no reward action, no rate-limit
 *       consumption);</li>
 *   <li>eventId idempotency (bounded, expiring cache) — committed only after
 *       the action send succeeds;</li>
 *   <li>per-player per-tick rate limit;</li>
 *   <li>single-event exception isolation (a failed send is logged, never
 *       breaks the tick, and does not occupy the cache/rate-limit);</li>
 *   <li>cache cleared on server stop.</li>
 * </ul>
 *
 * <p>No gold rewards and no changes to chef/farmer/gunner reward pipelines.
 * Only real Keg pours (automated=false, runtime tier) settle.
 */
public final class BrewerRewardModule {

    private static final int EVENT_ID_EXPIRY_TICKS = 40;
    private static final int MAX_TRACKED_EVENT_IDS = 4096;

    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;
    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;

    private static final Map<UUID, EventRecord> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<UUID, Integer> ACTIONS_THIS_TICK = new ConcurrentHashMap<>();
    private static long currentTick = 0;

    /** Idempotency cache record: owning player + committed tick. */
    private record EventRecord(UUID playerUuid, long committedTick) {
    }

    private static boolean initialized = false;

    // Config suppliers (injected for tests; production reads Config).
    private static BooleanSupplier frameworkEnabledSupplier = () -> Config.ENABLED.get();
    private static BooleanSupplier integrationEnabledSupplier = () -> Config.BREWER_INTEGRATION_ENABLED.get();
    private static BooleanSupplier rewardsEnabledSupplier = () -> Config.BREWER_REWARDS_ENABLED.get();
    private static IntSupplier maxActionsPerTickSupplier = () -> Config.MAX_BREWER_REWARDS_PER_TICK_PER_PLAYER.get();

    /** Action sender (injectable for tests); production uses the Arc bridge. */
    interface BeverageActionSender {
        com.daqem.arc.api.action.result.ActionResult send(ServerPlayer player, BeveragePreparedEvent event,
                                                          BeverageTier tier);
    }

    private static BeverageActionSender actionSender = BeverageActionDispatcher::sendBeverageAction;

    private BrewerRewardModule() {
    }

    /** Registers the module on the game bus. Idempotent. */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] Brewer reward module init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus.addListener(BrewerRewardModule::onBeveragePrepared);
        gameBus.addListener(BrewerRewardModule::onServerTick);
        gameBus.addListener(BrewerRewardModule::onServerStopping);
        gameBus.addListener(BrewerRewardModule::onPlayerLoggedOut);
        TCTHIntegration.LOGGER.debug("[TCTH] Brewer reward module registered (disabled by default)");
    }

    static void onBeveragePrepared(BeveragePreparedEvent event) {
        try {
            // 1. three-way switch.
            if (!frameworkEnabledSupplier.getAsBoolean()
                    || !integrationEnabledSupplier.getAsBoolean()
                    || !rewardsEnabledSupplier.getAsBoolean()) {
                return;
            }
            // 2. actor player + automation.
            ServerPlayer player = event.getPlayer();
            if (player == null || event.isAutomated()) {
                return;
            }
            // 3. runtime tier only (UNKNOWN → no settlement, no rate-limit use).
            BeverageTier tier = event.getTier();
            if (tier == BeverageTier.UNKNOWN || tier == BeverageTier.T3) {
                return; // T3 has no action/reward (7C), UNKNOWN is not graded
            }
            // 4. idempotency check (eventId NOT recorded yet).
            synchronized (RECENT_EVENT_IDS) {
                if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                    return;
                }
            }
            // 5. rate limit check (count NOT incremented yet).
            int actions = ACTIONS_THIS_TICK.merge(player.getUUID(), 0, Integer::sum);
            if (actions >= maxActionsPerTickSupplier.getAsInt()) {
                TCTHIntegration.LOGGER.debug("[TCTH] Beverage action for {} dropped (rate limit for {})",
                        event.getEventId(), player.getGameProfile().getName());
                return;
            }
            // 6. send the Arc action; only record idempotency + count on
            //    success so a failed event can be retried safely.
            if (actionSender.send(player, event, tier) != null) {
                synchronized (RECENT_EVENT_IDS) {
                    RECENT_EVENT_IDS.put(event.getEventId(), new EventRecord(player.getUUID(), currentTick));
                    pruneExpiredLocked();
                }
                ACTIONS_THIS_TICK.merge(player.getUUID(), 1, Integer::sum);
            }
        } catch (RuntimeException | LinkageError e) {
            // Single-event isolation: a failed event never breaks the tick and
            // does not consume idempotency / rate-limit state.
            TCTHIntegration.LOGGER.warn("[TCTH] Beverage reward settlement failed: {}", e.toString());
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        ACTIONS_THIS_TICK.clear();
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue().committedTick() > EVENT_ID_EXPIRY_TICKS);
        }
    }

    static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        UUID uuid = event.getEntity().getUUID();
        ACTIONS_THIS_TICK.remove(uuid);
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> e.getValue().playerUuid().equals(uuid));
        }
        TCTHIntegration.LOGGER.debug("[TCTH] Brewer reward caches cleared for logged-out player {}", uuid);
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        TCTHIntegration.LOGGER.debug("[TCTH] Brewer reward module caches cleared");
    }

    private static void pruneExpiredLocked() {
        // 1. Always drop entries older than the expiry window first.
        RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue().committedTick() > EVENT_ID_EXPIRY_TICKS);
        // 2. Hard cap: even if thousands of events arrive within the same
        //    window, evict the oldest entries until size <= MAX_TRACKED.
        while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
            var it = RECENT_EVENT_IDS.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next(); // LinkedHashMap iteration order = insertion order
            it.remove();
        }
    }

    // ---- test hooks ----

    static int cachedEventIdCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static void setRewardsEnabledSupplierForTesting(BooleanSupplier supplier) {
        rewardsEnabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setIntegrationEnabledSupplierForTesting(BooleanSupplier supplier) {
        integrationEnabledSupplier = supplier;
    }

    static void setMaxActionsPerTickSupplierForTesting(IntSupplier supplier) {
        maxActionsPerTickSupplier = supplier;
    }

    static void setActionSenderForTesting(BeverageActionSender sender) {
        actionSender = sender == null ? BeverageActionDispatcher::sendBeverageAction : sender;
    }

    static void resetForTesting() {
        rewardsEnabledSupplier = () -> Config.BREWER_REWARDS_ENABLED.get();
        frameworkEnabledSupplier = () -> Config.ENABLED.get();
        integrationEnabledSupplier = () -> Config.BREWER_INTEGRATION_ENABLED.get();
        maxActionsPerTickSupplier = () -> Config.MAX_BREWER_REWARDS_PER_TICK_PER_PLAYER.get();
        actionSender = BeverageActionDispatcher::sendBeverageAction;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        currentTick = 0;
    }
}
