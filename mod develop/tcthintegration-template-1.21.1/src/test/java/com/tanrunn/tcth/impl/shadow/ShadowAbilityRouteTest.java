package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * Tests for {@link ShadowAbilityRoute} (phase 8E): the fixed node ids of the
 * four routes (the ids are phase-contract values and must never be renamed)
 * and the full powerup locations.
 */
class ShadowAbilityRouteTest {

    @Test
    void sleightRouteNodes() {
        assertEquals("sleight_of_hand_i", ShadowAbilityRoute.SLEIGHT.nodeI());
        assertEquals("sleight_of_hand_ii", ShadowAbilityRoute.SLEIGHT.nodeII());
        assertEquals("sleight_of_hand_iii", ShadowAbilityRoute.SLEIGHT.nodeIII());
    }

    @Test
    void lifeSiphonRouteNodes() {
        assertEquals("life_siphon_i", ShadowAbilityRoute.LIFE_SIPHON.nodeI());
        assertEquals("life_siphon_ii", ShadowAbilityRoute.LIFE_SIPHON.nodeII());
        assertEquals("life_siphon_iii", ShadowAbilityRoute.LIFE_SIPHON.nodeIII());
    }

    @Test
    void spellTheftRouteNodes() {
        assertEquals("spell_theft_i", ShadowAbilityRoute.SPELL_THEFT.nodeI());
        assertEquals("spell_theft_ii", ShadowAbilityRoute.SPELL_THEFT.nodeII());
        assertEquals("spell_theft_iii", ShadowAbilityRoute.SPELL_THEFT.nodeIII());
    }

    @Test
    void shadowEscapeRouteNodes() {
        assertEquals("shadow_escape_i", ShadowAbilityRoute.SHADOW_ESCAPE.nodeI());
        assertEquals("shadow_escape_ii", ShadowAbilityRoute.SHADOW_ESCAPE.nodeII());
        assertEquals("shadow_escape_iii", ShadowAbilityRoute.SHADOW_ESCAPE.nodeIII());
    }

    @Test
    void nodeLocationsAreUnderTheShadowThiefJob() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("tcth", "shadow_thief/sleight_of_hand_i"),
                ShadowAbilityRoute.SLEIGHT.nodeLocation("sleight_of_hand_i"));
        assertEquals(ResourceLocation.fromNamespaceAndPath("tcth", "shadow_thief/shadow_escape_iii"),
                ShadowAbilityRoute.SHADOW_ESCAPE.nodeLocation("shadow_escape_iii"));
    }

    @Test
    void nodeIdByTier() {
        assertEquals("sleight_of_hand_i", ShadowAbilityRoute.SLEIGHT.nodeId(ShadowAbilityTier.I));
        assertEquals("sleight_of_hand_ii", ShadowAbilityRoute.SLEIGHT.nodeId(ShadowAbilityTier.II));
        assertEquals("sleight_of_hand_iii", ShadowAbilityRoute.SLEIGHT.nodeId(ShadowAbilityTier.III));
        assertNull(ShadowAbilityRoute.SLEIGHT.nodeId(ShadowAbilityTier.NONE));
    }

    @Test
    void fourRoutesExactly() {
        assertEquals(4, ShadowAbilityRoute.values().length);
    }
}
