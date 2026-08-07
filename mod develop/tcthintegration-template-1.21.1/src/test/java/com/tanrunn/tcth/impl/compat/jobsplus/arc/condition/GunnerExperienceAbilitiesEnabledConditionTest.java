package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 5B: {@link GunnerExperienceAbilitiesEnabledCondition} — four-switch
 * conjunction, fail-closed on any config read failure, and the inverted flag
 * can never turn a failure into a pass.
 */
class GunnerExperienceAbilitiesEnabledConditionTest {

    @AfterEach
    void tearDown() {
        GunnerExperienceAbilitiesEnabledCondition.resetSuppliersForTesting();
    }

    private static GunnerExperienceAbilitiesEnabledCondition cond(boolean inverted) {
        return new GunnerExperienceAbilitiesEnabledCondition(inverted);
    }

    private static ActionData data() {
        return new ActionData(null, null, java.util.Map.of());
    }

    @Test
    void matchesOnlyWhenAllFourSwitchesOn() {
        GunnerExperienceAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        assertTrue(cond(false).isMet(data()));

        GunnerExperienceAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(cond(false).isMet(data()));
        GunnerExperienceAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> false;
        assertFalse(cond(false).isMet(data()));
    }

    @Test
    void configFailureFailsClosedEvenWhenInverted() {
        GunnerExperienceAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        GunnerExperienceAbilitiesEnabledCondition.routeEnabledSupplier = () -> {
            throw new IllegalStateException("config broken");
        };
        // inverted=true must NOT flip the failure into a pass.
        assertFalse(cond(true).isMet(data()));
        assertFalse(cond(false).isMet(data()));

        GunnerExperienceAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> {
            throw new LinkageError("mod absent");
        };
        assertFalse(cond(true).isMet(data()));
    }
}
