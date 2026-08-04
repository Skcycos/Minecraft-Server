package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Unit tests for {@link CookingStatsData} storage binding (cross-dimension
 * merge via the overworld data storage).
 */
class CookingStatsDataTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private DimensionDataStorage mockOverworldStorage(CookingStatsData real) {
        DimensionDataStorage storage = Mockito.mock(DimensionDataStorage.class);
        Mockito.when(storage.computeIfAbsent(Mockito.any(), Mockito.anyString()))
                .thenAnswer(inv -> real);
        return storage;
    }

    @Test
    void currentBindsToOverworldDataStorageAcrossDimensions() {
        ServerLevel overworld = Mockito.mock(ServerLevel.class);
        ServerLevel nether = Mockito.mock(ServerLevel.class);
        ServerLevel end = Mockito.mock(ServerLevel.class);
        MinecraftServer server = Mockito.mock(MinecraftServer.class);
        Mockito.when(overworld.getServer()).thenReturn(server);
        Mockito.when(nether.getServer()).thenReturn(server);
        Mockito.when(end.getServer()).thenReturn(server);
        Mockito.when(server.overworld()).thenReturn(overworld);

        CookingStatsData real = new CookingStatsData();
        DimensionDataStorage storage = mockOverworldStorage(real);
        Mockito.when(overworld.getDataStorage()).thenReturn(storage);

        // Events in any dimension resolve to the same overworld-bound store.
        assertSame(real, CookingStatsData.current(overworld));
        assertSame(real, CookingStatsData.current(nether));
        assertSame(real, CookingStatsData.current(end));

        // Recording from the nether dimension lands in the shared store.
        UUID uuid = UUID.randomUUID();
        CookingStatsData.current(nether).getOrCreate(uuid)
                .record(com.tanrunn.tcth.api.cooking.CookingDevice.FURNACE, null,
                        com.tanrunn.tcth.api.cooking.DishQuality.UNKNOWN, "minecraft:cooked_beef", 1, 1L);
        assertEquals(1, CookingStatsData.current(end).get(uuid).getTotalDishesCooked());
    }
}
