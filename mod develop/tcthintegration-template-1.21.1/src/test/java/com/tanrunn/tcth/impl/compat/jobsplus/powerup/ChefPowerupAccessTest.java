package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Phase 3D: pure highest-active-tier logic and route layout.
 *
 * <p>Proves the "only the highest active node applies" rule for every route
 * and that the four routes are independent.
 */
class ChefPowerupAccessTest {

    @Test
    void noneWhenNothingActive() {
        assertEquals(ChefPowerupTier.NONE, ChefPowerupAccess.highestActive(false, false, false));
    }

    @Test
    void knifeRouteHighestActive() {
        // I active → I (10%).
        assertEquals(ChefPowerupTier.I, ChefPowerupAccess.highestActive(true, false, false));
        // II active → II (20%), never I+II = 30%.
        assertEquals(ChefPowerupTier.II, ChefPowerupAccess.highestActive(true, true, false));
        assertEquals(ChefPowerupTier.II, ChefPowerupAccess.highestActive(false, true, false));
        // III active → III (35%), never I+II+III = 65%.
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(true, true, true));
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(false, false, true));
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(false, true, true));
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(true, false, true));
    }

    @Test
    void hearthRouteHighestActive() {
        assertEquals(ChefPowerupTier.I, ChefPowerupAccess.highestActive(true, false, false));
        // II active → II (30%), never 15+30 = 45%.
        assertEquals(ChefPowerupTier.II, ChefPowerupAccess.highestActive(true, true, false));
        // III active → III (50%), never 15+30+50 = 95%.
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(true, true, true));
    }

    @Test
    void tastingRouteHighestActive() {
        assertEquals(ChefPowerupTier.I, ChefPowerupAccess.highestActive(true, false, false));
        // II active → II package only (no I action).
        assertEquals(ChefPowerupTier.II, ChefPowerupAccess.highestActive(true, true, false));
        // III active → III package only (no I/II actions).
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(true, true, true));
    }

    @Test
    void studyRouteHighestActive() {
        assertEquals(ChefPowerupTier.I, ChefPowerupAccess.highestActive(true, false, false));
        assertEquals(ChefPowerupTier.II, ChefPowerupAccess.highestActive(true, true, false));
        assertEquals(ChefPowerupTier.III, ChefPowerupAccess.highestActive(true, true, true));
    }

    @Test
    void fourRoutesCanBeActiveSimultaneously() {
        // 刀工 II + 炉火 I + 品鉴 III + 研修 I — all independent.
        ChefPowerupTier knife = ChefPowerupAccess.highestActive(true, true, false);
        ChefPowerupTier hearth = ChefPowerupAccess.highestActive(true, false, false);
        ChefPowerupTier tasting = ChefPowerupAccess.highestActive(true, true, true);
        ChefPowerupTier study = ChefPowerupAccess.highestActive(true, false, false);
        assertEquals(ChefPowerupTier.II, knife);
        assertEquals(ChefPowerupTier.I, hearth);
        assertEquals(ChefPowerupTier.III, tasting);
        assertEquals(ChefPowerupTier.I, study);
    }

    @Test
    void routeNodesAndJobLocationAreWellFormed() {
        assertEquals("tcth:chef", ChefPowerupAccess.CHEF_JOB.toString());
        assertEquals("tcth:chef/knife_adept", ChefAbilityRoute.KNIFE.nodeLocation("knife_adept").toString());
        assertNotNull(ChefAbilityRoute.STUDY.nodeI());
        assertNotNull(ChefAbilityRoute.STUDY.nodeII());
        assertNotNull(ChefAbilityRoute.STUDY.nodeIII());
    }

    @Test
    void tierValuesAreExactlyFour() {
        assertEquals(4, ChefPowerupTier.values().length);
        assertEquals(ChefPowerupTier.NONE, ChefPowerupTier.valueOf("NONE"));
        assertEquals(ChefPowerupTier.I, ChefPowerupTier.valueOf("I"));
        assertEquals(ChefPowerupTier.II, ChefPowerupTier.valueOf("II"));
        assertEquals(ChefPowerupTier.III, ChefPowerupTier.valueOf("III"));
        assertThrows(IllegalArgumentException.class, () -> ChefPowerupTier.valueOf("IV"));
    }
}
