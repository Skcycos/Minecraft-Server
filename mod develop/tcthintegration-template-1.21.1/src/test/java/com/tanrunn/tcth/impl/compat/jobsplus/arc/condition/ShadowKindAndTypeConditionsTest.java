package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 8E: {@link ShadowTargetKindCondition} and
 * {@link ShadowTheftTypeCondition} — the value-matching conditions of the
 * {@code tcth:on_shadow_theft_success} action data.
 */
class ShadowKindAndTypeConditionsTest {

    static {
        MinecraftTestBootstrap.bootStrap();
    }

    private static ActionData data(String kind, String type) {
        java.util.Map<com.daqem.arc.api.action.data.type.IActionDataType<?>, Object> values =
                new java.util.HashMap<>();
        if (kind != null) {
            values.put(TcthArcRegistrar.SHADOW_TARGET_KIND, kind);
        }
        if (type != null) {
            values.put(TcthArcRegistrar.SHADOW_THEFT_TYPE, type);
        }
        return new ActionData(null, null, values);
    }

    @Test
    void targetKindMatchesExactValue() {
        ShadowTargetKindCondition cond = new ShadowTargetKindCondition(false, "ENTITY");
        assertTrue(cond.isMet(data("ENTITY", "ITEM")));
        assertFalse(cond.isMet(data("PLAYER", "ITEM")));
        assertFalse(cond.isMet(data(null, "ITEM")));
    }

    @Test
    void targetKindInverted() {
        ShadowTargetKindCondition cond = new ShadowTargetKindCondition(true, "ENTITY");
        assertFalse(cond.isMet(data("ENTITY", "ITEM")));
        assertTrue(cond.isMet(data("PLAYER", "ITEM")));
        assertTrue(cond.isMet(data(null, "ITEM")), "missing data never matches the value");
    }

    @Test
    void theftTypeMatchesExactValue() {
        ShadowTheftTypeCondition cond = new ShadowTheftTypeCondition(false, "ITEM");
        assertTrue(cond.isMet(data("PLAYER", "ITEM")));
        assertFalse(cond.isMet(data("PLAYER", "HEALTH")));
        assertFalse(cond.isMet(data("PLAYER", null)));
    }

    @Test
    void theftTypeInverted() {
        ShadowTheftTypeCondition cond = new ShadowTheftTypeCondition(true, "ITEM");
        assertFalse(cond.isMet(data("PLAYER", "ITEM")));
        assertTrue(cond.isMet(data("PLAYER", "HEALTH")));
    }
}
