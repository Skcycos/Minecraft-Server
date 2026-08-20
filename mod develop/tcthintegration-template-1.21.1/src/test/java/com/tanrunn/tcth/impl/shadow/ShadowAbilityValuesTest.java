package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ShadowAbilityValues} (phase 8E): the single numeric source
 * shared by the candidate pool, the success layer, the transfer prepare and
 * the cooldown layer. All values finite; tiers never stack.
 */
class ShadowAbilityValuesTest {

    @Test
    void sleightSuccessBonuses() {
        assertEquals(0.0d, ShadowAbilityValues.sleightSuccessBonus(ShadowAbilityTier.NONE));
        assertEquals(0.05d, ShadowAbilityValues.sleightSuccessBonus(ShadowAbilityTier.I));
        assertEquals(0.10d, ShadowAbilityValues.sleightSuccessBonus(ShadowAbilityTier.II));
        assertEquals(0.15d, ShadowAbilityValues.sleightSuccessBonus(ShadowAbilityTier.III));
        for (ShadowAbilityTier t : ShadowAbilityTier.values()) {
            assertTrue(Double.isFinite(ShadowAbilityValues.sleightSuccessBonus(t)));
        }
    }

    @Test
    void sleightCooldownsFromBase200() {
        assertEquals(200L, ShadowAbilityValues.sleightGlobalCooldownTicks(200L, ShadowAbilityTier.NONE));
        assertEquals(180L, ShadowAbilityValues.sleightGlobalCooldownTicks(200L, ShadowAbilityTier.I));
        assertEquals(160L, ShadowAbilityValues.sleightGlobalCooldownTicks(200L, ShadowAbilityTier.II));
        assertEquals(140L, ShadowAbilityValues.sleightGlobalCooldownTicks(200L, ShadowAbilityTier.III));
    }

    @Test
    void sleightCooldownNeverNegative() {
        // base <= reduction → 0 (including negative and zero bases).
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(-5L, ShadowAbilityTier.III));
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(-1L, ShadowAbilityTier.I));
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(0L, ShadowAbilityTier.III));
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(10L, ShadowAbilityTier.III));
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(60L, ShadowAbilityTier.III),
                "base equal to the reduction → 0");
        assertEquals(1L, ShadowAbilityValues.sleightGlobalCooldownTicks(61L, ShadowAbilityTier.III));
        assertEquals(0L, ShadowAbilityValues.sleightGlobalCooldownTicks(0L, ShadowAbilityTier.NONE));
        // Long.MAX_VALUE yields MAX - reduction — never MAX itself.
        assertEquals(Long.MAX_VALUE,
                ShadowAbilityValues.sleightGlobalCooldownTicks(Long.MAX_VALUE, ShadowAbilityTier.NONE));
        assertEquals(Long.MAX_VALUE - 60L,
                ShadowAbilityValues.sleightGlobalCooldownTicks(Long.MAX_VALUE, ShadowAbilityTier.III));
        assertEquals(Long.MAX_VALUE - 20L,
                ShadowAbilityValues.sleightGlobalCooldownTicks(Long.MAX_VALUE, ShadowAbilityTier.I));
    }

    @Test
    void highValueModifiers() {
        assertEquals(ItemPlan.HIGH_VALUE_MODIFIER, ShadowAbilityValues.highValueModifier(ShadowAbilityTier.NONE));
        assertEquals(ItemPlan.HIGH_VALUE_MODIFIER, ShadowAbilityValues.highValueModifier(ShadowAbilityTier.I));
        assertEquals(-0.05d, ShadowAbilityValues.highValueModifier(ShadowAbilityTier.II));
        assertEquals(0.0d, ShadowAbilityValues.highValueModifier(ShadowAbilityTier.III));
    }

    @Test
    void lifeSiphonTransfers() {
        assertEquals(1.0f, ShadowAbilityValues.lifeSiphonHealthTransfer(ShadowAbilityTier.NONE));
        assertEquals(1.0f, ShadowAbilityValues.lifeSiphonHealthTransfer(ShadowAbilityTier.I));
        assertEquals(2.0f, ShadowAbilityValues.lifeSiphonHealthTransfer(ShadowAbilityTier.II));
        assertEquals(4.0f, ShadowAbilityValues.lifeSiphonHealthTransfer(ShadowAbilityTier.III));
        assertEquals(2, ShadowAbilityValues.lifeSiphonHungerTransfer(ShadowAbilityTier.NONE));
        assertEquals(2, ShadowAbilityValues.lifeSiphonHungerTransfer(ShadowAbilityTier.I));
        assertEquals(3, ShadowAbilityValues.lifeSiphonHungerTransfer(ShadowAbilityTier.II));
        assertEquals(4, ShadowAbilityValues.lifeSiphonHungerTransfer(ShadowAbilityTier.III));
    }

    @Test
    void spellTheftMaxTicks() {
        assertEquals(200, ShadowAbilityValues.spellTheftMaxTicks(ShadowAbilityTier.NONE));
        assertEquals(200, ShadowAbilityValues.spellTheftMaxTicks(ShadowAbilityTier.I));
        assertEquals(400, ShadowAbilityValues.spellTheftMaxTicks(ShadowAbilityTier.II));
        assertEquals(600, ShadowAbilityValues.spellTheftMaxTicks(ShadowAbilityTier.III));
    }

    @Test
    void escapePackages() {
        assertEquals(0, ShadowAbilityValues.escapeSpeedTicks(ShadowAbilityTier.NONE));
        assertEquals(80, ShadowAbilityValues.escapeSpeedTicks(ShadowAbilityTier.I));
        assertEquals(120, ShadowAbilityValues.escapeSpeedTicks(ShadowAbilityTier.II));
        assertEquals(160, ShadowAbilityValues.escapeSpeedTicks(ShadowAbilityTier.III));
        assertEquals(0, ShadowAbilityValues.escapeSpeedAmplifier(ShadowAbilityTier.NONE));
        assertEquals(0, ShadowAbilityValues.escapeSpeedAmplifier(ShadowAbilityTier.I));
        assertEquals(0, ShadowAbilityValues.escapeSpeedAmplifier(ShadowAbilityTier.II));
        assertEquals(1, ShadowAbilityValues.escapeSpeedAmplifier(ShadowAbilityTier.III));
        assertEquals(0, ShadowAbilityValues.escapeInvisibilityTicks(ShadowAbilityTier.NONE));
        assertEquals(0, ShadowAbilityValues.escapeInvisibilityTicks(ShadowAbilityTier.I));
        assertEquals(40, ShadowAbilityValues.escapeInvisibilityTicks(ShadowAbilityTier.II));
        assertEquals(80, ShadowAbilityValues.escapeInvisibilityTicks(ShadowAbilityTier.III));
    }

    @Test
    void escapeFailureMultipliers() {
        assertEquals(1.0d, ShadowAbilityValues.escapeFailureMultiplier(ShadowAbilityTier.NONE));
        assertEquals(0.8d, ShadowAbilityValues.escapeFailureMultiplier(ShadowAbilityTier.I));
        assertEquals(0.6d, ShadowAbilityValues.escapeFailureMultiplier(ShadowAbilityTier.II));
        assertEquals(0.4d, ShadowAbilityValues.escapeFailureMultiplier(ShadowAbilityTier.III));
    }
}
