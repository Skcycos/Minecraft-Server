package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

/**
 * Phase 5C: medal thresholds and silent reconcile.
 */
class GunnerMedalEvaluatorTest {

    private static final String W = "scguns:defender_pistol";
    private static final String T = "minecraft:zombie";

    @Test
    void zeroKillsUnlocksNothing() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        assertTrue(GunnerMedalEvaluator.unlockNewlyMet(stats, 1L).isEmpty());
        assertFalse(stats.hasMedal(GunnerMedal.FIRST_BLOOD));
    }

    @Test
    void firstKillUnlocksFirstBloodOnly() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(W, T, GunTargetTier.COMMON, 1.0f, 1000L);
        List<GunnerMedal> newly = GunnerMedalEvaluator.unlockNewlyMet(stats, 111L);
        assertEquals(List.of(GunnerMedal.FIRST_BLOOD), newly);
        assertEquals(111L, stats.getUnlockedMedals().get(GunnerMedal.FIRST_BLOOD.id()));
        assertFalse(stats.hasMedal(GunnerMedal.CENTURION));
    }

    @Test
    void ninetyNineKillsNoCenturionOneHundredUnlocks() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        for (int i = 0; i < 99; i++) {
            stats.record(W, T, GunTargetTier.COMMON, 1.0f, 1000L + i);
        }
        GunnerMedalEvaluator.unlockNewlyMet(stats, 1L);
        assertTrue(stats.hasMedal(GunnerMedal.FIRST_BLOOD));
        assertFalse(stats.hasMedal(GunnerMedal.CENTURION));
        stats.record(W, T, GunTargetTier.COMMON, 1.0f, 2000L);
        List<GunnerMedal> newly = GunnerMedalEvaluator.unlockNewlyMet(stats, 2L);
        assertTrue(newly.contains(GunnerMedal.CENTURION));
        assertTrue(stats.hasMedal(GunnerMedal.CENTURION));
    }

    @Test
    void longShotBoundaryFiftyInclusive() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(W, T, GunTargetTier.COMMON, 49.9f, 1L);
        GunnerMedalEvaluator.unlockNewlyMet(stats, 1L);
        assertFalse(stats.hasMedal(GunnerMedal.LONG_SHOT));
        stats.record(W, T, GunTargetTier.COMMON, 50.0f, 2L);
        List<GunnerMedal> newly = GunnerMedalEvaluator.unlockNewlyMet(stats, 2L);
        assertTrue(newly.contains(GunnerMedal.LONG_SHOT));
    }

    @Test
    void eliteHunterUsesEliteOnlyNotHeavy() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        for (int i = 0; i < 24; i++) {
            stats.record(W, T, GunTargetTier.ELITE, 1.0f, 1L + i);
        }
        for (int i = 0; i < 100; i++) {
            stats.record(W, T, GunTargetTier.HEAVY, 1.0f, 1000L + i);
        }
        GunnerMedalEvaluator.unlockNewlyMet(stats, 1L);
        assertFalse(stats.hasMedal(GunnerMedal.ELITE_HUNTER));
        stats.record(W, T, GunTargetTier.ELITE, 1.0f, 2000L);
        assertTrue(GunnerMedalEvaluator.unlockNewlyMet(stats, 2L).contains(GunnerMedal.ELITE_HUNTER));
    }

    @Test
    void bossFinisherAtOneBossKill() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(W, T, GunTargetTier.COMMON, 1.0f, 1L);
        GunnerMedalEvaluator.unlockNewlyMet(stats, 1L);
        assertFalse(stats.hasMedal(GunnerMedal.BOSS_FINISHER));
        stats.record(W, T, GunTargetTier.BOSS, 1.0f, 2L);
        assertTrue(GunnerMedalEvaluator.unlockNewlyMet(stats, 2L).contains(GunnerMedal.BOSS_FINISHER));
    }

    @Test
    void alreadyUnlockedNotReturnedAgain() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(W, T, GunTargetTier.COMMON, 1.0f, 1L);
        assertEquals(1, GunnerMedalEvaluator.unlockNewlyMet(stats, 1L).size());
        assertTrue(GunnerMedalEvaluator.unlockNewlyMet(stats, 2L).isEmpty());
    }

    @Test
    void multiMedalUnlockPreservesDefinitionOrder() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        // One kill at 50+ distance with BOSS also satisfies first blood, long shot, boss.
        stats.record(W, T, GunTargetTier.BOSS, 50.0f, 1L);
        List<GunnerMedal> newly = GunnerMedalEvaluator.unlockNewlyMet(stats, 9L);
        assertEquals(List.of(
                GunnerMedal.FIRST_BLOOD,
                GunnerMedal.LONG_SHOT,
                GunnerMedal.BOSS_FINISHER), newly);
    }

    @Test
    void reconcileSilentUsesZeroTimestamp() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        for (int i = 0; i < 100; i++) {
            stats.record(W, T, GunTargetTier.COMMON, 1.0f, 1L + i);
        }
        assertTrue(GunnerMedalEvaluator.reconcileSilent(stats));
        assertTrue(stats.hasMedal(GunnerMedal.FIRST_BLOOD));
        assertTrue(stats.hasMedal(GunnerMedal.CENTURION));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.FIRST_BLOOD.id()));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.CENTURION.id()));
    }
}
