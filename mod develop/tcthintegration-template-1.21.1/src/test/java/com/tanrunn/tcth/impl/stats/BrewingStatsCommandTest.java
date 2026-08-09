package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.network.chat.Component;

/**
 * Unit tests for {@link BrewingStatsCommand} output formatting (phase 7D).
 */
class BrewingStatsCommandTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void formatShowsTotalsDevicesTiersMostAndLast() {
        PlayerBrewingStats stats = new PlayerBrewingStats();
        stats.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", 1, 1_700_000_000_000L);
        stats.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", 1, 1_700_000_001_000L);
        stats.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 1, 1_700_000_002_000L);

        Component out = BrewingStatsCommand.format(stats);
        String text = out.getString();
        assertTrue(text.contains("调饮次数: 3"), text);
        assertTrue(text.contains("饮品份数: 3"), text);
        assertTrue(text.contains("不同饮品: 2"), text);
        assertTrue(text.contains("最常用设备: KEG (3 次)"), text);
        assertTrue(text.contains("COMMON=2"), text);
        assertTrue(text.contains("T2=1"), text);
        assertTrue(text.contains("最常调制: minecraft:honey_bottle (2 份)"), text);
        assertTrue(text.contains("最近调制: brewinandchewin:beer"), text);
        assertTrue(text.contains("KEG"), text);
    }

    @Test
    void emptyStatsFormatIsSafe() {
        Component out = BrewingStatsCommand.format(new PlayerBrewingStats());
        String text = out.getString();
        assertTrue(text.contains("调饮次数: 0"), text);
        assertTrue(text.contains("最常调制: -"), text);
        assertTrue(text.contains("最近调制: -"), text);
    }
}
