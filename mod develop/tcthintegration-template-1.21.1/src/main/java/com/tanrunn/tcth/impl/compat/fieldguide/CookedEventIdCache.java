package com.tanrunn.tcth.impl.compat.fieldguide;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, tick-based idempotency cache for {@code DishCookedEvent} event ids.
 *
 * <p>Guarantees that the same cooked dish (identified by its unique event id)
 * is only ever unlocked once per session, even if the event is re-delivered or
 * observed by multiple code paths.
 *
 * <p>Semantics (see {@link #commit}):
 * <ul>
 *   <li>an event id is <em>committed</em> only after the unlock succeeded or
 *       the entry was confirmed already unlocked — never on a failed unlock;</li>
 *   <li>a committed id expires after {@code ttlTicks} server ticks (≈40), so a
 *       genuinely new dish with the same id (impossible by construction, but
 *       defensive) is not blocked forever;</li>
 *   <li>the cache is bounded at {@code maxSize} entries (4096) with LRU
 *       eviction;</li>
 *   <li>{@link #clear()} on server stopping removes all in-memory state.</li>
 * </ul>
 *
 * <p>All methods are thread-safe; production callers run on the server thread.
 */
final class CookedEventIdCache {

    /** Default bound: at most 4096 in-flight ids. */
    static final int DEFAULT_MAX_SIZE = 4096;
    /** Default TTL: ~40 server ticks (2 seconds). */
    static final int DEFAULT_TTL_TICKS = 40;

    private final int maxSize;
    private final int ttlTicks;

    /** event id -> remaining ticks before expiry. Access-ordered (LRU). */
    private final Map<UUID, Integer> remaining = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
            return size() > maxSize;
        }
    };

    CookedEventIdCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_TICKS);
    }

    CookedEventIdCache(int maxSize, int ttlTicks) {
        if (maxSize <= 0 || ttlTicks <= 0) {
            throw new IllegalArgumentException("maxSize and ttlTicks must be positive");
        }
        this.maxSize = maxSize;
        this.ttlTicks = ttlTicks;
    }

    /**
     * @return {@code true} if the event id has been committed (already
     *         unlocked or confirmed unlocked) and has not expired yet
     */
    synchronized boolean isProcessed(UUID eventId) {
        return remaining.containsKey(eventId);
    }

    /**
     * Records that the event id has been fully handled. Must only be called
     * after the unlock succeeded or the entry was confirmed already unlocked.
     */
    synchronized void commit(UUID eventId) {
        remaining.put(eventId, ttlTicks);
    }

    /**
     * Advances the clock one server tick: decrements every live entry's
     * remaining ticks and drops expired ones.
     */
    synchronized void tick() {
        remaining.replaceAll((id, ticks) -> ticks - 1);
        remaining.entrySet().removeIf(e -> e.getValue() <= 0);
    }

    /** Removes all entries (server stopping). */
    synchronized void clear() {
        remaining.clear();
    }

    /** Current live entry count (test/inspection). */
    synchronized int size() {
        return remaining.size();
    }
}
