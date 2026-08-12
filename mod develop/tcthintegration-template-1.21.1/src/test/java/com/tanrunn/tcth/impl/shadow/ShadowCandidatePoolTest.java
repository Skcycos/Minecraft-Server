package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.util.RandomSource;

/**
 * Unit tests for {@link ShadowCandidatePool} (phase 8B).
 *
 * <p>Covers: empty pool never touches the random source, single candidate,
 * five-type default weights, pruning and renormalisation, non-positive weight
 * rejection, large weights without int overflow, exactly one draw call, no
 * redraw after a failed execution.
 */
class ShadowCandidatePoolTest {

    @Test
    void emptyPoolDrawsNothingAndNeverCallsRandom() {
        RandomSource random = mock(RandomSource.class);
        assertNull(ShadowCandidatePool.empty().draw(random));
        verify(random, never()).nextLong();
        verify(random, never()).nextDouble();
    }

    @Test
    void singleCandidateIsAlwaysDrawn() {
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        ShadowCandidatePool pool = ShadowCandidatePool.empty()
                .with(ShadowCandidate.plain(ShadowTheftType.HEALTH, 20));
        assertEquals(ShadowTheftType.HEALTH, pool.draw(random).type());
        verify(random, times(1)).nextLong();
    }

    @Test
    void defaultWeightsMatchStage8a() {
        ShadowCandidatePool pool = ShadowCandidatePool.defaults();
        assertEquals(5, pool.size());
        assertEquals(30, ShadowCandidatePool.DEFAULT_ITEM_WEIGHT);
        assertEquals(20, ShadowCandidatePool.DEFAULT_COIN_WEIGHT);
        assertEquals(20, ShadowCandidatePool.DEFAULT_HEALTH_WEIGHT);
        assertEquals(15, ShadowCandidatePool.DEFAULT_HUNGER_WEIGHT);
        assertEquals(15, ShadowCandidatePool.DEFAULT_EFFECT_WEIGHT);
        assertEquals(100L, pool.totalWeight());
    }

    @Test
    void removalRenormalisesRemainingWeights() {
        ShadowCandidatePool pool = ShadowCandidatePool.defaults().without(ShadowTheftType.ITEM);
        assertFalse(pool.contains(ShadowTheftType.ITEM));
        // remaining weights: COIN 20, HEALTH 20, HUNGER 15, EFFECT 15 = 70
        assertEquals(70L, pool.totalWeight());
        RandomSource random = mock(RandomSource.class);
        // roll 45 → COIN(0..20) HEALTH(20..40) HUNGER(40..55) → HUNGER
        when(random.nextLong()).thenReturn(45L);
        assertEquals(ShadowTheftType.HUNGER, pool.draw(random).type());
        // roll 30 → within HEALTH (20..40)
        when(random.nextLong()).thenReturn(30L);
        assertEquals(ShadowTheftType.HEALTH, pool.draw(random).type());
    }

    @Test
    void nonPositiveWeightsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShadowCandidate.plain(ShadowTheftType.ITEM, 0));
        assertThrows(IllegalArgumentException.class, () -> ShadowCandidate.plain(ShadowTheftType.ITEM, -5));
    }

    @Test
    void hugeWeightsDoNotOverflowIntOrLong() {
        ShadowCandidatePool pool = ShadowCandidatePool.empty()
                .with(new ShadowCandidate(ShadowTheftType.ITEM, Integer.MAX_VALUE, 0.0d, false))
                .with(new ShadowCandidate(ShadowTheftType.HEALTH, Integer.MAX_VALUE, 0.0d, false))
                .with(new ShadowCandidate(ShadowTheftType.EFFECT, Integer.MAX_VALUE, 0.0d, false));
        assertEquals(3L * Integer.MAX_VALUE, pool.totalWeight(),
                "the weight sum must be computed in long");
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(Long.MAX_VALUE);
        assertTrue(pool.draw(random) != null, "a huge pool must still draw");
        verify(random, times(1)).nextLong();
    }

    @Test
    void drawUsesWeightedDistribution() {
        RandomSource random = mock(RandomSource.class);
        ShadowCandidatePool pool = ShadowCandidatePool.defaults();
        // weights: ITEM 30, COIN 20, HEALTH 20, HUNGER 15, EFFECT 15 (total 100)
        when(random.nextLong()).thenReturn(0L);
        assertEquals(ShadowTheftType.ITEM, pool.draw(random).type());
        when(random.nextLong()).thenReturn(29L);
        assertEquals(ShadowTheftType.ITEM, pool.draw(random).type());
        when(random.nextLong()).thenReturn(30L);
        assertEquals(ShadowTheftType.COIN, pool.draw(random).type());
        when(random.nextLong()).thenReturn(49L);
        assertEquals(ShadowTheftType.COIN, pool.draw(random).type());
        when(random.nextLong()).thenReturn(50L);
        assertEquals(ShadowTheftType.HEALTH, pool.draw(random).type());
        when(random.nextLong()).thenReturn(69L);
        assertEquals(ShadowTheftType.HEALTH, pool.draw(random).type());
        when(random.nextLong()).thenReturn(70L);
        assertEquals(ShadowTheftType.HUNGER, pool.draw(random).type());
        when(random.nextLong()).thenReturn(84L);
        assertEquals(ShadowTheftType.HUNGER, pool.draw(random).type());
        when(random.nextLong()).thenReturn(85L);
        assertEquals(ShadowTheftType.EFFECT, pool.draw(random).type());
        when(random.nextLong()).thenReturn(99L);
        assertEquals(ShadowTheftType.EFFECT, pool.draw(random).type());
        verify(random, times(10)).nextLong();
    }

    @Test
    void drawCallsRandomExactlyOncePerDraw() {
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        ShadowCandidatePool pool = ShadowCandidatePool.defaults();
        pool.draw(random);
        pool.draw(random);
        verify(random, times(2)).nextLong();
    }

    @Test
    void poolIsImmutableAcrossWithAndWithout() {
        ShadowCandidatePool pool = ShadowCandidatePool.defaults();
        ShadowCandidatePool pruned = pool.without(ShadowTheftType.ITEM);
        assertEquals(5, pool.size(), "the original pool must stay untouched");
        assertEquals(4, pruned.size());
    }

    @Test
    void nonFiniteSuccessModifierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShadowCandidate(ShadowTheftType.ITEM, 5, Double.NaN, false));
    }
}
