package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Unit tests for {@link GunnerStatsData} (phase 5A).
 *
 * <p>Covers: player cap, NBT round-trip, unknown uuid skip.
 */
class GunnerStatsDataTest {

    private static HolderLookup.Provider provider() {
        MinecraftTestBootstrap.bootStrap();
        return HolderLookup.Provider.create(java.util.stream.Stream.empty());
    }

    @Test
    void newPlayerIsCreated() {
        GunnerStatsData data = new GunnerStatsData();
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats = data.getOrCreate(id);
        assertNotNull(stats);
        assertEquals(1, data.getPlayers().size());
    }

    @Test
    void getReturnsNullForUnknownPlayer() {
        GunnerStatsData data = new GunnerStatsData();
        assertNull(data.get(UUID.randomUUID()));
    }

    @Test
    void existingPlayerIsReturned() {
        GunnerStatsData data = new GunnerStatsData();
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats1 = data.getOrCreate(id);
        PlayerGunnerStats stats2 = data.getOrCreate(id);
        assertTrue(stats1 == stats2, "existing player must return the same instance");
    }

    @Test
    void playerCapIsEnforced() {
        GunnerStatsData data = new GunnerStatsData();
        for (int i = 0; i < 1024; i++) {
            assertNotNull(data.getOrCreate(UUID.randomUUID()));
        }
        assertNull(data.getOrCreate(UUID.randomUUID()));
        assertEquals(1024, data.getPlayers().size());
    }

    @Test
    void existingPlayerSurvivesCap() {
        GunnerStatsData data = new GunnerStatsData();
        UUID existingId = UUID.randomUUID();
        data.getOrCreate(existingId);
        for (int i = 0; i < 1023; i++) {
            data.getOrCreate(UUID.randomUUID());
        }
        assertNotNull(data.getOrCreate(existingId));
    }

    @Test
    void nbtRoundTripPreservesData() {
        GunnerStatsData data = new GunnerStatsData();
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats = data.getOrCreate(id);
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:umax_pistol", "minecraft:skeleton", GunTargetTier.ELITE, 20.0f, 2000L);

        CompoundTag tag = data.save(new CompoundTag(), provider());

        GunnerStatsData loaded = GunnerStatsData.load(tag, provider());
        PlayerGunnerStats loadedStats = loaded.get(id);
        assertNotNull(loadedStats);
        assertEquals(2, loadedStats.getTotalGunKills());
        assertEquals(1, loadedStats.getCommonKills());
        assertEquals(1, loadedStats.getEliteKills());
    }

    @Test
    void loadSkipsUnknownUuid() {
        GunnerStatsData data = new GunnerStatsData();
        CompoundTag playersTag = new CompoundTag();
        CompoundTag playerTag = new CompoundTag();
        playerTag.putInt("totalGunKills", 5);
        playersTag.put("not-a-uuid", playerTag);
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("players", playersTag);

        GunnerStatsData loaded = GunnerStatsData.load(tag, provider());
        assertEquals(0, loaded.getPlayers().size());
    }
}
