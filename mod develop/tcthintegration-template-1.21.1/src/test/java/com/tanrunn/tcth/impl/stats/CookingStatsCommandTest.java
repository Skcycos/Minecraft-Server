package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.network.chat.Component;

/**
 * Unit tests for {@link CookingStatsCommand} output formatting.
 */
class CookingStatsCommandTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void formatShowsTotalsDevicesTiersQualityAndLastDish() {
        PlayerCookingStats stats = new PlayerCookingStats();
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN, "minecraft:cooked_beef", 1, 1_700_000_000_000L);
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN, "minecraft:cooked_beef", 1, 1_700_000_001_000L);
        stats.record(CookingDevice.SMOKER, DishTier.T3, DishQuality.SUPERB, "minecraft:cooked_porkchop", 1, 1_700_000_002_000L);

        Component out = CookingStatsCommand.format(stats);
        String text = out.getString();
        assertTrue(text.contains("出锅次数: 3"), text);
        assertTrue(text.contains("料理份数: 3"), text);
        assertTrue(text.contains("不同料理: 2"), text);
        assertTrue(text.contains("最常用设备: FURNACE (2 次)"), text);
        assertTrue(text.contains("COMMON=2"), text);
        assertTrue(text.contains("T3=1"), text);
        assertTrue(text.contains("SUPERB=1"), text);
        assertTrue(text.contains("最近制作: minecraft:cooked_porkchop"), text);
        assertTrue(text.contains("SMOKER"), text);
    }

    @Test
    void emptyStatsFormatIsSafe() {
        Component out = CookingStatsCommand.format(new PlayerCookingStats());
        assertTrue(out.getString().contains("出锅次数: 0"));
        assertTrue(out.getString().contains("最近制作: -"));
    }
}
