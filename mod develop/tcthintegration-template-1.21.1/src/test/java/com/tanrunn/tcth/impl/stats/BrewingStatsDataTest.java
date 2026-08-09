package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Unit tests for {@link BrewingStatsData} (phase 7D).
 *
 * <p>Covers: player cap, NBT round-trip, unknown uuid / unknown enum skip.
 */
class BrewingStatsDataTest {

    private static HolderLookup.Provider provider() {
        MinecraftTestBootstrap.bootStrap();
        return HolderLookup.Provider.create(Stream.empty());
    }

    @Test
    void newPlayerIsCreated() {
        BrewingStatsData data = new BrewingStatsData();
        UUID id = UUID.randomUUID();
        assertNotNull(data.getOrCreate(id));
        assertEquals(1, data.getPlayers().size());
    }

    @Test
    void getReturnsNullForUnknownPlayer() {
        BrewingStatsData data = new BrewingStatsData();
        assertNull(data.get(UUID.randomUUID()));
    }

    @Test
    void existingPlayerIsReturned() {
        BrewingStatsData data = new BrewingStatsData();
        UUID id = UUID.randomUUID();
        PlayerBrewingStats a = data.getOrCreate(id);
        PlayerBrewingStats b = data.getOrCreate(id);
        assertTrue(a == b, "existing player must return the same instance");
    }

    @Test
    void playerCapIsEnforced() {
        BrewingStatsData data = new BrewingStatsData();
        for (int i = 0; i < 1024; i++) {
            assertNotNull(data.getOrCreate(UUID.randomUUID()));
        }
        assertNull(data.getOrCreate(UUID.randomUUID()));
        assertEquals(1024, data.getPlayers().size());
    }

    @Test
    void existingPlayerSurvivesCap() {
        BrewingStatsData data = new BrewingStatsData();
        UUID existingId = UUID.randomUUID();
        data.getOrCreate(existingId);
        for (int i = 0; i < 1023; i++) {
            data.getOrCreate(UUID.randomUUID());
        }
        assertNotNull(data.getOrCreate(existingId));
    }

    @Test
    void nbtRoundTripPreservesData() {
        BrewingStatsData data = new BrewingStatsData();
        UUID id = UUID.randomUUID();
        PlayerBrewingStats stats = data.getOrCreate(id);
        stats.record(BeverageTier.COMMON, BeverageDevice.KEG, "minecraft:honey_bottle", 1, 1000L);
        stats.record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 2, 2000L);

        CompoundTag tag = data.save(new CompoundTag(), provider());

        BrewingStatsData loaded = BrewingStatsData.load(tag, provider());
        PlayerBrewingStats loadedStats = loaded.get(id);
        assertNotNull(loadedStats);
        assertEquals(2, loadedStats.getTotalBrewingEvents());
        assertEquals(3, loadedStats.getTotalBeveragesPrepared());
        assertEquals(2, loadedStats.getUniqueBeverageCount());
        assertEquals(1, loadedStats.getTierCounts().get(BeverageTier.COMMON));
        assertEquals(2, loadedStats.getTierCounts().get(BeverageTier.T2));
        assertEquals(2, loadedStats.getDeviceCounts().get(BeverageDevice.KEG));
        assertEquals("brewinandchewin:beer", loadedStats.getLastBeverage());
    }

    @Test
    void loadSkipsUnknownUuid() {
        CompoundTag playersTag = new CompoundTag();
        playersTag.put("not-a-uuid", new CompoundTag());
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("players", playersTag);

        BrewingStatsData loaded = BrewingStatsData.load(tag, provider());
        assertEquals(0, loaded.getPlayers().size());
    }

    @Test
    void loadToleratesUnknownTierAndDevice() {
        BrewingStatsData data = new BrewingStatsData();
        UUID id = UUID.randomUUID();
        data.getOrCreate(id).record(BeverageTier.T2, BeverageDevice.KEG, "brewinandchewin:beer", 1, 1L);
        CompoundTag tag = data.save(new CompoundTag(), provider());

        // Corrupt the tier/device maps with unknown enum names.
        CompoundTag players = tag.getCompound("players");
        CompoundTag player = players.getCompound(id.toString());
        player.put("tierCounts", unknownEnumTag("TIER_FUTURE"));
        player.put("deviceCounts", unknownEnumTag("DEVICE_FUTURE"));

        BrewingStatsData loaded = BrewingStatsData.load(tag, provider());
        PlayerBrewingStats loadedStats = loaded.get(id);
        assertNotNull(loadedStats);
        // Unknown enum keys are skipped; counters that survive still hold.
        assertTrue(loadedStats.getTierCounts().isEmpty());
        assertTrue(loadedStats.getDeviceCounts().isEmpty());
        assertEquals(1, loadedStats.getTotalBrewingEvents());
    }

    private static CompoundTag unknownEnumTag(String name) {
        CompoundTag c = new CompoundTag();
        c.putInt(name, 1);
        return c;
    }
}
