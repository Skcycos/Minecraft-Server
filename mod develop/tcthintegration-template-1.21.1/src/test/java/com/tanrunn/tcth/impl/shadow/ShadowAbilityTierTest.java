package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ShadowAbilityTier} (phase 8E): route semantics — only the
 * highest ACTIVE node applies; lower nodes are excluded (no stacking).
 */
class ShadowAbilityTierTest {

    @Test
    void noneWhenNothingActive() {
        assertEquals(ShadowAbilityTier.NONE, ShadowAbilityTier.highestActive(false, false, false));
    }

    @Test
    void firstTierWhenOnlyItIsActive() {
        assertEquals(ShadowAbilityTier.I, ShadowAbilityTier.highestActive(true, false, false));
    }

    @Test
    void secondTierWhenOnlyItIsActive() {
        assertEquals(ShadowAbilityTier.II, ShadowAbilityTier.highestActive(false, true, false));
    }

    @Test
    void thirdTierWhenOnlyItIsActive() {
        assertEquals(ShadowAbilityTier.III, ShadowAbilityTier.highestActive(false, false, true));
    }

    @Test
    void higherTierWinsOverLower() {
        assertEquals(ShadowAbilityTier.II, ShadowAbilityTier.highestActive(true, true, false));
        assertEquals(ShadowAbilityTier.III, ShadowAbilityTier.highestActive(true, true, true));
        assertEquals(ShadowAbilityTier.III, ShadowAbilityTier.highestActive(false, true, true));
        assertEquals(ShadowAbilityTier.III, ShadowAbilityTier.highestActive(true, false, true));
    }

    @Test
    void allThreeActiveYieldsOnlyThird() {
        // Even with all three active, only the highest tier applies —
        // never a sum of 1+2+3.
        assertEquals(ShadowAbilityTier.III, ShadowAbilityTier.highestActive(true, true, true));
    }
}
