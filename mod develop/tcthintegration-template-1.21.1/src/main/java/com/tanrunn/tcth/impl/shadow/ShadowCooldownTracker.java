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
 * Bounded, in-memory, tick-based gameplay cooldowns, alert windows and victim
 * protection for the shadow thief framework (8B.1).
 *
 * <p>This tracker deliberately owns <em>only</em> the short-lived safety
 * records: GLOBAL_COOLDOWN, NO_CANDIDATE_COOLDOWN, FAILURE_COOLDOWN,
 * VICTIM_PROTECTION and ALERT. The idempotency records (eventId / attempt
 * keys) live in the separate {@link ShadowIdempotencyTracker} so that a flood
 * of event ids can never evict safety-protection records (8B.1 §2.6).
 *
 * <p>Rules:
 * <ul>
 *   <li>all keys use {@link UUID}s, never player names;</li>
 *   <li>capacity is bounded (default 1024 entries); when a new key exceeds
 *       the cap the oldest entry is evicted; updating an existing key keeps
 *       its position and simply refreshes the value;</li>
 *   <li>expiry is computed in server ticks only — never wall-clock;</li>
 *   <li>all tick arithmetic is overflow-safe (a duration that would overflow
 *       saturates at {@link Long#MAX_VALUE});</li>
 *   <li>logout removes every entry of that player (thief or victim); server
 *       stopping clears everything; lifecycle registration is idempotent;</li>
 *   <li>nothing here is ever written to player NBT.</li>
 * </ul>
 */
public final class ShadowCooldownTracker {

    /** Hard capacity of the cooldown tracker. */
    public static final int CAPACITY = 1024;

    /** Entry kind discriminator. */
    public enum Kind {
        GLOBAL_COOLDOWN, NO_CANDIDATE_COOLDOWN, FAILURE_COOLDOWN, VICTIM_PROTECTION, ALERT
    }

    private record Key(Kind kind, UUID a, @Nullable UUID b) {
        private Key {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private static final long NEVER_EXPIRES = Long.MAX_VALUE;

    /**
     * The shared production tracker. Its lifecycle listeners are registered
     * by the mod constructor; the attempt coordinator's production default
     * wiring uses this instance.
     */
    public static final ShadowCooldownTracker SHARED = new ShadowCooldownTracker();

    private final Map<Key, Long> entries = new LinkedHashMap<>(64, 0.75f);
    private long currentTick = 0L;
    private boolean initialized = false;

    /** Idempotent registration of the tick / logout / stop lifecycle. */
    public void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] ShadowCooldownTracker.init called more than once; ignoring");
            return;
        }
        initialized = true;
        bus.addListener(this::onServerTick);
        bus.addListener(this::onPlayerLogout);
        bus.addListener(this::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] ShadowCooldownTracker initialized");
    }

    /** Advances the internal tick and sweeps expired entries. */
    public void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        sweepExpired();
    }

    /** Removes every entry involving the logged-out player. */
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

    /** Removes every entry involving the given player. */
    public void removePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        entries.entrySet().removeIf(e -> playerId.equals(e.getKey().a()) || playerId.equals(e.getKey().b()));
    }

    // ---- cooldowns / alert ----

    /** @return whether the thief's global action cooldown is active */
    public boolean isGlobalCooldownActive(UUID thiefId) {
        return isActive(Kind.GLOBAL_COOLDOWN, thiefId, null);
    }

    /** Marks the thief's global action cooldown for {@code durationTicks}. */
    public void markGlobalCooldown(UUID thiefId, long durationTicks) {
        put(Kind.GLOBAL_COOLDOWN, thiefId, null, durationTicks);
    }

    /** @return whether the thief's no-candidate short cooldown is active */
    public boolean isNoCandidateCooldownActive(UUID thiefId) {
        return isActive(Kind.NO_CANDIDATE_COOLDOWN, thiefId, null);
    }

    /** Marks the thief's no-candidate short cooldown. */
    public void markNoCandidateCooldown(UUID thiefId, long durationTicks) {
        put(Kind.NO_CANDIDATE_COOLDOWN, thiefId, null, durationTicks);
    }

    /** @return whether the thief's failure cooldown is active */
    public boolean isFailureCooldownActive(UUID thiefId) {
        return isActive(Kind.FAILURE_COOLDOWN, thiefId, null);
    }

    /** Marks the thief's failure cooldown. */
    public void markFailureCooldown(UUID thiefId, long durationTicks) {
        put(Kind.FAILURE_COOLDOWN, thiefId, null, durationTicks);
    }

    /** @return whether the victim is under post-success protection */
    public boolean isVictimProtected(UUID victimId) {
        return isActive(Kind.VICTIM_PROTECTION, victimId, null);
    }

    /** Marks the victim protection window after a successful theft. */
    public void markVictimProtection(UUID victimId, long durationTicks) {
        put(Kind.VICTIM_PROTECTION, victimId, null, durationTicks);
    }

    /** @return whether the target is in an alert window */
    public boolean isAlerted(UUID targetId) {
        return isActive(Kind.ALERT, targetId, null);
    }

    /** Marks the target's alert window. */
    public void markAlert(UUID targetId, long durationTicks) {
        put(Kind.ALERT, targetId, null, durationTicks);
    }

    // ---- internals ----

    private boolean isActive(Kind kind, UUID a, @Nullable UUID b) {
        Key key = new Key(kind, a, b);
        Long expiry = entries.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry != NEVER_EXPIRES && currentTick >= expiry) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    private void put(Kind kind, UUID a, @Nullable UUID b, long durationTicks) {
        if (durationTicks <= 0 && durationTicks != NEVER_EXPIRES) {
            return; // zero-duration marks are no-ops
        }
        Key key = new Key(kind, a, b);
        long expiry = durationTicks == NEVER_EXPIRES
                ? NEVER_EXPIRES
                : (durationTicks > Long.MAX_VALUE - currentTick ? NEVER_EXPIRES : currentTick + durationTicks);
        entries.put(key, expiry);
        // Evict the oldest entry when over capacity.
        while (entries.size() > CAPACITY) {
            Key eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    private void sweepExpired() {
        entries.entrySet().removeIf(e -> e.getValue() != NEVER_EXPIRES && currentTick >= e.getValue());
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
