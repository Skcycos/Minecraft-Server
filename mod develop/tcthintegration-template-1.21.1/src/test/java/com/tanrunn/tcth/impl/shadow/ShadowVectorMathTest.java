package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.shadow.ShadowVectorMath.ShadowDirectionFacts;

import net.minecraft.world.phys.Vec3;

/**
 * Unit tests for {@link ShadowVectorMath} (phase 8B, stage 8A §10).
 *
 * <p>Covers: watched/behind thresholds as single-source constants, mutual
 * exclusivity, boundary angles and degenerate (zero-length / non-finite)
 * input fail-closed behaviour.
 */
class ShadowVectorMathTest {

    private static final double SQRT2_OVER_2 = Math.sqrt(2.0d) / 2.0d;

    @Test
    void thresholdsAreSingleSourceConstants() {
        assertEquals(SQRT2_OVER_2, ShadowVectorMath.WATCHED_DOT_MIN, 1.0E-12,
                "watched threshold must equal cos(45°)");
        assertEquals(-SQRT2_OVER_2, ShadowVectorMath.BEHIND_DOT_MAX, 1.0E-12,
                "behind threshold must equal cos(135°)");
        assertTrue(ShadowVectorMath.WATCHED_DOT_MIN > ShadowVectorMath.BEHIND_DOT_MAX,
                "watched and behind thresholds must never overlap");
    }

    @Test
    void targetLookingStraightAtThiefIsWatched() {
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(5.0d, 0.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
        assertTrue(facts.watched());
        assertFalse(facts.behind());
    }

    @Test
    void targetLookingAwayFromThiefIsBehind() {
        Vec3 targetLook = new Vec3(-1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(5.0d, 0.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
        assertFalse(facts.watched());
        assertTrue(facts.behind());
    }

    @Test
    void sideViewIsNeitherWatchedNorBehind() {
        Vec3 targetLook = new Vec3(0.0d, 0.0d, 1.0d);
        Vec3 thiefPos = new Vec3(5.0d, 0.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
        assertFalse(facts.watched());
        assertFalse(facts.behind());
    }

    @Test
    void watchedBoundaryAtExactlyFortyFiveDegrees() {
        // target looks along (1,0,0); thief at (1,1,0) → angle 45°
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(1.0d, 1.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
        assertTrue(facts.watched(), "exactly 45° must count as watched (>= threshold)");
        assertFalse(facts.behind());
    }

    @Test
    void behindBoundaryAtExactlyOneHundredThirtyFiveDegrees() {
        // Target at origin looking along (-1,0,0); the thief "behind" the
        // target sits in the opposite half-plane: toThief=(1,1,0) normalized
        // gives dot = -cos(45°) = cos(135°) = -0.7071…
        Vec3 targetLook = new Vec3(-1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(1.0d, 1.0d, 0.0d).scale(10.0d);
        Vec3 targetPos = Vec3.ZERO;
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
        assertFalse(facts.watched());
        assertTrue(facts.behind(), "exactly 135° must count as behind (<= threshold)");
    }

    @Test
    void watchedAndBehindAreMutuallyExclusiveAcrossTheFullCircle() {
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        for (int angle = 0; angle < 360; angle += 5) {
            double rad = Math.toRadians(angle);
            Vec3 thiefPos = new Vec3(Math.cos(rad), 0.0d, Math.sin(rad)).scale(10.0d);
            ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true);
            assertFalse(facts.watched() && facts.behind(),
                    "angle " + angle + " must not be both watched and behind");
        }
    }

    @Test
    void zeroLengthTargetLookFailsClosed() {
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(Vec3.ZERO, new Vec3(5.0d, 0.0d, 0.0d), Vec3.ZERO, true);
        assertFalse(facts.watched());
        assertFalse(facts.behind());
    }

    @Test
    void zeroLengthDistanceFailsClosed() {
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(new Vec3(1.0d, 0.0d, 0.0d), Vec3.ZERO, Vec3.ZERO, true);
        assertFalse(facts.watched());
        assertFalse(facts.behind());
    }

    @Test
    void nonFiniteInputFailsClosed() {
        assertFalse(ShadowVectorMath.computeFacts(new Vec3(Double.NaN, 0.0d, 0.0d),
                new Vec3(5.0d, 0.0d, 0.0d), Vec3.ZERO, true).watched());
        assertFalse(ShadowVectorMath.computeFacts(new Vec3(1.0d, 0.0d, 0.0d),
                new Vec3(Double.POSITIVE_INFINITY, 0.0d, 0.0d), Vec3.ZERO, true).behind());
    }

    @Test
    void nullInputFailsClosed() {
        ShadowDirectionFacts facts = ShadowVectorMath.computeFacts(null, new Vec3(5.0d, 0.0d, 0.0d), Vec3.ZERO, true);
        assertFalse(facts.watched());
        assertFalse(facts.behind());
    }

    // ---- 8B.1 line-of-sight ----

    @Test
    void watchedRequiresLineOfSight() {
        // At exactly 45° with LOS → watched; with LOS blocked → fail-closed
        // (no watched penalty, no behind bonus).
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(1.0d, 1.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        assertTrue(ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true).watched());
        ShadowVectorMath.ShadowDirectionFacts blocked =
                ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, false);
        assertFalse(blocked.watched(), "an obstructed view must not count as watched");
        assertFalse(blocked.behind(), "an obstructed view must not grant the behind bonus");
    }

    @Test
    void behindRequiresLineOfSight() {
        Vec3 targetLook = new Vec3(-1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(1.0d, 1.0d, 0.0d).scale(10.0d);
        Vec3 targetPos = Vec3.ZERO;
        assertTrue(ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true).behind());
        ShadowVectorMath.ShadowDirectionFacts blocked =
                ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, false);
        assertFalse(blocked.behind(), "an obstructed view must not grant the behind bonus");
        assertFalse(blocked.watched());
    }

    @Test
    void lineOfSightKeepsFactsMutuallyExclusive() {
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        for (int angle = 0; angle < 360; angle += 5) {
            double rad = Math.toRadians(angle);
            Vec3 thiefPos = new Vec3(Math.cos(rad), 0.0d, Math.sin(rad)).scale(10.0d);
            for (boolean los : new boolean[] { true, false }) {
                ShadowVectorMath.ShadowDirectionFacts facts =
                        ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, los);
                assertFalse(facts.watched() && facts.behind(),
                        "angle " + angle + " (los=" + los + ") must not be both watched and behind");
            }
        }
    }

    @Test
    void watcherBoundaryAtExactlyFortyFiveDegreesRequiresLos() {
        Vec3 targetLook = new Vec3(1.0d, 0.0d, 0.0d);
        Vec3 thiefPos = new Vec3(1.0d, 1.0d, 0.0d);
        Vec3 targetPos = Vec3.ZERO;
        assertTrue(ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, true).watched(),
                "45° with LOS counts as watched");
        assertFalse(ShadowVectorMath.computeFacts(targetLook, thiefPos, targetPos, false).watched(),
                "45° without LOS counts as neither watched nor behind");
    }
}
