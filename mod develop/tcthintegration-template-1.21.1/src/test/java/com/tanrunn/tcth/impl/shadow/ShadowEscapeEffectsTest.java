package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;

/**
 * Tests for {@link ShadowEscapeEffects} (phase 8E/8E.2.2): the SUCCESS
 * effect packages per tier, the TCTH-invisibility marker (bounded, in-memory,
 * logout/stop cleanup), the deferred tick-based reconciliation (events only
 * mark pending; the real effect slot is read on the next tick), the attack/
 * new-attempt break with signature verification, and the never-delete-
 * external-effects rule.
 */
class ShadowEscapeEffectsTest {

    private static final long GAME_TIME = 1000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        ShadowEscapeEffects.resetForTesting();
    }

    private ServerPlayer player(long gameTime) {
        ServerPlayer p = mock(ServerPlayer.class);
        UUID uuid = UUID.randomUUID();
        when(p.getUUID()).thenReturn(uuid);
        when(p.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(uuid, "thief"));
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(gameTime);
        when(p.level()).thenReturn(level);
        return p;
    }

    private void grantInvisibility(ServerPlayer p, int ticks, long gameTime) {
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
    }

    private ShadowAbilitySnapshot snapshot(ShadowAbilityTier escapeTier) {
        return new ShadowAbilitySnapshot(ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                ShadowAbilityTier.NONE, escapeTier);
    }

    /** Registers a player lookup so reconciliation can find the mock. */
    private void setUpLookup(ServerPlayer p) {
        UUID uuid = p.getUUID();
        ShadowEscapeEffects.setPlayerLookupForTesting(u -> u.equals(uuid) ? p : null);
    }

    // ---- tier packages ----

    @Test
    void noneGrantsNothing() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.NONE));
        verify(p, never()).addEffect(any(MobEffectInstance.class));
        assertEquals(0, ShadowEscapeEffects.markCount());
    }

    @Test
    void tierIgrantsSpeedIOnly() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.I));
        verify(p).addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 80, 0, false, true, true));
        assertEquals(0, ShadowEscapeEffects.markCount());
    }

    @Test
    void tierIIgrantsSpeedIAndInvisibilityWithMarker() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        verify(p).addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 120, 0, false, true, true));
        verify(p).addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, true, true));
        assertEquals(1, ShadowEscapeEffects.markCount());
        assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()));
        ShadowEscapeEffects.Mark mark = ShadowEscapeEffects.markForTesting(p.getUUID());
        assertEquals(0, mark.amplifier());
        assertEquals(40, mark.grantedDuration());
        assertEquals(GAME_TIME, mark.grantedAt());
        assertEquals(GAME_TIME + 40, mark.expiry());
    }

    @Test
    void tierIIIgrantsSpeedIIAndLongerInvisibility() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.III));
        verify(p).addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 160, 1, false, true, true));
        verify(p).addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 80, 0, false, true, true));
        assertEquals(1, ShadowEscapeEffects.markCount());
    }

    @Test
    void rejectedGrantRecordsNoMarker() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(false);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        assertEquals(0, ShadowEscapeEffects.markCount());
    }

    @Test
    void grantFailureNeverThrows() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenThrow(new IllegalStateException("boom"));
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.III));
        assertEquals(0, ShadowEscapeEffects.markCount());
    }

    // ---- break on attack / new attempt (direct slot check, no reconciliation) ----

    @Test
    void attackBreaksTcthInvisibility() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true));
        when(p.removeEffect(MobEffects.INVISIBILITY)).thenReturn(true);
        ShadowEscapeEffects.onAttack(new AttackEntityEvent(p, mock(Entity.class)));
        verify(p).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void clientSideAttackNeverBreaks() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().isClientSide()).thenReturn(true);
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true));
        ShadowEscapeEffects.onAttack(new AttackEntityEvent(p, mock(Entity.class)));
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void newAttemptBreaksTcthInvisibility() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true));
        when(p.removeEffect(MobEffects.INVISIBILITY)).thenReturn(true);
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void longerExternalInvisibilityIsNeverDeleted() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0, false, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()), "marker cleared, external effect kept");
    }

    @Test
    void ambientReplacementIsNeverDeleted() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, true, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void expiredMarkerSignatureMismatchClearsMarker() {
        // Past expiry: naturalRemaining=0, current duration=1 → doesn't
        // match → marker cleared (effect is not provably ours).
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 41);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 1, 0, false, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void noMarkMeansNoRemoval() {
        ServerPlayer p = player(GAME_TIME);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0, false, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
    }

    @Test
    void shorterExternalInvisibilityIsNeverDeleted() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 10, 0, false, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void differentAmplifierOrFlagsExternalInvisibilityIsNeverDeleted() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 1, false, true, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        ShadowEscapeEffects.resetForTesting();
        p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, false, true));
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
    }

    @Test
    void naturalDecayWindowBoundary() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 29, 0, false, true, true));
        when(p.removeEffect(MobEffects.INVISIBILITY)).thenReturn(true);
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p).removeEffect(MobEffects.INVISIBILITY);
    }

    // ---- 8E.2.2: deferred reconciliation via events ----

    private ServerPlayer grantedPlayer() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        return p;
    }

    private static MobEffectEvent.Added addedEvent(ServerPlayer p, MobEffectInstance replaced,
                                                   MobEffectInstance incoming) {
        return new MobEffectEvent.Added(p, replaced, incoming, mock(Entity.class));
    }

    @Test
    void addedEventMarksForReconciliation() {
        ServerPlayer p = grantedPlayer();
        MobEffectInstance external = new MobEffectInstance(MobEffects.INVISIBILITY, 200, 0, false, true, true);
        ShadowEscapeEffects.onEffectAdded(addedEvent(p, null, external));
        assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                "Added event must mark the player for reconciliation");
        // Marker is NOT cleared until reconciliation.
        assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void externalReplacementClearsMarkerAfterReconcile() {
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
            // External add replaces our owned 30-remaining with a longer 200.
            MobEffectInstance ours = new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true);
            MobEffectInstance external = new MobEffectInstance(MobEffects.INVISIBILITY, 200, 0, false, true, true);
            ShadowEscapeEffects.onEffectAdded(addedEvent(p, ours, external));
            assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
            // After the event, the slot holds the external effect.
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(external);
            ShadowEscapeEffects.flushPendingForTesting();
            assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "reconcile: external 200 > naturalRemaining 30 → marker cleared");
            assertFalse(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
            // breakOnNewAttempt sees no marker → doesn't delete external.
            ShadowEscapeEffects.breakOnNewAttempt(p);
            verify(p, never()).removeEffect(MobEffects.INVISIBILITY);
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void externalAddWithoutMarkerIsIgnored() {
        ServerPlayer p = player(GAME_TIME);
        MobEffectInstance external = new MobEffectInstance(MobEffects.INVISIBILITY, 100, 0, false, true, true);
        ShadowEscapeEffects.onEffectAdded(addedEvent(p, null, external));
        assertEquals(0, ShadowEscapeEffects.markCount());
        assertFalse(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
    }

    @Test
    void ownGrantGuardProtectsFromReconciliation() {
        ServerPlayer p = grantedPlayer();
        ShadowEscapeEffects.setGrantGuardForTesting(p.getUUID(), true);
        try {
            MobEffectInstance ours = new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true);
            MobEffectInstance regrant = new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, true, true);
            ShadowEscapeEffects.onEffectAdded(addedEvent(p, ours, regrant));
            assertFalse(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                    "guard prevents marking for reconciliation");
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "the guard keeps the marker during our own grant");
        } finally {
            ShadowEscapeEffects.setGrantGuardForTesting(p.getUUID(), false);
        }
    }

    @Test
    void removeEventMarksForReconciliation() {
        ServerPlayer p = grantedPlayer();
        ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                MobEffects.INVISIBILITY, null));
        assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                "Remove event must mark for reconciliation");
    }

    @Test
    void externalRemovalClearsMarkerAfterReconcile() {
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                    MobEffects.INVISIBILITY, null));
            assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
            // After removal, the effect is gone.
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(null);
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 5);
            ShadowEscapeEffects.flushPendingForTesting();
            assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "reconcile: effect gone → marker cleared");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void nonInvisibilityRemovalNeverMarked() {
        ServerPlayer p = grantedPlayer();
        ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                MobEffects.REGENERATION, null));
        assertFalse(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                "non-invisibility removal must not mark for reconciliation");
        assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void expiredEventMarksForReconciliation() {
        ServerPlayer p = grantedPlayer();
        ShadowEscapeEffects.onEffectExpired(new MobEffectEvent.Expired(p,
                new MobEffectInstance(MobEffects.INVISIBILITY, 0, 0, false, true, true)));
        assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                "Expired event must mark for reconciliation");
    }

    @Test
    void expiredClearsMarkerAfterReconcile() {
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            ShadowEscapeEffects.onEffectExpired(new MobEffectEvent.Expired(p,
                    new MobEffectInstance(MobEffects.INVISIBILITY, 0, 0, false, true, true)));
            // After expiry, the effect is gone.
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(null);
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 41);
            ShadowEscapeEffects.flushPendingForTesting();
            assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "reconcile: effect gone after expiry → marker cleared");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void cancelledRemovePreservesMarkerAfterReconcile() {
        // 8E.2.2: cancelled Remove → effect stays → reconcile reads the
        // real slot, sees the effect still matches → keeps marker.
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            MobEffectEvent.Remove removeEvent = new MobEffectEvent.Remove(p,
                    MobEffects.INVISIBILITY, null);
            removeEvent.setCanceled(true);
            ShadowEscapeEffects.onEffectRemoved(removeEvent);
            assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
            // Effect still present and matches signature (not actually removed).
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 5);
            when(p.getEffect(MobEffects.INVISIBILITY))
                    .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 35, 0, false, true, true));
            ShadowEscapeEffects.flushPendingForTesting();
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "cancelled Remove: effect still matches → marker preserved");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void weakAddedPreservesMarkerAfterReconcile() {
        // 8E.2.2: weak add (addEffect returns false, slot unchanged).
        // The Added event marks for reconciliation. After reconcile, the
        // real slot still holds our effect → marker preserved.
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 5);
            MobEffectInstance weakIncoming = new MobEffectInstance(
                    MobEffects.INVISIBILITY, 10, 0, false, true, true);
            ShadowEscapeEffects.onEffectAdded(addedEvent(p, null, weakIncoming));
            assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
            // Slot unchanged: still our 35-remaining invisibility.
            when(p.getEffect(MobEffects.INVISIBILITY))
                    .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 35, 0, false, true, true));
            ShadowEscapeEffects.flushPendingForTesting();
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "weak Added: slot unchanged, still matches → marker preserved");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void trueReplacementClearsMarkerAfterReconcile() {
        // When the Added event fires with replaced=our effect, and after
        // the replacement the slot holds a DIFFERENT (longer) effect,
        // reconcile should clear the marker.
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
            MobEffectInstance ours = new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true);
            MobEffectInstance longer = new MobEffectInstance(MobEffects.INVISIBILITY, 50, 0, false, true, true);
            ShadowEscapeEffects.onEffectAdded(addedEvent(p, ours, longer));
            // After replacement, slot holds the longer effect.
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(longer);
            ShadowEscapeEffects.flushPendingForTesting();
            assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "reconcile: 50 > naturalRemaining 30 → external → marker cleared");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    /** Known limitation: a byte-identical external replacement is
     *  indistinguishable from our own effect by the signature check.
     *  Reconcile treats it as ours; the marker stays. */
    @Test
    void byteIdenticalExternalReplacementIsTreatedAsOurs() {
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
            MobEffectInstance ours = new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true);
            MobEffectInstance identical = new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true);
            ShadowEscapeEffects.onEffectAdded(addedEvent(p, ours, identical));
            // After replacement, slot holds the byte-identical effect.
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(identical);
            ShadowEscapeEffects.flushPendingForTesting();
            // Signature matches (byte-identical) → marker stays.
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "known limitation: byte-identical replacement treated as ours");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    @Test
    void reconcileWithPlayerOfflineKeepsMarker() {
        // If the player is offline (lookup returns null), the marker stays
        // (conservative; cleaned on logout).
        ServerPlayer p = grantedPlayer();
        // Don't set up lookup → resolvePlayer returns null.
        ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                MobEffects.INVISIBILITY, null));
        ShadowEscapeEffects.flushPendingForTesting();
        assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                "offline player: marker stays (cleaned on logout)");
    }

    @Test
    void reconcileWithEffectGoneClearsMarker() {
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                    MobEffects.INVISIBILITY, null));
            when(p.getEffect(MobEffects.INVISIBILITY)).thenReturn(null);
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 5);
            ShadowEscapeEffects.flushPendingForTesting();
            assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "reconcile: effect gone → marker cleared");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    // ---- break path edge cases (8E.2.2) ----

    @Test
    void removeEffectFalsePreservesTheMarker() {
        ServerPlayer p = grantedPlayer();
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 10);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 30, 0, false, true, true));
        ShadowEscapeEffects.setRemoveEffectFailsForTesting(true);
        try {
            ShadowEscapeEffects.breakOnNewAttempt(p);
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "removeEffect=false must preserve the marker");
        } finally {
            ShadowEscapeEffects.setRemoveEffectFailsForTesting(false);
        }
        when(p.removeEffect(MobEffects.INVISIBILITY)).thenReturn(true);
        ShadowEscapeEffects.breakOnNewAttempt(p);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                "a successful removeEffect must clear the marker");
    }

    @Test
    void breakPastExpiryWithEffectPresentAttemptsRemoval() {
        // Past expiry but effect is still in the slot with duration=0
        // (cancelled expiry or tick boundary). The signature matches
        // (naturalRemaining=0, duration=0) → removal attempted.
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        when(p.level().getGameTime()).thenReturn(GAME_TIME + 41);
        when(p.getEffect(MobEffects.INVISIBILITY))
                .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 0, 0, false, true, true));
        when(p.removeEffect(MobEffects.INVISIBILITY)).thenReturn(true);
        ShadowEscapeEffects.breakOnNewAttempt(p);
        verify(p).removeEffect(MobEffects.INVISIBILITY);
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()));
    }

    @Test
    void cancelledExpiredPreservesMarkerAfterReconcile() {
        // 8E.2.3: Expired implements ICancellableEvent in NeoForge 21.1.247.
        // If cancelled, the effect stays in the slot. Reconcile reads the
        // real slot, sees the effect still matches → marker preserved.
        ServerPlayer p = grantedPlayer();
        setUpLookup(p);
        try {
            MobEffectEvent.Expired expiredEvent = new MobEffectEvent.Expired(p,
                    new MobEffectInstance(MobEffects.INVISIBILITY, 0, 0, false, true, true));
            expiredEvent.setCanceled(true);
            ShadowEscapeEffects.onEffectExpired(expiredEvent);
            assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                    "Expired event (even cancelled) must mark for reconciliation");
            // Effect still present and matches signature (cancelled expiry).
            when(p.level().getGameTime()).thenReturn(GAME_TIME + 39);
            when(p.getEffect(MobEffects.INVISIBILITY))
                    .thenReturn(new MobEffectInstance(MobEffects.INVISIBILITY, 1, 0, false, true, true));
            ShadowEscapeEffects.flushPendingForTesting();
            assertTrue(ShadowEscapeEffects.hasMark(p.getUUID()),
                    "cancelled Expired: effect still matches → marker preserved");
        } finally {
            ShadowEscapeEffects.setPlayerLookupForTesting(null);
        }
    }

    // ---- lifecycle & bounds ----

    @Test
    void logoutClearsTheMark() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        assertEquals(1, ShadowEscapeEffects.markCount());
        ShadowEscapeEffects.onPlayerLogout(new PlayerLoggedOutEvent(p));
        assertEquals(0, ShadowEscapeEffects.markCount());
    }

    @Test
    void logoutClearsPending() {
        ServerPlayer p = grantedPlayer();
        ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                MobEffects.INVISIBILITY, null));
        assertTrue(ShadowEscapeEffects.isPendingForTesting(p.getUUID()));
        ShadowEscapeEffects.onPlayerLogout(new PlayerLoggedOutEvent(p));
        assertFalse(ShadowEscapeEffects.isPendingForTesting(p.getUUID()),
                "logout must clear pending");
        assertFalse(ShadowEscapeEffects.hasMark(p.getUUID()),
                "logout must clear marker");
    }

    @Test
    void serverStopClearsEverything() {
        ServerPlayer p = player(GAME_TIME);
        when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        ServerPlayer p2 = player(GAME_TIME);
        when(p2.addEffect(any(MobEffectInstance.class))).thenReturn(true);
        ShadowEscapeEffects.applySuccess(p2, snapshot(ShadowAbilityTier.III));
        ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                MobEffects.INVISIBILITY, null));
        assertEquals(2, ShadowEscapeEffects.markCount());
        assertTrue(ShadowEscapeEffects.pendingCountForTesting() > 0);
        ShadowEscapeEffects.onServerStopping(null);
        assertEquals(0, ShadowEscapeEffects.markCount());
        assertEquals(0, ShadowEscapeEffects.pendingCountForTesting());
    }

    @Test
    void markerMapIsBounded() {
        for (int i = 0; i < ShadowEscapeEffects.MAX_MARKS + 10; i++) {
            ServerPlayer p = player(GAME_TIME);
            when(p.addEffect(any(MobEffectInstance.class))).thenReturn(true);
            ShadowEscapeEffects.applySuccess(p, snapshot(ShadowAbilityTier.II));
        }
        assertEquals(ShadowEscapeEffects.MAX_MARKS, ShadowEscapeEffects.markCount());
    }

    @Test
    void pendingSetIsBounded() {
        ServerPlayer p = grantedPlayer();
        for (int i = 0; i < 2000; i++) {
            ShadowEscapeEffects.onEffectRemoved(new MobEffectEvent.Remove(p,
                    MobEffects.INVISIBILITY, null));
        }
        assertTrue(ShadowEscapeEffects.pendingCountForTesting() <= 1024,
                "pending set must be bounded");
    }
}
