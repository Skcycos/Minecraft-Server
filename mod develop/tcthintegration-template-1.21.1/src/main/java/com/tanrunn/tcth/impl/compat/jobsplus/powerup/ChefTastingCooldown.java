package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Per-player anti-farm cooldown for the tasting route (phase 3D).
 *
 * <p>In-memory only: keys are player UUIDs, values are server tick counts when
 * the tasting effects were last successfully granted. Nothing is ever written
 * to player NBT or world data.
 *
 * <ul>
 *   <li>cooldown duration is configurable ({@code tastingEffectCooldownTicks},
 *       default 400 ticks = 20 s);</li>
 *   <li>all three tasting nodes share the same cooldown;</li>
 *   <li>{@link #commit} is called only after at least one effect was actually
 *       granted (success-driven commit);</li>
 *   <li>entries are removed on player logout and on server stop, so the map
 *       stays bounded by the currently-online players;</li>
 *   <li>the time source is the server tick counter (dimension-independent).</li>
 * </ul>
 */
public final class ChefTastingCooldown {

    private static final ChefTastingCooldown INSTANCE = new ChefTastingCooldown();

    private final Map<UUID, Long> lastGrantTick = new ConcurrentHashMap<>();

    /** True once {@link #registerLifecycle} has attached the listeners. */
    private volatile boolean lifecycleRegistered = false;

    /** Server tick source; production reads {@code server.getTickCount()}. */
    private LongSupplier tickSource = ChefTastingCooldown::serverTickOrZero;

    /** Cooldown ticks; production reads {@link Config#TASTING_EFFECT_COOLDOWN_TICKS}. */
    private IntSupplier cooldownTicksSupplier = () -> Config.TASTING_EFFECT_COOLDOWN_TICKS.get();

    private ChefTastingCooldown() {
    }

    public static ChefTastingCooldown instance() {
        return INSTANCE;
    }

    /**
     * Registers logout/stop cleanup on the game bus. <strong>Idempotent:</strong>
     * only the first call attaches listeners; subsequent calls are no-ops, so
     * repeated module initialization can never register duplicate handlers.
     */
    public void registerLifecycle(IEventBus gameBus) {
        if (lifecycleRegistered) {
            return;
        }
        synchronized (this) {
            if (lifecycleRegistered) {
                return;
            }
            lifecycleRegistered = true;
            gameBus.addListener(ChefTastingCooldown::onPlayerLogout);
            gameBus.addListener(ChefTastingCooldown::onServerStopping);
        }
    }

    /**
     * Whether the player is still inside the tasting cooldown window.
     */
    public boolean isOnCooldown(UUID playerId, ServerPlayer player) {
        Long last = lastGrantTick.get(playerId);
        if (last == null) {
            return false;
        }
        long now = nowTick(player);
        int cooldown = Math.max(1, cooldownTicksSupplier.getAsInt());
        return now - last < cooldown;
    }

    /**
     * Records a successful grant for the player. Called by the tasting reward
     * only after at least one status effect was actually applied.
     */
    public void commit(UUID playerId, ServerPlayer player) {
        lastGrantTick.put(playerId, nowTick(player));
    }

    /**
     * Drops the player's entry (logout / disconnect).
     */
    public void clearPlayer(UUID playerId) {
        if (playerId != null) {
            lastGrantTick.remove(playerId);
        }
    }

    /**
     * Drops all entries (server stop).
     */
    public void clearAll() {
        lastGrantTick.clear();
    }

    // ---- lifecycle listeners ----

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            INSTANCE.clearPlayer(serverPlayer.getUUID());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        INSTANCE.clearAll();
        TCTHIntegration.LOGGER.debug("[TCTH] Tasting cooldown cache cleared (server stopping)");
    }

    // ---- time source ----

    private long nowTick(ServerPlayer player) {
        try {
            if (player != null && player.serverLevel() != null && player.serverLevel().getServer() != null) {
                return player.serverLevel().getServer().getTickCount();
            }
        } catch (RuntimeException ignored) {
            // fall through to the injected/zero source
        }
        return tickSource.getAsLong();
    }

    private static long serverTickOrZero() {
        return 0L;
    }

    // ---- test hooks ----

    /**
     * Test hooks (public so unit tests in other packages can drive the
     * cooldown without a live server).
     */
    public static void setTickSourceForTesting(LongSupplier source) {
        INSTANCE.tickSource = source;
    }

    public static void setCooldownTicksForTesting(IntSupplier source) {
        INSTANCE.cooldownTicksSupplier = source;
    }

    public static Map<UUID, Long> snapshotForTesting() {
        return Map.copyOf(INSTANCE.lastGrantTick);
    }

    public static void resetForTesting() {
        INSTANCE.lastGrantTick.clear();
        INSTANCE.tickSource = ChefTastingCooldown::serverTickOrZero;
        INSTANCE.cooldownTicksSupplier = () -> Config.TASTING_EFFECT_COOLDOWN_TICKS.get();
        INSTANCE.lifecycleRegistered = false;
    }
}
