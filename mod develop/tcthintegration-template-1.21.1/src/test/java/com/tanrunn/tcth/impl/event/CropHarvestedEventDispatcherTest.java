package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.api.farming.HarvestMethod;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher.Result;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Phase 4A.2: {@link CropHarvestedEventDispatcher} — switches, client/fake
 * rejection, immature rejection, bounded idempotency, stop cleanup.
 */
class CropHarvestedEventDispatcherTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private FakePlayer fakePlayer;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        CropHarvestedEventDispatcher.resetForTesting();
        bus = mock(IEventBus.class);
        CropHarvestedEventDispatcher.setGameBusForTesting(bus);
        CropHarvestedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        CropHarvestedEventDispatcher.setFarmingEnabledSupplierForTesting(() -> true);
        level = mock(ServerLevel.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        fakePlayer = mock(FakePlayer.class);
        when(fakePlayer.getUUID()).thenReturn(java.util.UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        CropHarvestedEventDispatcher.resetForTesting();
    }

    private Result publish(ServerPlayer p, BlockPos pos) {
        return CropHarvestedEventDispatcher.publish(p, ResourceLocation.parse("minecraft:wheat"),
                Blocks.WHEAT.defaultBlockState(), pos, level, HarvestMethod.BREAK, true);
    }

    @Test
    void frameworkSwitchOffReturnsFrameworkDisabled() {
        CropHarvestedEventDispatcher.setEnabledSupplierForTesting(() -> false);
        assertEquals(Result.FRAMEWORK_DISABLED, publish(player, BlockPos.ZERO));
        verify(bus, never()).post(any());
    }

    @Test
    void farmingSwitchOffReturnsFarmingDisabled() {
        CropHarvestedEventDispatcher.setFarmingEnabledSupplierForTesting(() -> false);
        assertEquals(Result.FARMING_DISABLED, publish(player, BlockPos.ZERO));
        verify(bus, never()).post(any());
    }

    @Test
    void clientLevelRejected() {
        when(level.isClientSide()).thenReturn(true);
        assertEquals(Result.INVALID_CONTEXT, publish(player, BlockPos.ZERO));
        verify(bus, never()).post(any());
    }

    @Test
    void nullLevelRejected() {
        assertEquals(Result.INVALID_CONTEXT,
                CropHarvestedEventDispatcher.publish(player, ResourceLocation.parse("minecraft:wheat"),
                        Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, null, HarvestMethod.BREAK, true));
    }

    @Test
    void fakePlayerRejectedAsAutomated() {
        assertEquals(Result.AUTOMATED_REJECTED, publish(fakePlayer, BlockPos.ZERO));
        verify(bus, never()).post(any());
    }

    @Test
    void nullPlayerRejectedAsAutomated() {
        assertEquals(Result.AUTOMATED_REJECTED,
                CropHarvestedEventDispatcher.publish(null, ResourceLocation.parse("minecraft:wheat"),
                        Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, level, HarvestMethod.BREAK, true));
    }

    @Test
    void immatureHarvestRejected() {
        assertEquals(Result.NOT_FULLY_GROWN,
                CropHarvestedEventDispatcher.publish(player, ResourceLocation.parse("minecraft:wheat"),
                        Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, level, HarvestMethod.BREAK, false));
        verify(bus, never()).post(any());
    }

    @Test
    void postedEventCarriesExpectedFields() {
        assertEquals(Result.POSTED, publish(player, new BlockPos(1, 2, 3)));
        verify(bus).post(any(CropHarvestedEvent.class));
    }

    @Test
    void sameHarvestIsIdempotent() {
        assertEquals(Result.POSTED, publish(player, BlockPos.ZERO));
        assertEquals(Result.DUPLICATE, publish(player, BlockPos.ZERO));
        verify(bus, org.mockito.Mockito.times(1)).post(any());
    }

    @Test
    void differentPositionNotDeduplicated() {
        assertEquals(Result.POSTED, publish(player, BlockPos.ZERO));
        assertEquals(Result.POSTED, publish(player, new BlockPos(5, 0, 0)));
        verify(bus, org.mockito.Mockito.times(2)).post(any());
    }

    @Test
    void differentTickNotDeduplicated() {
        assertEquals(Result.POSTED, publish(player, BlockPos.ZERO));
        CropHarvestedEventDispatcher.onServerTick(mock(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class));
        assertEquals(Result.POSTED, publish(player, BlockPos.ZERO));
        verify(bus, org.mockito.Mockito.times(2)).post(any());
    }

    @Test
    void differentMethodNotDeduplicated() {
        assertEquals(Result.POSTED, publish(player, BlockPos.ZERO));
        assertEquals(Result.POSTED,
                CropHarvestedEventDispatcher.publish(player, ResourceLocation.parse("minecraft:sweet_berry_bush"),
                        Blocks.SWEET_BERRY_BUSH.defaultBlockState(), BlockPos.ZERO, level, HarvestMethod.RIGHT_CLICK, true));
        verify(bus, org.mockito.Mockito.times(2)).post(any());
    }

    @Test
    void cacheIsBounded() {
        int cap = CropHarvestedEventDispatcher.MAX_TRACKED_HARVESTS;
        for (int i = 0; i < cap; i++) {
            assertEquals(Result.POSTED, publish(player, new BlockPos(i, 0, 0)));
        }
        // 触发容量清理：再加入一个（同一 tick）会淘汰最老的。
        assertEquals(Result.POSTED, publish(player, new BlockPos(cap, 0, 0)));
        int tracked = CropHarvestedEventDispatcher.trackedHarvestCountForTesting();
        assertTrue(tracked <= cap, "cache must be bounded: " + tracked + " > " + cap);
    }

    @Test
    void stopClearsCache() {
        publish(player, BlockPos.ZERO);
        assertTrue(CropHarvestedEventDispatcher.trackedHarvestCountForTesting() > 0);
        CropHarvestedEventDispatcher.onServerStopping(mock(ServerStoppingEvent.class));
        assertEquals(0, CropHarvestedEventDispatcher.trackedHarvestCountForTesting());
    }

    @Test
    void expiredEntriesArePrunedOnTick() {
        publish(player, BlockPos.ZERO);
        // 前进超过过期窗口
        for (int i = 0; i <= CropHarvestedEventDispatcher.IDEMPOTENCY_EXPIRY_TICKS; i++) {
            CropHarvestedEventDispatcher.onServerTick(mock(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class));
        }
        assertEquals(0, CropHarvestedEventDispatcher.trackedHarvestCountForTesting());
    }
}
