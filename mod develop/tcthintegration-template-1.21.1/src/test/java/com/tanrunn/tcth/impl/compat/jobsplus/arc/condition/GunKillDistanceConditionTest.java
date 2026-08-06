package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Unit tests for {@link GunKillDistanceCondition} (phase 5A).
 *
 * <p>Covers: min/max validation, inverted.
 */
class GunKillDistanceConditionTest {

    @Test
    void validRangeIsAccepted() {
        MinecraftTestBootstrap.bootStrap();
        GunKillDistanceCondition condition = new GunKillDistanceCondition(false, 0.0f, 100.0f);
        assertEquals(0.0f, condition.min());
        assertEquals(100.0f, condition.max());
    }

    @Test
    void negativeMinThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new GunKillDistanceCondition(false, -1.0f, 100.0f));
    }

    @Test
    void maxLessThanMinThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new GunKillDistanceCondition(false, 50.0f, 10.0f));
    }

    @Test
    void zeroRangeIsAccepted() {
        GunKillDistanceCondition condition = new GunKillDistanceCondition(false, 0.0f, 0.0f);
        assertEquals(0.0f, condition.min());
        assertEquals(0.0f, condition.max());
    }

    @Test
    void invertedIsStored() {
        GunKillDistanceCondition condition = new GunKillDistanceCondition(true, 0.0f, 100.0f);
        assertTrue(condition.isInverted());
    }
}
