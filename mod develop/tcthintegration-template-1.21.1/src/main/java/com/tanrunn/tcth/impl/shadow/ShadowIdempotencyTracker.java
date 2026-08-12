package com.tanrunn.tcth.impl.shadow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.TCTHIntegration;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Bounded, in-memory, tick-based idempotency cache for the shadow thief
 * framework (8B.1).
 *
 * <p>Owns exactly two kinds of records, separated from the gameplay
 * cooldowns/alert/victim-protection tracker
 * ({@link ShadowCooldownTracker}) so that a flood of event ids can never
 * evict safety-protection records:
 * <ul>
 *   <li>{@code EVENT_ID} — seen attempt ids (TTL 1 hour);</li>
 *   <li>{@code ATTEMPT} — thief + target + tick keys (TTL 20 ticks).</li>
 * </ul>
 *
 * <p>The durable duplicate protection lives in the audit store
 * ({@code ShadowAuditWriter#byEventId}); this in-memory cache is the fast
 * path and the crash-window backstop.
 *
 * <p>Rules: UUID keys only; bounded capacity (4096) with oldest-entry
 * eviction; tick-based expiry only; overflow-safe tick arithmetic; logout and
 * server-stop cleanup; idempotent lifecycle registration; never written to
 * player NBT.
 */
public final class ShadowIdempotencyTracker {

    /** Hard capacity of the idempotency tracker. */
    public static final int CAPACITY = 4096;
    /** TTL of attempt keys (thief + target + tick) in ticks. */
    public static final long ATTEMPT_TTL_TICKS = 20L;
    /** TTL of event-id keys in ticks (1 hour). */
    public static final long EVENT_ID_TTL_TICKS = 72_000L;

    /** Entry kind discriminator. */
    public enum Kind {
        EVENT_ID, ATTEMPT
    }

    /**
     * Cache key. The ATTEMPT kind is keyed on thief + target + serverTick so
     * that two different eventIds in the same tick are duplicates while the
     * same pair one tick later is NOT blocked by the idempotency key
     * (8B.1.1 §3).
     */
    private record Key(Kind kind, @Nullable UUID a, @Nullable UUID b, long tick) {
        private Key {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * The shared production tracker. Its lifecycle listeners are registered
     * by the mod constructor; the attempt coordinator's production default
     * wiring uses this instance.
     */
    public static final ShadowIdempotencyTracker SHARED = new ShadowIdempotencyTracker();

    private final Map<Key, Long> entries = new LinkedHashMap<>(64, 0.75f);
    private long currentTick = 0L;
    private boolean initialized = false;

    /** Idempotent registration of the tick / logout / stop lifecycle. */
    public void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] ShadowIdempotencyTracker.init called more than once; ignoring");
            return;
        }
        initialized = true;
        bus.addListener(this::onServerTick);
        bus.addListener(this::onPlayerLogout);
        bus.addListener(this::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] ShadowIdempotencyTracker initialized");
    }

    /** Advances the internal tick and sweeps expired entries. */
    public void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        sweepExpired();
    }

    /** Removes every ATTEMPT entry involving the logged-out player. */
    public void onPlayerLogout(PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            removePlayer(event.getEntity().getUUID());
        }
    }

    /** Clears everything on server stop. */
    public void onServerStopping(ServerStoppingEvent event) {
        entries.clear();
        currentTick = 0L;
    }

    /** Removes every ATTEMPT entry involving the given player. */
    public void removePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        entries.entrySet().removeIf(e -> e.getKey().kind() == Kind.ATTEMPT
                && (playerId.equals(e.getKey().a()) || playerId.equals(e.getKey().b())));
    }
    // ---- idempotency ----

    /** @return whether the eventId has already been seen */
    public boolean hasEventId(UUID eventId) {
        return isActive(Kind.EVENT_ID, eventId, null, 0L);
    }

    /** Marks the eventId as seen. */
    public void markEventId(UUID eventId) {
        put(Kind.EVENT_ID, eventId, null, 0L, EVENT_ID_TTL_TICKS);
    }

    /**
     * @param thiefId    the thief's UUID
     * @param targetId   the target's UUID
     * @param serverTick the server tick of the attempt — part of the key
     * @return whether an attempt with the same thief + target + serverTick
     *         key exists
     */
    public boolean isAttemptDuplicate(UUID thiefId, UUID targetId, long serverTick) {
        return isActive(Kind.ATTEMPT, thiefId, targetId, serverTick);
    }

    /**
     * Marks the thief + target + serverTick idempotency key.
     */
    public void markAttempt(UUID thiefId, UUID targetId, long serverTick) {
        put(Kind.ATTEMPT, thiefId, targetId, serverTick, ATTEMPT_TTL_TICKS);
    }

    // ---- internals ----

    private boolean isActive(Kind kind, UUID a, @Nullable UUID b, long tick) {
        Key key = new Key(kind, a, b, tick);
        Long expiry = entries.get(key);
        if (expiry == null) {
            return false;
        }
        if (currentTick >= expiry) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    private void put(Kind kind, UUID a, @Nullable UUID b, long tick, long durationTicks) {
        Key key = new Key(kind, a, b, tick);
        long expiry = durationTicks > Long.MAX_VALUE - currentTick
                ? Long.MAX_VALUE
                : currentTick + durationTicks;
        entries.put(key, expiry);
        // Evict the oldest entry when over capacity.
        while (entries.size() > CAPACITY) {
            Key eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    private void sweepExpired() {
        entries.entrySet().removeIf(e -> currentTick >= e.getValue());
    }

    // ---- test hooks (not part of the public API) ----

    /** @return the number of tracked entries (tests) */
    public int size() {
        return entries.size();
    }

    /** @return the internal current tick (tests) */
    public long currentTickForTesting() {
        return currentTick;
    }

    /** Resets state and un-registers the lifecycle flag (tests). */
    public void resetForTesting() {
        entries.clear();
        currentTick = 0L;
        initialized = false;
    }
}
