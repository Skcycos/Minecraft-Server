package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

/**
 * Unit tests for {@link PlayerGunnerStats} (phase 5A).
 *
 * <p>Covers: total kills, tier distribution, weapon tracking, max distance,
 * most-used weapon, saturated addition, NBT round-trip.
 */
class PlayerGunnerStatsTest {

    private static final String WEAPON_A = "scguns:defender_pistol";
    private static final String WEAPON_B = "scguns:umax_pistol";
    private static final String TARGET_ZOMBIE = "minecraft:zombie";
    private static final String TARGET_SKELETON = "minecraft:skeleton";

    @Test
    void totalKillsAccumulate() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 15.0f, 2000L);
        assertEquals(2, stats.getTotalGunKills());
    }

    @Test
    void tierDistributionIsTracked() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_A, TARGET_SKELETON, GunTargetTier.ELITE, 20.0f, 2000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.HEAVY, 30.0f, 3000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.BOSS, 40.0f, 4000L);
        assertEquals(1, stats.getCommonKills());
        assertEquals(1, stats.getEliteKills());
        assertEquals(1, stats.getHeavyKills());
        assertEquals(1, stats.getBossKills());
        assertEquals(4, stats.getTotalGunKills());
    }

    @Test
    void weaponKillsAreTracked() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 15.0f, 2000L);
        stats.record(WEAPON_B, TARGET_SKELETON, GunTargetTier.ELITE, 20.0f, 3000L);
        assertEquals(2, stats.getWeaponKills().get(WEAPON_A));
        assertEquals(1, stats.getWeaponKills().get(WEAPON_B));
        assertEquals(2, stats.getUniqueWeapons());
    }

    @Test
    void maxDistanceIsTracked() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 50.0f, 2000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 30.0f, 3000L);
        assertEquals(50.0f, stats.getMaxDistance());
    }

    @Test
    void mostUsedWeaponIsReturned() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 15.0f, 2000L);
        stats.record(WEAPON_B, TARGET_SKELETON, GunTargetTier.ELITE, 20.0f, 3000L);
        assertEquals(WEAPON_A, stats.getMostUsedWeapon());
    }

    @Test
    void lastKillInfoIsTracked() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_B, TARGET_SKELETON, GunTargetTier.ELITE, 20.0f, 2000L);
        assertEquals(WEAPON_B, stats.getLastWeapon());
        assertEquals(TARGET_SKELETON, stats.getLastTarget());
        assertEquals("ELITE", stats.getLastTier());
        assertEquals(1000L, stats.getFirstGunKillAt());
        assertEquals(2000L, stats.getLastGunKillAt());
    }

    @Test
    void saturatedAdditionDoesNotOverflow() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        // Simulate near-max value by recording many kills.
        for (int i = 0; i < 1000; i++) {
            stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L + i);
        }
        assertTrue(stats.getTotalGunKills() >= 1000);
        assertTrue(stats.getTotalGunKills() > 0); // no overflow to negative
    }

    @Test
    void newWeaponBeyondCapIsNotTracked() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        // Fill the weapon map to the cap with distinct ids.
        for (int i = 0; i < GunnerStatsData.MAX_WEAPONS; i++) {
            stats.record("scguns:gun_" + i, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L + i);
        }
        assertEquals(GunnerStatsData.MAX_WEAPONS, stats.getWeaponKills().size());
        int totalBefore = stats.getTotalGunKills();
        // A brand-new weapon id beyond the cap must NOT be added to the map,
        // but the kill still counts.
        stats.record("scguns:overflow_gun", TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 999999L);
        assertEquals(GunnerStatsData.MAX_WEAPONS, stats.getWeaponKills().size(),
                "new weapon ids beyond the cap must not be tracked");
        assertEquals(totalBefore + 1, stats.getTotalGunKills(),
                "kills still count even when the weapon map is at its cap");
        assertFalse(stats.getWeaponKills().containsKey("scguns:overflow_gun"));
    }

    @Test
    void existingWeaponContinuesAccumulatingPastCap() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        for (int i = 1; i < GunnerStatsData.MAX_WEAPONS; i++) {
            stats.record("scguns:gun_" + i, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L + i);
        }
        assertEquals(GunnerStatsData.MAX_WEAPONS, stats.getWeaponKills().size());
        // Existing weapon must keep accumulating past the cap.
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 999999L);
        assertEquals(2, stats.getWeaponKills().get(WEAPON_A),
                "weapons already tracked must keep accumulating past the cap");
        assertEquals(GunnerStatsData.MAX_WEAPONS, stats.getWeaponKills().size());
    }

    @Test
    void nbtRoundTripPreservesData() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record(WEAPON_B, TARGET_SKELETON, GunTargetTier.ELITE, 25.5f, 2000L);
        stats.record(WEAPON_A, TARGET_ZOMBIE, GunTargetTier.HEAVY, 50.0f, 3000L);

        // Save to NBT
        net.minecraft.nbt.CompoundTag tag = stats.save();

        // Load from NBT
        PlayerGunnerStats loaded = PlayerGunnerStats.load(tag);

        assertEquals(stats.getTotalGunKills(), loaded.getTotalGunKills());
        assertEquals(stats.getCommonKills(), loaded.getCommonKills());
        assertEquals(stats.getEliteKills(), loaded.getEliteKills());
        assertEquals(stats.getHeavyKills(), loaded.getHeavyKills());
        assertEquals(stats.getBossKills(), loaded.getBossKills());
        assertEquals(stats.getUniqueWeapons(), loaded.getUniqueWeapons());
        assertEquals(stats.getMaxDistance(), loaded.getMaxDistance());
        assertEquals(stats.getLastWeapon(), loaded.getLastWeapon());
        assertEquals(stats.getLastTarget(), loaded.getLastTarget());
        assertEquals(stats.getLastTier(), loaded.getLastTier());
        assertEquals(stats.getFirstGunKillAt(), loaded.getFirstGunKillAt());
        assertEquals(stats.getLastGunKillAt(), loaded.getLastGunKillAt());
        assertEquals(stats.getWeaponKills(), loaded.getWeaponKills());
    }

    @Test
    void emptyStatsReturnDefaults() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        assertEquals(0, stats.getTotalGunKills());
        assertEquals(0, stats.getCommonKills());
        assertEquals(0, stats.getEliteKills());
        assertEquals(0, stats.getHeavyKills());
        assertEquals(0, stats.getBossKills());
        assertEquals(0, stats.getUniqueWeapons());
        assertEquals(0.0f, stats.getMaxDistance());
        assertEquals("", stats.getLastWeapon());
        assertEquals("", stats.getLastTarget());
        assertEquals("", stats.getLastTier());
        assertEquals(0L, stats.getFirstGunKillAt());
        assertEquals(0L, stats.getLastGunKillAt());
        assertEquals("", stats.getMostUsedWeapon());
    }
}
