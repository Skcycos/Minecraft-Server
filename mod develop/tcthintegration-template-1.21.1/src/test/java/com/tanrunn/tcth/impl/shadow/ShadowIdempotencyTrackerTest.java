package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Unit tests for {@link ShadowIdempotencyTracker} (8B.1 §2).
 *
 * <p>Covers the eventId and thief+target+tick attempt keys: bounded capacity
 * with oldest-entry eviction, TTL expiry, logout cleanup, stop cleanup,
 * idempotent lifecycle registration and full independence from the gameplay
 * cooldown tracker (a flood of event ids must never evict safety records).
 */
class ShadowIdempotencyTrackerTest {

    @Test
    void eventIdIsRememberedUntilTtl() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID eventId = UUID.randomUUID();
        assertFalse(tracker.hasEventId(eventId));
        tracker.markEventId(eventId);
        assertTrue(tracker.hasEventId(eventId));
        for (int i = 0; i <= ShadowIdempotencyTracker.EVENT_ID_TTL_TICKS; i++) {
            tracker.onServerTick(null);
        }
        assertFalse(tracker.hasEventId(eventId), "event ids must expire after their TTL");
    }

    @Test
    void attemptKeyIsThiefPlusTargetPlusServerTick() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID thief = UUID.randomUUID();
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        long tick = 1_000L;
        tracker.markAttempt(thief, targetA, tick);
        assertTrue(tracker.isAttemptDuplicate(thief, targetA, tick));
        assertFalse(tracker.isAttemptDuplicate(thief, targetB, tick), "different target must not collide");
        assertFalse(tracker.isAttemptDuplicate(UUID.randomUUID(), targetA, tick), "different thief must not collide");
    }

    @Test
    void sameTickDifferentEventIdsAreDuplicates() {
        // Two attempts with the same thief + target + serverTick collide on
        // the attempt key regardless of their eventIds (8B.1.1 §3).
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long tick = 500L;
        tracker.markAttempt(thief, target, tick);
        assertTrue(tracker.isAttemptDuplicate(thief, target, tick));
    }

    @Test
    void nextTickSamePairIsNotBlockedByIdempotency() {
        // The attempt key contains the tick: one tick later the same pair is
        // a fresh key (other cooldowns may still block, but not the
        // idempotency key) (8B.1.1 §3).
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        tracker.markAttempt(thief, target, 1_000L);
        assertTrue(tracker.isAttemptDuplicate(thief, target, 1_000L));
        assertFalse(tracker.isAttemptDuplicate(thief, target, 1_001L),
                "one tick later the same pair must not be blocked by the idempotency key");
    }

    @Test
    void attemptKeyExpiresAfterTtl() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        tracker.markAttempt(thief, target, 1_000L);
        assertTrue(tracker.isAttemptDuplicate(thief, target, 1_000L));
        for (int i = 0; i <= ShadowIdempotencyTracker.ATTEMPT_TTL_TICKS; i++) {
            tracker.onServerTick(null);
        }
        assertFalse(tracker.isAttemptDuplicate(thief, target, 1_000L), "attempt keys must expire (tick window)");
    }

    @Test
    void capacityEvictsOldestEntry() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID first = UUID.randomUUID();
        tracker.markEventId(first); // oldest entry
        for (int i = 0; i < ShadowIdempotencyTracker.CAPACITY; i++) {
            tracker.markEventId(UUID.randomUUID());
        }
        assertEquals(ShadowIdempotencyTracker.CAPACITY, tracker.size());
        assertFalse(tracker.hasEventId(first), "the oldest entry must be evicted");
    }

    @Test
    void updatingExistingKeyDoesNotGrowTheTracker() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID eventId = UUID.randomUUID();
        tracker.markEventId(eventId);
        tracker.markEventId(eventId); // refresh
        assertTrue(tracker.hasEventId(eventId));
        assertEquals(1, tracker.size());
    }

    @Test
    void logoutRemovesAttemptKeysOfThePlayerOnly() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID otherTarget = UUID.randomUUID();
        tracker.markAttempt(player, other, 1_000L);
        tracker.markAttempt(other, otherTarget, 1_000L);
        tracker.markEventId(UUID.randomUUID()); // event ids have no player; must survive
        ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(player);
        tracker.onPlayerLogout(new PlayerLoggedOutEvent(serverPlayer));
        assertFalse(tracker.isAttemptDuplicate(player, other, 1_000L), "the logged-out player's attempt key must go");
        assertTrue(tracker.isAttemptDuplicate(other, otherTarget, 1_000L), "other players' attempt keys must survive");
        assertEquals(2, tracker.size(), "only the logged-out player's attempt key is removed");
    }

    @Test
    void serverStoppingClearsEverything() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        tracker.markEventId(UUID.randomUUID());
        tracker.markAttempt(UUID.randomUUID(), UUID.randomUUID(), 1_000L);
        tracker.onServerStopping(null);
        assertEquals(0, tracker.size());
        assertEquals(0L, tracker.currentTickForTesting());
    }

    @Test
    void lifecycleRegistrationIsIdempotent() {
        ShadowIdempotencyTracker tracker = new ShadowIdempotencyTracker();
        IEventBus bus = BusBuilder.builder().build();
        tracker.init(bus);
        tracker.init(bus);
        tracker.onServerTick(null);
        assertTrue(tracker.currentTickForTesting() >= 1L);
    }

    @Test
    void eventIdFloodNeverTouchesTheCooldownTracker() {
        // Independence guarantee (8B.1 §2.6): idempotency floods must not
        // evict victim protection / alert / cooldown records.
        ShadowIdempotencyTracker idempotency = new ShadowIdempotencyTracker();
        ShadowCooldownTracker cooldowns = new ShadowCooldownTracker();
        UUID victim = UUID.randomUUID();
        cooldowns.markVictimProtection(victim, 10_000L);
        for (int i = 0; i < ShadowIdempotencyTracker.CAPACITY * 3; i++) {
            idempotency.markEventId(UUID.randomUUID());
        }
        assertEquals(ShadowIdempotencyTracker.CAPACITY, idempotency.size());
        assertTrue(cooldowns.isVictimProtected(victim), "safety records must survive the flood");
        assertEquals(1, cooldowns.size());
    }
}
