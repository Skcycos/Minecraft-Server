package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 8E: the four {@code tcth:shadow_*_abilities_enabled} route conditions
 * — the four-switch conjunction (framework + integration + master + route),
 * fail-closed on any config read failure, inverted never flips failures.
 */
class ShadowRouteEnabledConditionsTest {

    @BeforeEach
    void setUp() {
        ShadowRouteEnabledCondition.frameworkEnabledSupplier = () -> true;
        ShadowRouteEnabledCondition.integrationEnabledSupplier = () -> true;
        ShadowRouteEnabledCondition.abilitiesMasterSupplier = () -> true;
        ShadowSleightAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        ShadowLifeSiphonAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        ShadowSpellTheftAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        ShadowEscapeAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
    }

    @AfterEach
    void tearDown() {
        ShadowRouteEnabledCondition.resetBaseSuppliersForTesting();
        ShadowSleightAbilitiesEnabledCondition.resetSuppliersForTesting();
        ShadowLifeSiphonAbilitiesEnabledCondition.resetSuppliersForTesting();
        ShadowSpellTheftAbilitiesEnabledCondition.resetSuppliersForTesting();
        ShadowEscapeAbilitiesEnabledCondition.resetSuppliersForTesting();
    }

    private static ActionData data() {
        return new ActionData(null, null, java.util.Map.of());
    }

    private static boolean met(AbstractCondition cond) {
        return cond.isMet(data());
    }

    @Test
    void allFourRoutesMatchWhenEverythingIsOn() {
        assertTrue(met(new ShadowSleightAbilitiesEnabledCondition(false)));
        assertTrue(met(new ShadowLifeSiphonAbilitiesEnabledCondition(false)));
        assertTrue(met(new ShadowSpellTheftAbilitiesEnabledCondition(false)));
        assertTrue(met(new ShadowEscapeAbilitiesEnabledCondition(false)));
    }

    @Test
    void routeSwitchOffStopsOnlyThatRoute() {
        ShadowSleightAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(false)));
        assertTrue(met(new ShadowLifeSiphonAbilitiesEnabledCondition(false)),
                "other routes are unaffected by the sleight switch");
        ShadowLifeSiphonAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(met(new ShadowLifeSiphonAbilitiesEnabledCondition(false)));
        ShadowSpellTheftAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(met(new ShadowSpellTheftAbilitiesEnabledCondition(false)));
        ShadowEscapeAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(met(new ShadowEscapeAbilitiesEnabledCondition(false)));
    }

    @Test
    void masterSwitchOffStopsEveryRoute() {
        ShadowRouteEnabledCondition.abilitiesMasterSupplier = () -> false;
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(false)));
        assertFalse(met(new ShadowEscapeAbilitiesEnabledCondition(false)));
    }

    @Test
    void integrationSwitchOffStopsEveryRoute() {
        ShadowRouteEnabledCondition.integrationEnabledSupplier = () -> false;
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(false)));
        assertFalse(met(new ShadowSpellTheftAbilitiesEnabledCondition(false)));
    }

    @Test
    void configFailureFailsClosedEvenWhenInverted() {
        ShadowRouteEnabledCondition.abilitiesMasterSupplier = () -> {
            throw new IllegalStateException("config broken");
        };
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(true)),
                "inverted must not flip a failure into a pass");
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(false)));

        ShadowSleightAbilitiesEnabledCondition.routeEnabledSupplier = () -> {
            throw new LinkageError("mod absent");
        };
        assertFalse(met(new ShadowSleightAbilitiesEnabledCondition(true)));
    }
}
