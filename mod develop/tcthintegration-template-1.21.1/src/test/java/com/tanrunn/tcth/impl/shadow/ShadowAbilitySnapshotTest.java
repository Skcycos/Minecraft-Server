package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ShadowAbilitySnapshot} (phase 8E): the pure-MC immutable
 * snapshot queried at most once per attempt and threaded through all layers.
 */
class ShadowAbilitySnapshotTest {

    @Test
    void noneSnapshotHasEveryRouteAtNone() {
        ShadowAbilitySnapshot none = ShadowAbilitySnapshot.none();
        assertEquals(ShadowAbilityTier.NONE, none.sleight());
        assertEquals(ShadowAbilityTier.NONE, none.lifeSiphon());
        assertEquals(ShadowAbilityTier.NONE, none.spellTheft());
        assertEquals(ShadowAbilityTier.NONE, none.shadowEscape());
        assertFalse(none.hasAny());
    }

    @Test
    void tierByRoute() {
        ShadowAbilitySnapshot s = new ShadowAbilitySnapshot(ShadowAbilityTier.III,
                ShadowAbilityTier.I, ShadowAbilityTier.II, ShadowAbilityTier.NONE);
        assertEquals(ShadowAbilityTier.III, s.tier(ShadowAbilityRoute.SLEIGHT));
        assertEquals(ShadowAbilityTier.I, s.tier(ShadowAbilityRoute.LIFE_SIPHON));
        assertEquals(ShadowAbilityTier.II, s.tier(ShadowAbilityRoute.SPELL_THEFT));
        assertEquals(ShadowAbilityTier.NONE, s.tier(ShadowAbilityRoute.SHADOW_ESCAPE));
        assertTrue(s.hasAny());
    }

    @Test
    void nullTiersAreRejected() {
        assertThrows(NullPointerException.class, () -> new ShadowAbilitySnapshot(
                null, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE));
        assertThrows(NullPointerException.class, () -> ShadowAbilitySnapshot.none().tier(null));
    }
}
