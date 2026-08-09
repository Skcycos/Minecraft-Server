package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 7E: brewer ability-route switch conditions — four-switch conjunction,
 * fail-closed on any config read failure, and the inverted flag can never turn
 * a failure into a pass.
 */
class BrewerAbilitiesEnabledConditionTest {

    @AfterEach
    void tearDown() {
        BrewerStudyAbilitiesEnabledCondition.resetSuppliersForTesting();
        BrewerTastingAbilitiesEnabledCondition.resetSuppliersForTesting();
    }

    private static ActionData data() {
        MinecraftTestBootstrap.bootStrap();
        return new ActionData(null, null, java.util.Map.of());
    }

    @Test
    void studyMatchesOnlyWhenAllFourSwitchesOn() {
        BrewerStudyAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        assertTrue(new BrewerStudyAbilitiesEnabledCondition(false).isMet(data()));

        BrewerStudyAbilitiesEnabledCondition.routeEnabledSupplier = () -> false;
        assertFalse(new BrewerStudyAbilitiesEnabledCondition(false).isMet(data()));
    }

    @Test
    void tastingMatchesOnlyWhenAllFourSwitchesOn() {
        BrewerTastingAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.routeEnabledSupplier = () -> true;
        assertTrue(new BrewerTastingAbilitiesEnabledCondition(false).isMet(data()));

        BrewerTastingAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> false;
        assertFalse(new BrewerTastingAbilitiesEnabledCondition(false).isMet(data()));
    }

    @Test
    void studyConfigFailureFailsClosedEvenWhenInverted() {
        BrewerStudyAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        BrewerStudyAbilitiesEnabledCondition.routeEnabledSupplier = () -> {
            throw new IllegalStateException("config broken");
        };
        // inverted=true must NOT flip the failure into a pass.
        assertFalse(new BrewerStudyAbilitiesEnabledCondition(true).isMet(data()));
        assertFalse(new BrewerStudyAbilitiesEnabledCondition(false).isMet(data()));

        BrewerStudyAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> {
            throw new LinkageError("mod absent");
        };
        assertFalse(new BrewerStudyAbilitiesEnabledCondition(true).isMet(data()));
    }

    @Test
    void tastingConfigFailureFailsClosedEvenWhenInverted() {
        BrewerTastingAbilitiesEnabledCondition.frameworkEnabledSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.integrationEnabledSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.abilitiesMasterSupplier = () -> true;
        BrewerTastingAbilitiesEnabledCondition.routeEnabledSupplier = () -> {
            throw new IllegalStateException("config broken");
        };
        assertFalse(new BrewerTastingAbilitiesEnabledCondition(true).isMet(data()));
        assertFalse(new BrewerTastingAbilitiesEnabledCondition(false).isMet(data()));

        BrewerTastingAbilitiesEnabledCondition.integrationEnabledSupplier = () -> {
            throw new LinkageError("mod absent");
        };
        assertFalse(new BrewerTastingAbilitiesEnabledCondition(true).isMet(data()));
    }
}
