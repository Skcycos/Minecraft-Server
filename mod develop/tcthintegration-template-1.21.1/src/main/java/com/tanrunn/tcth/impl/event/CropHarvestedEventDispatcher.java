package com.tanrunn.tcth.impl.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.api.farming.HarvestMethod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Central publishing entry point for {@link CropHarvestedEvent}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>generate a fresh, stable {@link UUID} per harvest behaviour;</li>
 *   <li>check the framework master switch ({@link Config#ENABLED}) and the
 *       farming switch ({@link Config#FARMER_INTEGRATION_ENABLED});</li>
 *   <li>reject non-server contexts;</li>
 *   <li>classify {@link FakePlayer} (and its subclasses, e.g. Create's
 *       {@code DeployerFakePlayer}) as {@code automated=true} and reject by
 *       default — no real-player farming reward event is posted for them
 *       ({@link Result#AUTOMATED_REJECTED}); other non-FakePlayer bot players
 *       are a documented known boundary, not claimed to be excluded;</li>
 *   <li>bounded, expiring idempotency so one harvest posts at most once.</li>
 * </ul>
 *
 * <p>No reward, cooldown or rate-limit logic lives here — consumers keep their
 * own state keyed by {@link CropHarvestedEvent#getEventId()}.
 */
public final class CropHarvestedEventDispatcher {

    /**
     * Result of a dispatch attempt.
     */
    public enum Result {
        /** The event was generated and posted to the game bus. */
        POSTED,
        /** The framework master switch is disabled. */
        FRAMEWORK_DISABLED,
        /** The farming switch is disabled. */
        FARMING_DISABLED,
        /** Not a server-side context (or level null). */
        INVALID_CONTEXT,
        /** The event was already posted for this harvest (idempotency). */
        DUPLICATE,
        /** Automated / fake-player source; no real-player reward event posted. */
        AUTOMATED_REJECTED,
        /** The block is not a harvestable crop. */
        NOT_HARVESTABLE,
        /** The crop is not fully grown. */
        NOT_FULLY_GROWN
    }

    /** How long an event id stays in the idempotency cache (ticks). */
    static final int IDEMPOTENCY_EXPIRY_TICKS = 100;
    /** Hard cap so the cache can never grow without bound. */
    static final int MAX_TRACKED_HARVESTS = 4096;

    private static final Map<HarvestKey, Long> RECENT_HARVESTS = new LinkedHashMap<>(64, 0.75f, true);
    private static long currentTick = 0;

    private static BooleanSupplier enabledSupplier = () -> Config.ENABLED.get();
    private static BooleanSupplier farmingEnabledSupplier = () -> Config.FARMER_INTEGRATION_ENABLED.get();

    private static IEventBus gameBus;
    private static boolean initialized = false;

    private CropHarvestedEventDispatcher() {
    }

    /**
     * One-time registration of lifecycle listeners (server tick cleanup +
     * stop cleanup). Idempotent.
     */
    public static void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] CropHarvestedEventDispatcher.init called more than once; ignoring");
            return;
        }
        initialized = true;
        gameBus = bus;
        bus.addListener(CropHarvestedEventDispatcher::onServerTick);
        bus.addListener(CropHarvestedEventDispatcher::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] CropHarvestedEventDispatcher initialized");
    }

    /**
     * Publishes a crop-harvest event.
     *
     * @param player         the acting player (nullable — {@code null} is an
     *                       automated context)
     * @param cropId         block id of the crop
     * @param harvestedState the crop state at harvest time
     * @param position       crop position
     * @param level          server level
     * @param method         harvest method
     * @param fullyGrown     whether the crop was mature
     * @return the dispatch result; only {@link Result#POSTED} means the event
     *         was posted
     */
    public static Result publish(@Nullable ServerPlayer player, ResourceLocation cropId, BlockState harvestedState,
                                 BlockPos position, ServerLevel level, HarvestMethod method, boolean fullyGrown) {
        if (!enabledSupplier.getAsBoolean()) {
            return Result.FRAMEWORK_DISABLED;
        }
        if (!farmingEnabledSupplier.getAsBoolean()) {
            return Result.FARMING_DISABLED;
        }
        if (level == null || level.isClientSide()) {
            return Result.INVALID_CONTEXT;
        }
        boolean automated = player == null || player instanceof FakePlayer;
        if (automated) {
            // FakePlayer (and subclasses) or no-player contexts are classified
            // as automated and no real-player reward event is posted. Known
            // boundary: non-FakePlayer bot players are NOT claimed to be
            // excluded — no reliable public condition distinguishes them.
            return Result.AUTOMATED_REJECTED;
        }
        if (!fullyGrown) {
            // Immature crops never produce a harvest event (0 events).
            return Result.NOT_FULLY_GROWN;
        }
        long tick = currentTick;
        HarvestKey key = new HarvestKey(player.getUUID(), level.dimension().location(), position, tick, method);
        synchronized (RECENT_HARVESTS) {
            if (RECENT_HARVESTS.containsKey(key)) {
                return Result.DUPLICATE;
            }
        }
        CropHarvestedEvent event = new CropHarvestedEvent(UUID.randomUUID(), player, cropId, harvestedState,
                position, level, method, fullyGrown, false);
        IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
        bus.post(event);
        synchronized (RECENT_HARVESTS) {
            RECENT_HARVESTS.put(key, tick);
            pruneExpiredLocked(tick);
        }
        return Result.POSTED;
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        synchronized (RECENT_HARVESTS) {
            RECENT_HARVESTS.entrySet().removeIf(e -> currentTick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        synchronized (RECENT_HARVESTS) {
            RECENT_HARVESTS.clear();
        }
        currentTick = 0;
    }

    /** Capacity + expiry cleanup. Caller must hold the monitor. */
    private static void pruneExpiredLocked(long tick) {
        RECENT_HARVESTS.entrySet().removeIf(e -> tick - e.getValue() > IDEMPOTENCY_EXPIRY_TICKS);
        while (RECENT_HARVESTS.size() > MAX_TRACKED_HARVESTS) {
            Map.Entry<HarvestKey, Long> eldest = RECENT_HARVESTS.entrySet().iterator().next();
            RECENT_HARVESTS.remove(eldest.getKey());
        }
    }

    /** Idempotency key: one harvest behaviour per player/dimension/pos/tick/method. */
    record HarvestKey(UUID playerId, ResourceLocation dimension, BlockPos position, long tick, HarvestMethod method) {
    }

    private static long readCurrentTick() {
        return currentTick;
    }

    // ---- test hooks (not part of the public API) ----

    public static void setEnabledSupplierForTesting(BooleanSupplier supplier) {
        enabledSupplier = supplier;
    }

    public static void setFarmingEnabledSupplierForTesting(BooleanSupplier supplier) {
        farmingEnabledSupplier = supplier;
    }

    public static void setGameBusForTesting(IEventBus bus) {
        gameBus = bus;
    }

    public static int trackedHarvestCountForTesting() {
        synchronized (RECENT_HARVESTS) {
            return RECENT_HARVESTS.size();
        }
    }

    public static void resetForTesting() {
        initialized = false;
        enabledSupplier = () -> Config.ENABLED.get();
        farmingEnabledSupplier = () -> Config.FARMER_INTEGRATION_ENABLED.get();
        gameBus = null;
        synchronized (RECENT_HARVESTS) {
            RECENT_HARVESTS.clear();
        }
        currentTick = 0;
    }
}
