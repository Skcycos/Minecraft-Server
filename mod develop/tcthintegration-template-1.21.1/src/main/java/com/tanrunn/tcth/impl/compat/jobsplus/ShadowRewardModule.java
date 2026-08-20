package com.tanrunn.tcth.impl.compat.jobsplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.ShadowTheftSuccessActionDispatcher;
import com.tanrunn.tcth.impl.shadow.ShadowExperienceLimitStore;
import com.tanrunn.tcth.impl.shadow.ShadowExperienceLimitWriter;
import com.tanrunn.tcth.impl.shadow.ShadowLogThrottle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Shadow thief job-experience settlement for {@link ShadowTheftEvent}s (phase
 * 8E).
 *
 * <p><strong>Disabled by default</strong> ({@code Config.SHADOW_REWARDS_ENABLED}
 * = false) — enable only after live verification.
 *
 * <p>Settlement order:
 * <ol>
 *   <li>framework + integration + reward switches;</li>
 *   <li>real thief, not automated, {@code SUCCESS} outcome, non-empty receipt
 *       matching the drawn theft type (the coordinator only posts SUCCESS
 *       AFTER the FINAL audit write — never RECOVERY_REQUIRED);</li>
 *   <li>eventId idempotency (bounded, expiring in-memory cache + the durable
 *       per-pair store for PLAYER targets);</li>
 *   <li>PLAYER targets: per-(thief, target)-pair daily cap
 *       ({@code shadowMaxExperienceRewardsPerPairPerDay}, default 3, UTC
 *       date) with an eventId-idempotent reservation protocol —
 *       {@code tryReserve} BEFORE the Arc send (occupies the quota),
 *       {@code releaseReservation} on a clearly failed send (retryable),
 *       {@code commitReservation} after a successful send; a failed/unknown
 *       commit keeps the slot occupied so duplicate XP is never granted.
 *       A pre-restart reservation loads as RECOVERY and is never re-sent
 *       (8E.1 §1). Entity targets stay bounded by the LOOTED once-state
 *       (8D) — no extra per-pair limit;</li>
 *   <li>send the {@code tcth:on_shadow_theft_success} Arc action — the XP
 *       amounts themselves are data-driven by the preset
 *       ({@code jobsplus:job_exp} rewards: ENTITY 1–2; PLAYER ITEM 3–5,
 *       HEALTH 2–4, HUNGER 2–4, EFFECT 4–6).</li>
 * </ol>
 *
 * <p>Exception isolation (8E.1 §4): the listener catches
 * {@code RuntimeException | LinkageError}; every config supplier, the store
 * factory and the store calls fail closed on their own, and a throwing
 * action sender keeps the reservation occupied (unknown outcome). All
 * high-frequency failure logs go through {@link ShadowLogThrottle} (60 s per
 * template). In-memory state (eventId cache, tick counter) is cleaned on
 * logout (per player) and on server stop.
 */
public final class ShadowRewardModule {

    private static final int EVENT_ID_EXPIRY_TICKS = 40;
    private static final int MAX_TRACKED_EVENT_IDS = 4096;

    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;
    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;

    /** In-memory eventId fast path: eventId → (thief, tick). */
    private record EventEntry(UUID thiefId, long tick) {
    }

    private static final Map<UUID, EventEntry> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);
    private static long currentTick = 0;

    private static boolean initialized = false;

    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static BooleanSupplier integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
    private static BooleanSupplier rewardsEnabledSupplier = Config.SHADOW_REWARDS_ENABLED::get;
    private static LongSupplier maxPerPairPerDaySupplier =
            Config.SHADOW_MAX_EXPERIENCE_REWARDS_PER_PAIR_PER_DAY::get;
    private static Supplier<String> dailyDateSupplier = ShadowExperienceLimitStore::today;
    private static Function<ServerLevel, ShadowExperienceLimitWriter> experienceLimitStoreFactory =
            level -> ShadowExperienceLimitStore.current(level);

    interface ShadowTheftActionSender {
        ShadowSendResult send(ServerPlayer player, ShadowTheftEvent event);
    }

    private static ShadowTheftActionSender actionSender =
            ShadowTheftSuccessActionDispatcher::sendShadowTheftSuccessAction;

    private ShadowRewardModule() {
    }

    /** Registers the module on the game bus. Idempotent. */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] Shadow reward module init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus.addListener(ShadowRewardModule::onShadowTheft);
        gameBus.addListener(ShadowRewardModule::onServerTick);
        gameBus.addListener(ShadowRewardModule::onServerStopping);
        gameBus.addListener(ShadowRewardModule::onPlayerLogout);
        TCTHIntegration.LOGGER.debug("[TCTH] Shadow reward module registered (disabled by default)");
    }

    static void onShadowTheft(ShadowTheftEvent event) {
        try {
            settle(event);
        } catch (RuntimeException | LinkageError e) {
            // A single module failure must never break the server tick; the
            // high-frequency path is throttled (8E.1 §4) — no per-event
            // ERROR spam.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow reward settlement failed for {}: {}",
                    event == null ? "null" : event.getEventId(), e.toString());
        }
    }

    private static void settle(ShadowTheftEvent event) {
        if (event == null) {
            return;
        }
        // Every switch read fails closed: a RuntimeException or LinkageError
        // from a config supplier means "no rewards", never a bus escape.
        if (!safeSwitch(frameworkEnabledSupplier)
                || !safeSwitch(integrationEnabledSupplier)
                || !safeSwitch(rewardsEnabledSupplier)) {
            return;
        }
        ServerPlayer thief = event.getThief();
        if (thief == null) {
            return;
        }
        if (event.isAutomated()) {
            return; // defence in depth: automated sources never grant XP
        }
        if (event.getOutcome() != ShadowTheftOutcome.SUCCESS) {
            return; // every non-SUCCESS outcome is worth 0 XP
        }
        ShadowTheftType theftType = event.getTheftType();
        ShadowTheftReceipt receipt = event.getReceipt();
        if (theftType == null || receipt == null || receipt.isEmpty() || !receipt.matches(theftType)) {
            return; // the receipt must be non-empty and match the drawn type
        }
        // SUCCESS contract (coordinator): the FINAL audit record was already
        // written and the outcome is never RECOVERY_REQUIRED here.
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
        }
        // PLAYER targets: per-pair daily cap with the reservation protocol.
        ShadowExperienceLimitWriter store = null;
        if (event.getTargetKind() == ShadowTargetKind.PLAYER) {
            String utcDay;
            long limit;
            try {
                utcDay = dailyDateSupplier.get();
            } catch (RuntimeException | LinkageError e) {
                utcDay = null;
            }
            if (utcDay == null || utcDay.isEmpty()) {
                return; // fail-closed: no XP without a strictly valid UTC day
            }
            try {
                limit = maxPerPairPerDaySupplier.getAsLong();
            } catch (RuntimeException | LinkageError e) {
                return; // fail-closed
            }
            if (limit <= 0L) {
                return;
            }
            try {
                store = experienceLimitStoreFactory.apply(event.getLevel());
            } catch (RuntimeException | LinkageError e) {
                store = null;
            }
            if (store == null) {
                return; // fail-closed: unavailable store → no XP
            }
            ShadowExperienceLimitWriter.ReservationResult reservation;
            try {
                reservation = store.tryReserve(thief.getUUID(), event.getTargetId(), utcDay,
                        event.getEventId(), limit);
            } catch (RuntimeException | LinkageError e) {
                reservation = ShadowExperienceLimitWriter.ReservationResult.REJECTED;
            }
            if (reservation == ShadowExperienceLimitWriter.ReservationResult.LIMIT_REACHED
                    || reservation == ShadowExperienceLimitWriter.ReservationResult.REJECTED) {
                return; // at the daily cap or storage refused (fail-closed)
            }
            if (reservation == ShadowExperienceLimitWriter.ReservationResult.COMMITTED_EXISTING) {
                // This eventId already granted XP (e.g. a re-fired event after
                // a restart): never send a second time.
                settleEventId(event);
                return;
            }
            if (reservation == ShadowExperienceLimitWriter.ReservationResult.RECOVERY_EXISTING) {
                // A pre-restart RESERVED migrated to RECOVERY: the send
                // outcome is UNKNOWN — the slot stays occupied and the Arc
                // action is NEVER sent again (8E.1 §1). Not a clean failure:
                // no release.
                settleEventId(event);
                return;
            }
        }
        // Only a successful send settles the eventId. A CLEAR send failure
        // (CLEAR_FAILURE) releases the reservation so the attempt can be
        // retried; an UNKNOWN outcome (the sender threw an exception that
        // escaped the dispatcher) is treated as conservative — the
        // reservation stays occupied (no duplicate XP).
        ShadowSendResult sendResult;
        try {
            sendResult = actionSender.send(thief, event);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow reward action send threw (event {}): {}", event.getEventId(),
                    e.toString());
            sendResult = ShadowSendResult.UNKNOWN;
        }
        // A null sender result (defensive): treat as UNKNOWN — never NPE,
        // never release the reservation (8E.2.2).
        if (sendResult == null) {
            sendResult = ShadowSendResult.UNKNOWN;
        }
        switch (sendResult) {
            case UNKNOWN -> {
                // Outcome indeterminate: keep the reservation occupied to
                // prevent duplicate XP (8E.2.1 — exception must preserve
                // the reservation).
                return;
            }
            case CLEAR_FAILURE -> {
                if (store != null) {
                    releaseReservation(store, event);
                }
                return;
            }
            case SUCCESS -> {
                // fall through to commit
            }
        }
        if (store != null) {
            boolean committed;
            try {
                committed = store.commitReservation(event.getEventId());
            } catch (RuntimeException | LinkageError e) {
                committed = false;
            }
            if (!committed) {
                // Conservative: the RESERVED slot stays occupied — duplicate
                // XP is never granted even if the commit state is unknown.
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow experience quota commit failed (event {})", event.getEventId());
            }
        }
        settleEventId(event);
    }

    /** Marks the eventId in-memory (fast path). Caller: after a successful
     *  send or a COMMITTED/RECOVERY_EXISTING refusal. */
    private static void settleEventId(ShadowTheftEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.put(event.getEventId(),
                    new EventEntry(event.getThief().getUUID(), currentTick));
            pruneLocked();
        }
    }

    /** Fail-closed switch read: a throwing supplier (RuntimeException or
     *  LinkageError) yields {@code false} — never a bus escape, never an
     *  accidental grant. Throttled WARN. */
    private static boolean safeSwitch(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow reward switch read failed; rewards fail-closed: {}", e.toString());
            return false;
        }
    }

    /** Best-effort reservation release after a CLEAR send failure. Only the
     *  store's RESERVED entries are releaseable; COMMITTED/RECOVERY are
     *  refused by the store and only produce a throttled WARN. */
    private static void releaseReservation(ShadowExperienceLimitWriter store, ShadowTheftEvent event) {
        try {
            if (!store.releaseReservation(event.getEventId())) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow experience quota release failed (event {})", event.getEventId());
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow experience quota release failed (event {}): {}",
                    event.getEventId(), e.toString());
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_EVENT_IDS) {
            pruneLocked();
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        currentTick = 0;
    }

    /** Logout clears the in-memory cache entries of that thief. */
    static void onPlayerLogout(PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        UUID uuid = event.getEntity().getUUID();
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> e.getValue().thiefId().equals(uuid));
        }
    }

    /** Expiry + capacity cleanup. Caller must hold the monitor. */
    private static void pruneLocked() {
        RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue().tick() > EVENT_ID_EXPIRY_TICKS);
        while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
            Map.Entry<UUID, EventEntry> eldest = RECENT_EVENT_IDS.entrySet().iterator().next();
            RECENT_EVENT_IDS.remove(eldest.getKey());
        }
    }

    // ---- test hooks (not part of the public API) ----

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setIntegrationEnabledSupplierForTesting(BooleanSupplier supplier) {
        integrationEnabledSupplier = supplier;
    }

    static void setRewardsEnabledSupplierForTesting(BooleanSupplier supplier) {
        rewardsEnabledSupplier = supplier;
    }

    static void setMaxPerPairPerDaySupplierForTesting(LongSupplier supplier) {
        maxPerPairPerDaySupplier = supplier;
    }

    static void setDailyDateSupplierForTesting(Supplier<String> supplier) {
        dailyDateSupplier = supplier;
    }

    static void setExperienceLimitStoreFactoryForTesting(
            Function<ServerLevel, ShadowExperienceLimitWriter> factory) {
        experienceLimitStoreFactory = factory;
    }

    static void setActionSenderForTesting(ShadowTheftActionSender sender) {
        actionSender = sender;
    }

    static boolean isEventIdTracked(UUID eventId) {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.containsKey(eventId);
        }
    }

    static int trackedEventCountForTesting() {
        synchronized (RECENT_EVENT_IDS) {
            return RECENT_EVENT_IDS.size();
        }
    }

    static void resetForTesting() {
        initialized = false;
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
        rewardsEnabledSupplier = Config.SHADOW_REWARDS_ENABLED::get;
        maxPerPairPerDaySupplier = Config.SHADOW_MAX_EXPERIENCE_REWARDS_PER_PAIR_PER_DAY::get;
        dailyDateSupplier = ShadowExperienceLimitStore::today;
        experienceLimitStoreFactory = level -> ShadowExperienceLimitStore.current(level);
        actionSender = ShadowTheftSuccessActionDispatcher::sendShadowTheftSuccessAction;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        currentTick = 0;
    }
}
