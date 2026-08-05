package com.tanrunn.tcth.impl.compat.fieldguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CookedEventIdCache} bounds and expiry.
 */
class CookedEventIdCacheTest {

    @Test
    void commitMakesIdProcessed() {
        CookedEventIdCache cache = new CookedEventIdCache();
        UUID id = UUID.randomUUID();
        assertFalse(cache.isProcessed(id));
        cache.commit(id);
        assertTrue(cache.isProcessed(id));
    }

    @Test
    void expiresAfterConfiguredTicks() {
        CookedEventIdCache cache = new CookedEventIdCache(64, 40);
        UUID id = UUID.randomUUID();
        cache.commit(id);

        for (int i = 0; i < 39; i++) {
            cache.tick();
        }
        assertTrue(cache.isProcessed(id), "id must survive 39 ticks");

        cache.tick();
        assertFalse(cache.isProcessed(id), "id must expire after the 40th tick");
    }

    @Test
    void repeatedTickAfterExpiryIsSafe() {
        CookedEventIdCache cache = new CookedEventIdCache(64, 2);
        cache.commit(UUID.randomUUID());
        for (int i = 0; i < 10; i++) {
            cache.tick();
        }
        assertEquals(0, cache.size());
    }

    @Test
    void boundedAtMaxSizeWithLruEviction() {
        int max = 4096;
        CookedEventIdCache cache = new CookedEventIdCache(max, 1000);
        for (int i = 0; i < max; i++) {
            cache.commit(new UUID(0, i));
        }
        assertEquals(max, cache.size());
        assertTrue(cache.isProcessed(new UUID(0, 0)));

        // One more pushes the eldest (0,0) out via LRU.
        cache.commit(new UUID(1, 0));
        assertEquals(max, cache.size());
        assertFalse(cache.isProcessed(new UUID(0, 0)), "eldest LRU entry must be evicted");
        assertTrue(cache.isProcessed(new UUID(1, 0)));
    }

    @Test
    void clearRemovesEverything() {
        CookedEventIdCache cache = new CookedEventIdCache();
        cache.commit(UUID.randomUUID());
        cache.commit(UUID.randomUUID());
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void constructorRejectsNonPositiveArgs() {
        assertThrows(IllegalArgumentException.class, () -> new CookedEventIdCache(0, 40));
        assertThrows(IllegalArgumentException.class, () -> new CookedEventIdCache(4096, 0));
    }

    @Test
    void defaultsAre4096And40() {
        assertEquals(4096, CookedEventIdCache.DEFAULT_MAX_SIZE);
        assertEquals(40, CookedEventIdCache.DEFAULT_TTL_TICKS);
    }
}
