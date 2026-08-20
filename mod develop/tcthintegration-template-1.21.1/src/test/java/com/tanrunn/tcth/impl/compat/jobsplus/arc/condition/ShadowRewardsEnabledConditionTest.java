package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 8E: {@link ShadowRewardsEnabledCondition} — the three-switch
 * conjunction (framework + integration + rewards), fail-closed on any config
 * read failure, and the inverted flag can never turn a failure into a pass.
 */
class ShadowRewardsEnabledConditionTest {

    @AfterEach
    void tearDown() {
        ShadowRewardsEnabledCondition.resetSuppliersForTesting();
    }

    private static ShadowRewardsEnabledCondition cond(boolean inverted) {
        return new ShadowRewardsEnabledCondition(inverted);
    }

    private static ActionData data() {
        return new ActionData(null, null, java.util.Map.of());
    }

    @Test
    void matchesOnlyWhenAllThreeSwitchesOn() {
        ShadowRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> true;
        assertTrue(cond(false).isMet(data()));

        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> false;
        assertFalse(cond(false).isMet(data()));
        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> false;
        assertFalse(cond(false).isMet(data()));
        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.frameworkEnabledSupplier = () -> false;
        assertFalse(cond(false).isMet(data()));
    }

    @Test
    void invertedFlipsMatchesButNeverFailures() {
        ShadowRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> true;
        assertFalse(cond(true).isMet(data()), "inverted: enabled → no match");

        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> false;
        assertTrue(cond(true).isMet(data()), "inverted: disabled → match");
    }

    @Test
    void configFailureFailsClosedEvenWhenInverted() {
        ShadowRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        ShadowRewardsEnabledCondition.rewardsEnabledSupplier = () -> {
            throw new IllegalStateException("config broken");
        };
        // inverted=true must NOT flip the failure into a pass.
        assertFalse(cond(true).isMet(data()));
        assertFalse(cond(false).isMet(data()));

        ShadowRewardsEnabledCondition.integrationEnabledSupplier = () -> {
            throw new LinkageError("mod absent");
        };
        assertFalse(cond(true).isMet(data()));
    }
}
