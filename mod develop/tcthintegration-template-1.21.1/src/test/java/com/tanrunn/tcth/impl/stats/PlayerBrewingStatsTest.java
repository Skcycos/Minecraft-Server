package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;

/**
 * Unit tests for {@link PlayerBrewingStats} counting and ranking semantics.
 */
class PlayerBrewingStatsTest {

    @Test
    void recordsEventAndBeverageCounts() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", 1, 1000L);
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 2, 2000L);

        assertEquals(2, s.getTotalBrewingEvents());
        assertEquals(3, s.getTotalBeveragesPrepared());
        assertEquals(2, s.getUniqueBeverageCount());
        assertEquals(1, s.getTierCounts().get(BeverageTier.COMMON));
        assertEquals(2, s.getTierCounts().get(BeverageTier.T2));
        assertEquals(2, s.getDeviceCounts().get(BeverageDevice.KEG));
        assertEquals(1, s.getItemCounts().get("minecraft:honey_bottle"));
        assertEquals(2, s.getItemCounts().get("brewinandchewin:beer"));
        assertEquals(1000L, s.getFirstPreparedAt());
        assertEquals(2000L, s.getLastPreparedAt());
        assertEquals("brewinandchewin:beer", s.getLastBeverage());
        assertEquals("KEG", s.getLastDevice());
        assertEquals("T2", s.getLastTier());
    }

    @Test
    void mostPreparedBeverageTiesBreakByLexicographicId() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:mead", 2, 1L);
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 2, 2L);
        // tie at 2 -> "brewinandchewin:beer" sorts before "brewinandchewin:mead"
        assertEquals("brewinandchewin:beer", s.getMostPreparedBeverage());
        assertEquals(2, s.getMostPreparedBeverageCount());
    }

    @Test
    void mostPreparedBeverageByHigherCountWins() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 1, 1L);
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:mead", 3, 2L);
        assertEquals("brewinandchewin:mead", s.getMostPreparedBeverage());
        assertEquals(3, s.getMostPreparedBeverageCount());
    }

    @Test
    void emptyStatsReportNoMostPrepared() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        assertEquals("", s.getMostPreparedBeverage());
        assertEquals(0, s.getMostPreparedBeverageCount());
    }

    @Test
    void countsSaturateAtIntegerMax() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        // A single huge stack saturates totalBeveragesPrepared / itemCounts,
        // while totalBrewingEvents counts each event exactly once.
        s.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", Integer.MAX_VALUE, 1L);
        s.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", Integer.MAX_VALUE, 2L);
        assertEquals(2, s.getTotalBrewingEvents());
        assertEquals(Integer.MAX_VALUE, s.getTotalBeveragesPrepared());
        assertEquals(Integer.MAX_VALUE, s.getItemCounts().get("minecraft:honey_bottle"));
    }

    @Test
    void itemMapRespectsCapForNewItems() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        // Fill beyond the cap with distinct items; new items stop being added.
        for (int i = 0; i < PlayerBrewingStats.MAX_TRACKED_ITEMS + 10; i++) {
            s.record(BeverageTier.T2, BeverageDevice.KEG, "mod:item_" + i, 1, i);
        }
        assertTrue(s.getItemCounts().size() <= PlayerBrewingStats.MAX_TRACKED_ITEMS);
        assertEquals(PlayerBrewingStats.MAX_TRACKED_ITEMS, s.getUniqueBeverageCount());
    }

    @Test
    void existingItemKeepsAccumulatingPastItemCap() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        // Fill to cap with item_0..item_4095.
        for (int i = 0; i < PlayerBrewingStats.MAX_TRACKED_ITEMS; i++) {
            s.record(BeverageTier.T2, BeverageDevice.KEG, "mod:item_" + i, 1, i);
        }
        // item_0 already exists -> keeps accumulating even though map is full.
        s.record(BeverageTier.T2, BeverageDevice.KEG, "mod:item_0", 5, 9999L);
        assertEquals(6, s.getItemCounts().get("mod:item_0"));
        assertEquals(PlayerBrewingStats.MAX_TRACKED_ITEMS, s.getItemCounts().size());
    }

    @Test
    void accessorsReturnDefensiveCopies() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", 1, 1L);

        Map<String, Integer> items = s.getItemCounts();
        assertFalse(items.isEmpty());
        assertFalse(s.getUniqueBeverages().isEmpty());
        assertFalse(s.getTierCounts().isEmpty());
        assertFalse(s.getDeviceCounts().isEmpty());
    }

    @Test
    void loadRejectsNegativeCountersAndMalformedResourceLocations() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 1, 1L);
        net.minecraft.nbt.CompoundTag tag = s.save();

        // Corrupt: negative totals, negative tier count, malformed item id,
        // malformed unique id, malformed lastBeverage.
        tag.putInt("totalBrewingEvents", -5);
        tag.putInt("totalBeveragesPrepared", -7);
        tag.putString("lastBeverage", "not a valid :::id");
        net.minecraft.nbt.CompoundTag items = tag.getCompound("itemCounts");
        items.putInt("..%bad&id", 3);
        items.putInt("brewinandchewin:beer", -2);
        tag.put("itemCounts", items);
        net.minecraft.nbt.ListTag unique = new net.minecraft.nbt.ListTag();
        unique.add(net.minecraft.nbt.StringTag.valueOf("../evil"));
        tag.put("uniqueBeverages", unique);

        PlayerBrewingStats loaded = PlayerBrewingStats.load(tag);
        assertEquals(0, loaded.getTotalBrewingEvents(), "negative counters are clamped to 0");
        assertEquals(0, loaded.getTotalBeveragesPrepared(), "negative counters are clamped to 0");
        assertEquals("", loaded.getLastBeverage(), "malformed resource location is rejected");
        assertFalse(loaded.getItemCounts().containsKey("..%bad&id"),
                "malformed resource locations must be skipped");
        assertFalse(loaded.getItemCounts().containsKey("brewinandchewin:beer"),
                "negative item count is rejected (entry not restored)");
        assertTrue(loaded.getUniqueBeverages().isEmpty(),
                "malformed unique ids are skipped");
    }

    @Test
    void loadKeepsSaturationOnHugeCounters() {
        PlayerBrewingStats s = new PlayerBrewingStats();
        s.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 1, 1L);
        net.minecraft.nbt.CompoundTag tag = s.save();
        tag.putInt("totalBrewingEvents", Integer.MAX_VALUE);
        tag.putInt("totalBeveragesPrepared", Integer.MAX_VALUE);
        net.minecraft.nbt.CompoundTag items = tag.getCompound("itemCounts");
        items.putInt("brewinandchewin:beer", Integer.MAX_VALUE);
        tag.put("itemCounts", items);

        PlayerBrewingStats loaded = PlayerBrewingStats.load(tag);
        assertEquals(Integer.MAX_VALUE, loaded.getTotalBrewingEvents());
        assertEquals(Integer.MAX_VALUE, loaded.getTotalBeveragesPrepared());
        assertEquals(Integer.MAX_VALUE, loaded.getItemCounts().get("brewinandchewin:beer"));
    }
}
