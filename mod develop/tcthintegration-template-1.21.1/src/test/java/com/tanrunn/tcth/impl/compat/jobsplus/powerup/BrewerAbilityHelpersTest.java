package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.impl.compat.jobsplus.powerup.BrewerAbilityRoute;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.BrewerPowerupTier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;

/**
 * Unit tests for the pure brewer-ability tier/effect helpers (phase 7E).
 */
class BrewerAbilityHelpersTest {

    @Test
    void highestActiveOnlyPicksHighestTier() {
        assertEquals(BrewerPowerupTier.NONE, BrewerPowerupTier.highestActive(false, false, false));
        assertEquals(BrewerPowerupTier.I, BrewerPowerupTier.highestActive(true, false, false));
        assertEquals(BrewerPowerupTier.II, BrewerPowerupTier.highestActive(true, true, false));
        assertEquals(BrewerPowerupTier.III, BrewerPowerupTier.highestActive(true, true, true));
        assertEquals(BrewerPowerupTier.III, BrewerPowerupTier.highestActive(false, false, true));
        // lower tiers are excluded even if active
        assertEquals(BrewerPowerupTier.NONE, BrewerPowerupTier.highestActive(false, false, false));
    }

    @Test
    void routeNodesAreNamespacedUnderBrewer() {
        assertEquals("tcth:brewer/brewing_basic",
                BrewerAbilityRoute.BREWING.nodeLocation(BrewerAbilityRoute.BREWING.nodeI()).toString());
        assertEquals("tcth:brewer/study_iii",
                BrewerAbilityRoute.STUDY.nodeLocation(BrewerAbilityRoute.STUDY.nodeIII()).toString());
    }

    @Test
    void brewingEffectDurationsMatchSpec() {
        assertEquals(100, BrewerAbilityModule.brewingSpeedTicks(BrewerPowerupTier.I));
        assertEquals(160, BrewerAbilityModule.brewingSpeedTicks(BrewerPowerupTier.II));
        assertEquals(240, BrewerAbilityModule.brewingSpeedTicks(BrewerPowerupTier.III));
        assertEquals(0, BrewerAbilityModule.brewingSpeedTicks(BrewerPowerupTier.NONE));
        assertEquals(0, BrewerAbilityModule.brewingLuckTicks(BrewerPowerupTier.I));
        assertEquals(160, BrewerAbilityModule.brewingLuckTicks(BrewerPowerupTier.II));
        assertEquals(240, BrewerAbilityModule.brewingLuckTicks(BrewerPowerupTier.III));
    }

    @Test
    void resistanceMultipliersMatchSpec() {
        assertEquals(0.90f, BrewerAbilityModule.resistanceMultiplier(BrewerPowerupTier.I));
        assertEquals(0.80f, BrewerAbilityModule.resistanceMultiplier(BrewerPowerupTier.II));
        assertEquals(0.65f, BrewerAbilityModule.resistanceMultiplier(BrewerPowerupTier.III));
        assertEquals(1.0f, BrewerAbilityModule.resistanceMultiplier(BrewerPowerupTier.NONE));
    }

    @Test
    void studyMultipliersMatchSpecAndDoNotStack() {
        assertEquals(1.15f, BrewerAbilityModule.experienceMultiplier(BrewerPowerupTier.I));
        assertEquals(1.35f, BrewerAbilityModule.experienceMultiplier(BrewerPowerupTier.II));
        assertEquals(1.60f, BrewerAbilityModule.experienceMultiplier(BrewerPowerupTier.III));
        assertEquals(1.0f, BrewerAbilityModule.experienceMultiplier(BrewerPowerupTier.NONE));
        // No stacking: the module never multiplies two tiers together.
        assertEquals(1.60f, BrewerAbilityModule.experienceMultiplier(BrewerPowerupTier.III));
    }

    @Test
    void magicalDamageRecognisesMagicIndirectAndWither() {
        assertTrue(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.MAGIC)));
        assertTrue(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.INDIRECT_MAGIC)));
        assertTrue(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.WITHER)));
    }

    @Test
    void magicalDamageExcludesFireFallMeleeProjectile() {
        assertFalse(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.IN_FIRE)));
        assertFalse(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.ON_FIRE)));
        assertFalse(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.LAVA)));
        assertFalse(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.FALL)));
        assertFalse(BrewerAbilityModule.isMagicalDamage(damageSource(DamageTypes.MOB_PROJECTILE)));
        assertFalse(BrewerAbilityModule.isMagicalDamage(null));
    }

    private static DamageSource damageSource(ResourceKey<DamageType> key) {
        DamageSource source = Mockito.mock(DamageSource.class);
        Mockito.when(source.is(key)).thenReturn(true);
        return source;
    }
}
