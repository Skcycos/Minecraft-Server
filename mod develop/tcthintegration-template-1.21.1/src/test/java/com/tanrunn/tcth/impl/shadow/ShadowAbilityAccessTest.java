package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerPlayer;

/**
 * Tests for {@link ShadowAbilityAccess} (phase 8E): the default is the
 * all-NONE snapshot (basic behaviour, no Jobs+); the provider is installed by
 * the compat module; query failures fail closed.
 */
class ShadowAbilityAccessTest {

    @AfterEach
    void tearDown() {
        ShadowAbilityAccess.resetForTesting();
    }

    @Test
    void defaultProviderYieldsNone() {
        ServerPlayer player = mock(ServerPlayer.class);
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityAccess.snapshotFor(player));
    }

    @Test
    void nullPlayerYieldsNone() {
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityAccess.snapshotFor(null));
    }

    @Test
    void installedProviderIsUsed() {
        ShadowAbilitySnapshot snapshot = new ShadowAbilitySnapshot(ShadowAbilityTier.III,
                ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE);
        ShadowAbilityAccess.setProvider(p -> snapshot);
        assertSame(snapshot, ShadowAbilityAccess.snapshotFor(mock(ServerPlayer.class)));
    }

    @Test
    void throwingProviderFailsClosedToNone() {
        ShadowAbilityAccess.setProvider(p -> {
            throw new IllegalStateException("broken Jobs+");
        });
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityAccess.snapshotFor(mock(ServerPlayer.class)));
    }

    @Test
    void nullReturningProviderFailsClosedToNone() {
        ShadowAbilityAccess.setProvider(p -> null);
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityAccess.snapshotFor(mock(ServerPlayer.class)));
    }

    @Test
    void resetRestoresTheNoneDefault() {
        ShadowAbilityAccess.setProvider(p -> new ShadowAbilitySnapshot(ShadowAbilityTier.I,
                ShadowAbilityTier.I, ShadowAbilityTier.I, ShadowAbilityTier.I));
        ShadowAbilityAccess.resetForTesting();
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityAccess.snapshotFor(mock(ServerPlayer.class)));
    }

    @Test
    void snapshotIsQueriedOncePerCall() {
        // The handler contract: the snapshot is queried exactly once per
        // attempt; the accessor itself is a straight pass-through.
        int[] calls = { 0 };
        ShadowAbilityAccess.setProvider(p -> {
            calls[0]++;
            return ShadowAbilitySnapshot.none();
        });
        ShadowAbilityAccess.snapshotFor(mock(ServerPlayer.class));
        assertEquals(1, calls[0]);
    }
}
