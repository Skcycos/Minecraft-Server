package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Unit tests for {@link GunnerStatsCommand} (phase 5A).
 *
 * <p>Covers: format output for empty stats, format output for populated stats.
 */
class GunnerStatsCommandTest {

    @Test
    void formatEmptyStatsReturnsComponent() {
        MinecraftTestBootstrap.bootStrap();
        PlayerGunnerStats stats = new PlayerGunnerStats();
        net.minecraft.network.chat.Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("枪客档案"));
        assertTrue(text.contains("总枪械击杀: 0"));
    }

    @Test
    void formatPopulatedStatsReturnsComponent() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:umax_pistol", "minecraft:skeleton", GunTargetTier.ELITE, 25.5f, 2000L);
        stats.record("scguns:defender_pistol", "minecraft:creeper", GunTargetTier.HEAVY, 50.0f, 3000L);

        net.minecraft.network.chat.Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("总枪械击杀: 3"));
        assertTrue(text.contains("COMMON=1"));
        assertTrue(text.contains("ELITE=1"));
        assertTrue(text.contains("HEAVY=1"));
        assertTrue(text.contains("最大击杀距离: 50.0"));
        assertTrue(text.contains("minecraft:creeper"));
    }

    @Test
    void formatShowsMostUsedWeapon() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 15.0f, 2000L);
        stats.record("scguns:umax_pistol", "minecraft:skeleton", GunTargetTier.ELITE, 20.0f, 3000L);

        net.minecraft.network.chat.Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("scguns:defender_pistol"));
    }
}
