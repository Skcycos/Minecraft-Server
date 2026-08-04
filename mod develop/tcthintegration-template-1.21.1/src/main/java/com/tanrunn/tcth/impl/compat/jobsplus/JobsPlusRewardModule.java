package com.tanrunn.tcth.impl.compat.jobsplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.DishActionDispatcher;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Jobs+ dish-cooking reward module (settlement side).
 *
 * <p><strong>Disabled by default</strong> ({@code Config.JOBS_PLUS_REWARDS_ENABLED}
 * = false). It must stay off until the seven player take-out scenarios have
 * been verified on a live server.
 *
 * <p>Settlement order (see {@link #onDishCooked}):
 * <ol>
 *   <li>framework master switch + module switch;</li>
 *   <li>actor player exists;</li>
 *   <li>tier resolves (recipe mapping first, then item mapping) — an ungraded
 *       dish returns here and does <em>not</em> consume the rate limit;</li>
 *   <li>automation — the boolean is passed to Arc as action data (no ratio
 *       config: automated production normally has no actor and is dropped at
 *       step 2);</li>
 *   <li>eventId idempotency (bounded, expiring cache);</li>
 *   <li>per-player per-tick rate limit;</li>
 *   <li>send the {@code tcth:on_dish_cooked} Arc action.</li>
 * </ol>
 */
public final class JobsPlusRewardModule {

    /** Bounded idempotency cache: event ids are forgotten after this many ticks. */
    private static final int EVENT_ID_EXPIRY_TICKS = 40;
    /** Hard cap so the cache can never grow without bound. */
    private static final int MAX_TRACKED_EVENT_IDS = 4096;

    // Exposed for tests (same-package).
    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;
    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;

    private static final Map<UUID, Long> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<UUID, Integer> ACTIONS_THIS_TICK = new ConcurrentHashMap<>();
    private static long currentTick = 0;

    private static boolean initialized = false;

    // Config suppliers (injected for tests; production reads Config).
    private static BooleanSupplier rewardsEnabledSupplier = () -> Config.JOBS_PLUS_REWARDS_ENABLED.get();
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static IntSupplier maxActionsPerTickSupplier = () -> Config.MAX_EVENTS_PER_TICK_PER_PLAYER.get();

    /** Action sender (injectable for tests); production uses the Arc bridge. */
    interface DishActionSender {
        com.daqem.arc.api.action.result.ActionResult send(ServerPlayer player, DishCookedEvent event, DishTier tier);
    }

    private static DishActionSender actionSender = DishActionDispatcher::sendDishAction;

    private JobsPlusRewardModule() {
    }

    /**
     * Registers the module on the game bus. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] Jobs+ reward module init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus.addListener(JobsPlusRewardModule::onDishCooked);
        gameBus.addListener(JobsPlusRewardModule::onServerTick);
        gameBus.addListener(JobsPlusRewardModule::onServerStopping);
        gameBus.addListener(JobsPlusRewardModule::onAddReloadListeners);
        TCTHIntegration.LOGGER.debug("[TCTH] Jobs+ reward module registered (disabled by default)");
    }

    static void onDishCooked(DishCookedEvent event) {
        // 1. master switch + module switch.
        if (!frameworkEnabledSupplier.getAsBoolean() || !rewardsEnabledSupplier.getAsBoolean()) {
            return;
        }
        // 2. actor player.
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        // 3. tier resolution (recipe mapping first, then item mapping).
        DishTierDefinition definition = DishTierManager.resolve(event.getRecipeId(), event.getResult()).orElse(null);
        if (definition == null) {
            return; // ungraded: no reward action, and no rate-limit consumption
        }
        DishTier tier = definition.tier();
        // 4. automation: no ratio handling here — the flag travels as action
        //    data so Arc data packs can filter on it.
        // 5. idempotency check (eventId NOT recorded yet).
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
        }
        // 6. rate limit check (count NOT incremented yet).
        int actions = ACTIONS_THIS_TICK.merge(player.getUUID(), 0, Integer::sum);
        if (actions >= maxActionsPerTickSupplier.getAsInt()) {
            TCTHIntegration.LOGGER.debug("[TCTH] Dish action for {} dropped (rate limit for {})",
                    event.getEventId(), player.getGameProfile().getName());
            return;
        }
        // 7. send the Arc action; only record idempotency + count on success so
        //    a failed event can be retried safely.
        if (actionSender.send(player, event, tier) != null) {
            synchronized (RECENT_EVENT_IDS) {
                RECENT_EVENT_IDS.put(event.getEventId(), currentTick);
                pruneExpiredLocked();
            }
            ACTIONS_THIS_TICK.merge(player.getUUID(), 1, Integer::sum);
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        ACTIONS_THIS_TICK.clear();
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        // Avoid residue across integrated-server restarts in the same JVM.
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        currentTick = 0;
    }

    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new DishTierManager());
    }

    /**
     * Capacity + expiry cleanup. Caller must hold the monitor.
     */
    private static void pruneExpiredLocked() {
        RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
            UUID eldest = RECENT_EVENT_IDS.keySet().iterator().next();
            RECENT_EVENT_IDS.remove(eldest);
        }
    }

    // ---- test hooks (package-private) ----

    static void setRewardsEnabledSupplierForTesting(BooleanSupplier supplier) {
        rewardsEnabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setMaxActionsPerTickSupplierForTesting(IntSupplier supplier) {
        maxActionsPerTickSupplier = supplier;
    }

    static void setActionSenderForTesting(DishActionSender sender) {
        actionSender = sender;
    }

    static int trackedEventIdCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static long currentTickForTesting() {
        return currentTick;
    }

    static void resetForTesting() {
        initialized = false;
        rewardsEnabledSupplier = () -> Config.JOBS_PLUS_REWARDS_ENABLED.get();
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        maxActionsPerTickSupplier = () -> Config.MAX_EVENTS_PER_TICK_PER_PLAYER.get();
        actionSender = DishActionDispatcher::sendDishAction;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        currentTick = 0;
    }
}
