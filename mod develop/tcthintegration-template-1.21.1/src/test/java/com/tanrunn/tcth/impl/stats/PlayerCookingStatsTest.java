package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Unit tests for {@link PlayerCookingStats} recording and NBT round-trip.
 */
class PlayerCookingStatsTest {

    private static final long NOW = 1_700_000_000_000L;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private PlayerCookingStats cookedOnce() {
        PlayerCookingStats stats = new PlayerCookingStats();
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN, "minecraft:cooked_beef", 1, NOW);
        return stats;
    }

    @Test
    void firstDishIncreasesEventsAndUnique() {
        PlayerCookingStats stats = cookedOnce();
        assertEquals(1, stats.getTotalCookingEvents());
        assertEquals(1, stats.getTotalDishesCooked());
        assertEquals(1, stats.getUniqueDishCount());
        assertTrue(stats.getUniqueResultItems().contains("minecraft:cooked_beef"));
        assertEquals(NOW, stats.getFirstCookedAt());
        assertEquals(NOW, stats.getLastCookedAt());
        assertEquals("minecraft:cooked_beef", stats.getLastDish());
    }

    @Test
    void repeatDishIncreasesEventsButNotUnique() {
        PlayerCookingStats stats = cookedOnce();
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN, "minecraft:cooked_beef", 2, NOW + 1000);
        assertEquals(2, stats.getTotalCookingEvents());
        assertEquals(3, stats.getTotalDishesCooked(), "dish total accumulates the count");
        assertEquals(1, stats.getUniqueDishCount());
    }

    @Test
    void countAccumulatesPerItemAndDevicesCountPerEvent() {
        PlayerCookingStats stats = cookedOnce();
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN, "minecraft:cooked_beef", 3, NOW);
        stats.record(CookingDevice.SMOKER, DishTier.T2, DishQuality.STANDARD, "minecraft:cooked_porkchop", 2, NOW);
        assertEquals(4, stats.getItemCounts().get("minecraft:cooked_beef"), "1 + 3");
        assertEquals(2, stats.getItemCounts().get("minecraft:cooked_porkchop"));
        assertEquals(2, stats.getDeviceCounts().get(CookingDevice.FURNACE), "device counts per event");
        assertEquals(1, stats.getDeviceCounts().get(CookingDevice.SMOKER));
        assertEquals(4, stats.getTierCounts().get(DishTier.COMMON), "1 + 3");
        assertEquals(2, stats.getTierCounts().get(DishTier.T2));
        assertEquals(4, stats.getQualityCounts().get(DishQuality.UNKNOWN), "1 + 3");
        assertEquals(2, stats.getQualityCounts().get(DishQuality.STANDARD));
    }

    @Test
    void saturatingAdditionNeverOverflows() {
        PlayerCookingStats stats = new PlayerCookingStats();
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN,
                "minecraft:cooked_beef", Integer.MAX_VALUE - 1, NOW);
        stats.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN,
                "minecraft:cooked_beef", Integer.MAX_VALUE - 1, NOW);
        assertEquals(Integer.MAX_VALUE, stats.getTotalDishesCooked(), "must saturate, never overflow");
        assertEquals(Integer.MAX_VALUE, stats.getItemCounts().get("minecraft:cooked_beef"));
        assertEquals(Integer.MAX_VALUE, stats.getTierCounts().get(DishTier.COMMON));
        assertEquals(2, stats.getTotalCookingEvents(), "events count by event");
    }

    @Test
    void itemCountCapKeepsAccumulatingExistingItems() {
        PlayerCookingStats stats = new PlayerCookingStats();
        // Fill itemCounts to the cap with distinct ids, then keep adding to an
        // existing item past the cap.
        int cap = 4096;
        for (int i = 0; i < cap; i++) {
            stats.record(CookingDevice.FURNACE, null, DishQuality.UNKNOWN, "minecraft:item_" + i, 1, NOW);
        }
        assertEquals(cap, stats.getItemCounts().size());
        // Existing item must keep accumulating even past the cap.
        stats.record(CookingDevice.FURNACE, null, DishQuality.UNKNOWN, "minecraft:item_0", 5, NOW);
        assertEquals(cap, stats.getItemCounts().size(), "size stays capped");
        assertEquals(6, stats.getItemCounts().get("minecraft:item_0"), "existing item keeps accumulating");
        // A brand-new item is not added past the cap.
        stats.record(CookingDevice.FURNACE, null, DishQuality.UNKNOWN, "minecraft:new_item", 1, NOW);
        assertEquals(cap, stats.getItemCounts().size());
        assertTrue(!stats.getItemCounts().containsKey("minecraft:new_item"));
    }

    @Test
    void saveLoadRoundTripIsConsistent() {
        PlayerCookingStats stats = cookedOnce();
        stats.record(CookingDevice.SMOKER, DishTier.T2, DishQuality.EXCELLENT, "minecraft:cooked_porkchop", 3, NOW + 5000);
        CompoundTag tag = stats.save();
        PlayerCookingStats loaded = PlayerCookingStats.load(tag);

        assertEquals(stats.getTotalCookingEvents(), loaded.getTotalCookingEvents());
        assertEquals(stats.getTotalDishesCooked(), loaded.getTotalDishesCooked());
        assertEquals(stats.getUniqueDishCount(), loaded.getUniqueDishCount());
        assertEquals(stats.getDeviceCounts(), loaded.getDeviceCounts());
        assertEquals(stats.getTierCounts(), loaded.getTierCounts());
        assertEquals(stats.getQualityCounts(), loaded.getQualityCounts());
        assertEquals(stats.getItemCounts(), loaded.getItemCounts());
        assertEquals(stats.getFirstCookedAt(), loaded.getFirstCookedAt());
        assertEquals(stats.getLastCookedAt(), loaded.getLastCookedAt());
        assertEquals(stats.getLastDish(), loaded.getLastDish());
    }

    @Test
    void unknownDeviceTierQualityDoNotFailLoading() {
        PlayerCookingStats stats = cookedOnce();
        CompoundTag tag = stats.save();
        tag.getCompound("deviceCounts").putInt("UNKNOWN_DEVICE", 3);
        tag.getCompound("tierCounts").putInt("T9", 3);
        tag.getCompound("qualityCounts").putInt("GODLY", 3);

        PlayerCookingStats loaded = PlayerCookingStats.load(tag);
        assertEquals(1, loaded.getTotalDishesCooked(), "unknown enum values must be skipped, not fatal");
        assertEquals(1, loaded.getTotalCookingEvents());
    }

    @Test
    void dataVersionIsStoredAndOldVersionLoadsSafely() {
        CookingStatsData data = new CookingStatsData();
        UUID uuid = UUID.randomUUID();
        data.getOrCreate(uuid).record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN,
                "minecraft:cooked_beef", 1, NOW);

        CompoundTag tag = data.save(new CompoundTag(), (HolderLookup.Provider) null);
        assertTrue(tag.contains("dataVersion"));

        tag.putInt("dataVersion", 0);
        CookingStatsData loaded = CookingStatsData.load(tag, null);
        assertEquals(1, loaded.get(uuid).getTotalDishesCooked());
    }

    @Test
    void deviceCountsNeverOverflowFromLoadedNbt() {
        // Load a stats with deviceCounts already at Integer.MAX_VALUE, then
        // record one more event: must stay at MAX_VALUE, never wrap negative.
        PlayerCookingStats stats = cookedOnce();
        CompoundTag tag = stats.save();
        tag.getCompound("deviceCounts").putInt("FURNACE", Integer.MAX_VALUE);
        PlayerCookingStats loaded = PlayerCookingStats.load(tag);

        loaded.record(CookingDevice.FURNACE, DishTier.COMMON, DishQuality.UNKNOWN,
                "minecraft:cooked_beef", 1, NOW);

        assertEquals(Integer.MAX_VALUE, loaded.getDeviceCounts().get(CookingDevice.FURNACE),
                "device count must saturate, not overflow to negative");
    }

    @Test
    void playerCapKeepsUpdatingExistingPlayers() {
        CookingStatsData data = new CookingStatsData();
        UUID first = new UUID(0, 0);
        // Fill to the cap.
        for (int i = 0; i < 1024; i++) {
            UUID id = new UUID(0, i);
            data.getOrCreate(id).record(CookingDevice.FURNACE, null, DishQuality.UNKNOWN, "x", 1, NOW);
        }
        // Existing player past cap still updates.
        PlayerCookingStats existing = data.getOrCreate(first);
        assertEquals(1, existing.getTotalDishesCooked());
        existing.record(CookingDevice.FURNACE, null, DishQuality.UNKNOWN, "x", 1, NOW);
        assertEquals(2, data.getOrCreate(first).getTotalDishesCooked());
        // New player past cap is refused.
        assertEquals(null, data.getOrCreate(UUID.randomUUID()));
        assertEquals(1024, data.getPlayers().size());
    }
}
