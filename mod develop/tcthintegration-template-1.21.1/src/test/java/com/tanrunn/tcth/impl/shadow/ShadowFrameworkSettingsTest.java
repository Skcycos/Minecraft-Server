package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShadowFrameworkSettings} (phase 8B).
 *
 * <p>Verifies the fail-closed config boundary: defaults are safe, suppliers
 * are injectable (no bare JUnit test touches a loaded {@code ModConfigSpec}),
 * invalid values fall back to the safe default and records reject malformed
 * combinations.
 */
class ShadowFrameworkSettingsTest {

    @Test
    void defaultsAreSafe() {
        ShadowFrameworkSettings defaults = ShadowFrameworkSettings.defaults();
        assertFalse(defaults.masterEnabled(),
                "a config read failure must fail closed — Config.ENABLED OFF (8C.2.2 §1)");
        assertFalse(defaults.integrationEnabled(), "the framework must default to OFF");
        assertFalse(defaults.playerTheftEnabled(), "player theft must default to OFF");
        assertFalse(defaults.entityTheftEnabled(), "entity theft must default to OFF");
        assertFalse(defaults.auditEnabled(),
                "a config read failure must fail closed — audit OFF (8C.2.1)");
        assertEquals(0.35d, defaults.baseSuccessChance());
        assertEquals(0.05d, defaults.minSuccessChance());
        assertEquals(0.85d, defaults.maxSuccessChance());
        assertTrue(defaults.globalCooldownTicks() > 0);
        assertTrue(defaults.noCandidateCooldownTicks() > 0);
        assertTrue(defaults.failureCooldownTicks() > 0);
        assertTrue(defaults.victimProtectionTicks() > 0);
        assertTrue(defaults.alertTicks() > 0);
    }

    @Test
    void failingSupplierFallsBackToSafeDefault() {
        // safeGet* is the config-read boundary: a throwing supplier (config
        // not loaded) must fall back to the safe default.
        assertFalse(ShadowFrameworkSettings.safeGetBool(failingBool(), false));
        assertTrue(ShadowFrameworkSettings.safeGetBool(failingBool(), true));
        assertEquals(0.35d, ShadowFrameworkSettings.safeGetDouble(failingDouble(), 0.35d));
        assertEquals(200L, ShadowFrameworkSettings.safeGetLong(failingLong(), 200L));
    }

    @Test
    void nonFiniteSupplierValuesFallBack() {
        assertEquals(0.35d, ShadowFrameworkSettings.safeGetDouble(() -> Double.NaN, 0.35d));
        assertEquals(0.35d, ShadowFrameworkSettings.safeGetDouble(() -> Double.POSITIVE_INFINITY, 0.35d));
        assertEquals(200L, ShadowFrameworkSettings.safeGetLong(() -> -5L, 200L));
        assertFalse(ShadowFrameworkSettings.safeGetBool(() -> null, Boolean.FALSE),
                "a null supplier value must fall back to the safe default");
    }

    @Test
    void nonFiniteValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, Double.NaN, 0.05d, 0.85d, 1L, 1L, 1L, 1L, 1L, false, 3L));
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, 0.35d, 0.05d, Double.POSITIVE_INFINITY, 1L, 1L, 1L, 1L, 1L, false, 3L));
    }

    @Test
    void outOfRangeChancesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, 1.5d, 0.05d, 0.85d, 1L, 1L, 1L, 1L, 1L, false, 3L));
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, 0.35d, -0.1d, 0.85d, 1L, 1L, 1L, 1L, 1L, false, 3L));
    }

    @Test
    void invertedClampsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, 0.35d, 0.85d, 0.05d, 1L, 1L, 1L, 1L, 1L, false, 3L));
    }

    @Test
    void negativeCooldownsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowFrameworkSettings(true, true, true, true, true, 0.35d, 0.05d, 0.85d, -1L, 1L, 1L, 1L, 1L, false, 3L));
    }

    @Test
    void supplierInjectionDrivesTheCoordinatorWithoutConfig() {
        // The coordinator must never depend on a loaded ModConfigSpec in
        // tests: explicit settings records are the only input.
        ShadowFrameworkSettings settings = new ShadowFrameworkSettings(
                true, true, true, true, true, 0.35d, 0.05d, 0.85d, 10L, 5L, 20L, 30L, 15L, false, 3L);
        AtomicBoolean called = new AtomicBoolean(false);
        ShadowFrameworkSettings[] captured = new ShadowFrameworkSettings[1];
        new ShadowAttemptCoordinator(() -> {
            called.set(true);
            captured[0] = settings;
            return settings;
        }, EmptyShadowCandidateProvider.INSTANCE, NoopShadowTransferExecutor.INSTANCE,
                ShadowProtectionService.denyAll(), new ShadowCooldownTracker(),
                new ShadowIdempotencyTracker(),
                level -> new ShadowAuditStore(), level -> new FakeDailyLimits(),
                net.minecraft.util.RandomSource::create,
                System::currentTimeMillis, () -> "2026-08-11");
        assertFalse(called.get(), "the supplier must be lazy");
    }

    private static java.util.function.Supplier<Boolean> failingBool() {
        return () -> {
            throw new IllegalStateException("config not loaded");
        };
    }

    private static java.util.function.Supplier<Double> failingDouble() {
        return () -> {
            throw new IllegalStateException("config not loaded");
        };
    }

    private static java.util.function.Supplier<Long> failingLong() {
        return () -> {
            throw new IllegalStateException("config not loaded");
        };
    }
}
