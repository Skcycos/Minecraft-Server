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
 * Unit tests for {@link ShadowCooldownTracker} (8B.1).
 *
 * <p>Covers the gameplay-safety records only: bounded capacity with
 * oldest-entry eviction, tick expiry, logout cleanup, stop cleanup, idempotent
 * lifecycle registration, old/new player isolation and overflow-safe
 * durations. Idempotency records (eventId / attempt) are covered by
 * {@link ShadowIdempotencyTrackerTest}.
 */
class ShadowCooldownTrackerTest {

    @Test
    void globalCooldownExpiresAfterTicks() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID thief = UUID.randomUUID();
        assertFalse(tracker.isGlobalCooldownActive(thief));
        tracker.markGlobalCooldown(thief, 10L);
        assertTrue(tracker.isGlobalCooldownActive(thief));
        for (int i = 0; i < 11; i++) {
            tracker.onServerTick(null);
        }
        assertFalse(tracker.isGlobalCooldownActive(thief));
    }

    @Test
    void noCandidateAndFailureCooldownsAreIndependent() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID thief = UUID.randomUUID();
        tracker.markNoCandidateCooldown(thief, 5L);
        assertTrue(tracker.isNoCandidateCooldownActive(thief));
        assertFalse(tracker.isFailureCooldownActive(thief));
        tracker.markFailureCooldown(thief, 50L);
        assertTrue(tracker.isFailureCooldownActive(thief));
    }

    @Test
    void victimProtectionAndAlertAreKeyedByTarget() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID victim = UUID.randomUUID();
        tracker.markVictimProtection(victim, 100L);
        assertTrue(tracker.isVictimProtected(victim));
        tracker.markAlert(victim, 100L);
        assertTrue(tracker.isAlerted(victim));
    }

    @Test
    void capacityEvictsOldestEntry() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID first = UUID.randomUUID();
        tracker.markAlert(first, 1_000L); // oldest entry
        for (int i = 0; i < ShadowCooldownTracker.CAPACITY; i++) {
            tracker.markAlert(UUID.randomUUID(), 1_000L);
        }
        assertEquals(ShadowCooldownTracker.CAPACITY, tracker.size());
        assertFalse(tracker.isAlerted(first), "the oldest entry must be evicted");
    }

    @Test
    void updatingExistingKeyDoesNotGrowTheTracker() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID thief = UUID.randomUUID();
        tracker.markGlobalCooldown(thief, 10L);
        tracker.markGlobalCooldown(thief, 200L); // refresh
        assertTrue(tracker.isGlobalCooldownActive(thief));
        for (int i = 0; i < 15; i++) {
            tracker.onServerTick(null);
        }
        assertTrue(tracker.isGlobalCooldownActive(thief), "the refreshed duration must win");
    }

    @Test
    void logoutRemovesAllEntriesOfThePlayer() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        tracker.markGlobalCooldown(player, 100L);
        tracker.markAlert(other, 100L);
        tracker.markAlert(UUID.randomUUID(), 100L); // unrelated entry must survive
        int before = tracker.size();
        assertTrue(before > 1);
        ServerPlayer serverPlayer = mock(ServerPlayer.class);
        when(serverPlayer.getUUID()).thenReturn(player);
        tracker.onPlayerLogout(new PlayerLoggedOutEvent(serverPlayer));
        assertFalse(tracker.isGlobalCooldownActive(player));
        assertTrue(tracker.isAlerted(other), "other players' entries must survive");
    }

    @Test
    void serverStoppingClearsEverything() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        tracker.markGlobalCooldown(UUID.randomUUID(), 100L);
        tracker.markAlert(UUID.randomUUID(), 100L);
        tracker.onServerStopping(null);
        assertEquals(0, tracker.size());
        assertEquals(0L, tracker.currentTickForTesting());
    }

    @Test
    void zeroDurationMarksAreNoOps() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID thief = UUID.randomUUID();
        tracker.markGlobalCooldown(thief, 0L);
        assertFalse(tracker.isGlobalCooldownActive(thief));
        assertEquals(0, tracker.size());
    }

    @Test
    void overflowingDurationsSaturateToNeverExpiring() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID thief = UUID.randomUUID();
        tracker.markGlobalCooldown(thief, Long.MAX_VALUE - 1L);
        assertTrue(tracker.isGlobalCooldownActive(thief));
        tracker.onServerTick(null);
        assertTrue(tracker.isGlobalCooldownActive(thief), "saturated durations must not overflow");
    }

    @Test
    void lifecycleRegistrationIsIdempotent() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        IEventBus bus = BusBuilder.builder().build();
        tracker.init(bus);
        tracker.init(bus); // second call must not fail or duplicate
        tracker.onServerTick(null);
        assertTrue(tracker.currentTickForTesting() >= 1L);
    }

    @Test
    void oldAndNewPlayersAreIsolated() {
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        UUID oldPlayer = UUID.randomUUID();
        UUID newPlayer = UUID.randomUUID();
        tracker.markGlobalCooldown(oldPlayer, 100L);
        tracker.markGlobalCooldown(newPlayer, 100L);
        tracker.markAlert(oldPlayer, 100L);
        tracker.removePlayer(oldPlayer);
        assertFalse(tracker.isGlobalCooldownActive(oldPlayer));
        assertTrue(tracker.isGlobalCooldownActive(newPlayer), "the new player must keep its state");
        assertFalse(tracker.isAlerted(oldPlayer));
    }

    @Test
    void safetyRecordsAreNeverEvictedByIdempotencyFloods() {
        // The cooldown tracker is a separate cache from the idempotency
        // tracker (8B.1 §2.6): flooding the idempotency tracker with event
        // ids must not evict victim protection / alert / cooldown records.
        ShadowCooldownTracker tracker = new ShadowCooldownTracker();
        ShadowIdempotencyTracker idempotency = new ShadowIdempotencyTracker();
        UUID victim = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        tracker.markVictimProtection(victim, 1_000L);
        tracker.markAlert(victim, 1_000L);
        tracker.markGlobalCooldown(thief, 1_000L);
        for (int i = 0; i < ShadowIdempotencyTracker.CAPACITY * 2; i++) {
            idempotency.markEventId(UUID.randomUUID());
        }
        assertTrue(tracker.isVictimProtected(victim), "victim protection must survive the flood");
        assertTrue(tracker.isAlerted(victim), "the alert window must survive the flood");
        assertTrue(tracker.isGlobalCooldownActive(thief), "the global cooldown must survive the flood");
        assertEquals(3, tracker.size());
    }
}
