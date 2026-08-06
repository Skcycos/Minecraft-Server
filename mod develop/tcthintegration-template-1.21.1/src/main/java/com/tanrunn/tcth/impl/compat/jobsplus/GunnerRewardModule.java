package com.tanrunn.tcth.impl.compat.jobsplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.Nullable;

import com.daqem.arc.api.action.result.ActionResult;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.GunKillActionDispatcher;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Gunner job reward settlement for {@link GunKillEvent}s.
 *
 * <p><strong>Disabled by default</strong> ({@code Config.GUNNER_REWARDS_ENABLED}
 * = false) — enable only after live verification.
 *
 * <p>Settlement order:
 * <ol>
 *   <li>framework + gunner reward switches;</li>
 *   <li>actor player exists and event is not automated;</li>
 *   <li>eventId idempotency (bounded, expiring cache);</li>
 *   <li>per-player per-tick rate limit;</li>
 *   <li>BOSS-tier cooldown (prevents boss-respawn farming);</li>
 *   <li>send the {@code tcth:on_gun_kill} Arc action — idempotency, rate limit
 *       and BOSS cooldown are only recorded <em>after</em> a successful send,
 *       so a failed send can be retried.</li>
 * </ol>
 *
 * <p>An exception in this module never breaks the server tick.
 */
public final class GunnerRewardModule {

    private static final int EVENT_ID_EXPIRY_TICKS = 40;
    private static final int MAX_TRACKED_EVENT_IDS = 4096;

    static final int EVENT_ID_EXPIRY_TICKS_FOR_TESTING = EVENT_ID_EXPIRY_TICKS;
    static final int MAX_TRACKED_EVENT_IDS_FOR_TESTING = MAX_TRACKED_EVENT_IDS;

    private static final Map<UUID, Long> RECENT_EVENT_IDS = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<UUID, Integer> ACTIONS_THIS_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BOSS_KILL_TICKS = new ConcurrentHashMap<>();
    private static long currentTick = 0;

    private static boolean initialized = false;

    private static BooleanSupplier rewardsEnabledSupplier = () -> Config.GUNNER_REWARDS_ENABLED.get();
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    private static IntSupplier maxActionsPerTickSupplier = () -> Config.MAX_GUN_KILL_ACTIONS_PER_TICK.get();
    private static IntSupplier bossCooldownSupplier = () -> Config.GUNNER_BOSS_COOLDOWN_TICKS.get();

    interface GunnerActionSender {
        @Nullable
        ActionResult send(ServerPlayer player, GunKillEvent event);
    }

    private static GunnerActionSender actionSender = GunKillActionDispatcher::sendGunKillAction;

    private GunnerRewardModule() {
    }

    /**
     * Registers the module on the game bus. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] Gunner reward module init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus.addListener(GunnerRewardModule::onGunKill);
        gameBus.addListener(GunnerRewardModule::onServerTick);
        gameBus.addListener(GunnerRewardModule::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] Gunner reward module registered (disabled by default)");
    }

    static void onGunKill(GunKillEvent event) {
        try {
            settle(event);
        } catch (Exception e) {
            // A single module failure must never break the server tick.
            TCTHIntegration.LOGGER.error("[TCTH] Gunner reward settlement failed for {}: {}",
                    event.getEventId(), e.toString());
        }
    }

    private static void settle(GunKillEvent event) {
        if (!frameworkEnabledSupplier.getAsBoolean() || !rewardsEnabledSupplier.getAsBoolean()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (event.isAutomated()) {
            return; // defence in depth: dispatcher already rejects automated sources
        }
        synchronized (RECENT_EVENT_IDS) {
            if (RECENT_EVENT_IDS.containsKey(event.getEventId())) {
                return;
            }
        }
        int actions = ACTIONS_THIS_TICK.merge(player.getUUID(), 0, Integer::sum);
        if (actions >= maxActionsPerTickSupplier.getAsInt()) {
            TCTHIntegration.LOGGER.debug("[TCTH] Gun-kill action for {} dropped (rate limit for {})",
                    event.getEventId(), player.getGameProfile().getName());
            return;
        }
        // BOSS-tier cooldown check
        if (event.getTargetTier() == GunTargetTier.BOSS) {
            long cd = bossCooldownSupplier.getAsInt();
            if (cd > 0) {
                Long lastBoss = BOSS_KILL_TICKS.get(player.getUUID());
                if (lastBoss != null && currentTick - lastBoss < cd) {
                    TCTHIntegration.LOGGER.debug("[TCTH] BOSS gun-kill for {} dropped (cooldown)",
                            player.getGameProfile().getName());
                    return;
                }
            }
        }
        // Only a successful send consumes the eventId, the rate-limit slot, and the BOSS cooldown.
        ActionResult result = actionSender.send(player, event);
        if (result == null) {
            return;
        }
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.put(event.getEventId(), currentTick);
            pruneExpiredLocked();
        }
        ACTIONS_THIS_TICK.put(player.getUUID(), actions + 1);
        if (event.getTargetTier() == GunTargetTier.BOSS) {
            BOSS_KILL_TICKS.put(player.getUUID(), currentTick);
        }
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        }
        ACTIONS_THIS_TICK.clear();
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        BOSS_KILL_TICKS.clear();
        currentTick = 0;
    }

    /** Expiry + capacity cleanup. Caller must hold the monitor. */
    private static void pruneExpiredLocked() {
        RECENT_EVENT_IDS.entrySet().removeIf(e -> currentTick - e.getValue() > EVENT_ID_EXPIRY_TICKS);
        while (RECENT_EVENT_IDS.size() > MAX_TRACKED_EVENT_IDS) {
            Map.Entry<UUID, Long> eldest = RECENT_EVENT_IDS.entrySet().iterator().next();
            RECENT_EVENT_IDS.remove(eldest.getKey());
        }
    }

    // ---- test hooks (same package / for tests) ----

    static void setRewardsEnabledSupplierForTesting(BooleanSupplier supplier) {
        rewardsEnabledSupplier = supplier;
    }

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setMaxActionsPerTickSupplierForTesting(IntSupplier supplier) {
        maxActionsPerTickSupplier = supplier;
    }

    static void setBossCooldownSupplierForTesting(IntSupplier supplier) {
        bossCooldownSupplier = supplier;
    }

    static void setActionSenderForTesting(GunnerActionSender sender) {
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
        rewardsEnabledSupplier = () -> Config.GUNNER_REWARDS_ENABLED.get();
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        maxActionsPerTickSupplier = () -> Config.MAX_GUN_KILL_ACTIONS_PER_TICK.get();
        bossCooldownSupplier = () -> Config.GUNNER_BOSS_COOLDOWN_TICKS.get();
        actionSender = GunKillActionDispatcher::sendGunKillAction;
        synchronized (RECENT_EVENT_IDS) {
            RECENT_EVENT_IDS.clear();
        }
        ACTIONS_THIS_TICK.clear();
        BOSS_KILL_TICKS.clear();
        currentTick = 0;
    }
}
