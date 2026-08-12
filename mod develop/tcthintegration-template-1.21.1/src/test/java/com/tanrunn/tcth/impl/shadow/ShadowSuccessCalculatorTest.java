package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import net.minecraft.util.RandomSource;

/**
 * Unit tests for {@link ShadowSuccessCalculator} (phase 8B, stage 8A §10).
 *
 * <p>Covers: base 35%, behind +25%, watched -25%, alerted -20%, distance
 * decay, clamps 5%/85%, NaN/Infinity fail-closed, single random call per
 * roll, explicit {@code <} boundary.
 */
class ShadowSuccessCalculatorTest {

    @Test
    void baseChanceIsThirtyFivePercent() {
        assertEquals(0.35d, ShadowSuccessCalculator.calculate(ShadowSuccessContext.standard()), 1.0E-9);
        assertEquals(0.35d, ShadowSuccessCalculator.BASE_CHANCE);
    }

    @Test
    void behindAddsTwentyFivePercent() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, true, false, false, 0.0d,
                0.0d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.60d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void watchedSubtractsTwentyFivePercent() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, false, true, false, 0.0d,
                0.0d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.10d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void alertedSubtractsTwentyPercent() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, false, false, true, 0.0d,
                0.0d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.15d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void watchedAndAlertedStackClampedToMin() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, false, true, true, 0.0d,
                0.0d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9,
                "the raw -0.10 must be clamped to the 5% floor");
    }

    @Test
    void distanceDecayOnlyBeyondBestRange() {
        assertEquals(0.35d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, 1.5d, 0.0d, 0.0d, 0.05d, 0.85d)), 1.0E-9,
                "within the best range there is no decay");
        assertEquals(0.31d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, 3.5d, 0.0d, 0.0d, 0.05d, 0.85d)), 1.0E-9,
                "3.5 blocks = 2.0 over the start = 0.04 decay");
    }

    @Test
    void chanceIsClampedToLowerBound() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, false, true, true, 100.0d,
                0.0d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void chanceIsClampedToUpperBound() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, true, false, false, 0.0d,
                0.30d, 0.0d, 0.05d, 0.85d);
        assertEquals(0.85d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void candidateAndAbilityModifiersApply() {
        ShadowSuccessContext ctx = new ShadowSuccessContext(0.35d, false, false, false, 0.0d,
                -0.10d, 0.10d, 0.05d, 0.85d);
        assertEquals(0.35d, ShadowSuccessCalculator.calculate(ctx), 1.0E-9);
    }

    @Test
    void nonFiniteInputsFailClosedToMinChance() {
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(Double.NaN, false, false, false, 0.0d, 0.0d, 0.0d, 0.05d, 0.85d)));
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, Double.POSITIVE_INFINITY, 0.0d, 0.0d, 0.05d, 0.85d)));
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, 0.0d, Double.NEGATIVE_INFINITY, 0.0d, 0.05d, 0.85d)));
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, 0.0d, 0.0d, 0.0d, Double.NaN, 0.85d)));
        assertEquals(0.05d, ShadowSuccessCalculator.calculate(
                new ShadowSuccessContext(0.35d, false, false, false, 0.0d, 0.0d, 0.0d, 0.05d, Double.NaN)));
    }

    @Test
    void rollCallsRandomExactlyOnce() {
        RandomSource random = mock(RandomSource.class);
        when(random.nextDouble()).thenReturn(0.1d);
        assertTrue(ShadowSuccessCalculator.roll(random, 0.35d));
        verify(random, times(1)).nextDouble();
    }

    @Test
    void rollUsesExplicitStrictComparison() {
        RandomSource random = mock(RandomSource.class);
        // nextDouble() == chance → strict < fails (fail-safe for the target)
        when(random.nextDouble()).thenReturn(0.35d);
        assertFalse(ShadowSuccessCalculator.roll(random, 0.35d));
        // just below the chance → succeeds
        when(random.nextDouble()).thenReturn(0.3499d);
        assertTrue(ShadowSuccessCalculator.roll(random, 0.35d));
    }

    @Test
    void zeroChanceNeverSucceedsEvenOnZeroRoll() {
        RandomSource random = mock(RandomSource.class);
        when(random.nextDouble()).thenReturn(0.0d);
        assertFalse(ShadowSuccessCalculator.roll(random, 0.0d), "0 < 0 must fail");
    }

    @Test
    void nonFiniteChanceNeverSucceeds() {
        RandomSource random = mock(RandomSource.class);
        assertFalse(ShadowSuccessCalculator.roll(random, Double.NaN));
        assertFalse(ShadowSuccessCalculator.roll(random, Double.POSITIVE_INFINITY));
    }

    @Test
    void constantsAreExposedForSingleSourceOfTruth() {
        assertEquals(0.25d, ShadowSuccessCalculator.BEHIND_BONUS);
        assertEquals(0.25d, ShadowSuccessCalculator.WATCHED_PENALTY);
        assertEquals(0.20d, ShadowSuccessCalculator.ALERTED_PENALTY);
        assertEquals(0.05d, ShadowSuccessCalculator.MIN_CHANCE);
        assertEquals(0.85d, ShadowSuccessCalculator.MAX_CHANCE);
    }
}
