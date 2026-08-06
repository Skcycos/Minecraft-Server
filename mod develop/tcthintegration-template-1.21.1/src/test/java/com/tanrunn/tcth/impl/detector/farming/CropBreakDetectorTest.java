package com.tanrunn.tcth.impl.detector.farming;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Phase 4A.2: {@link CropBreakDetector} — no-player / fake-player rejection,
 * immature suppression and mature publish-once for break harvests.
 */
class CropBreakDetectorTest {

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
        when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        player = mock(ServerPlayer.class);
        fakePlayer = mock(FakePlayer.class);
    }

    @AfterEach
    void tearDown() {
        CropHarvestedEventDispatcher.resetForTesting();
    }

    private void breakBlock(Player breaker, BlockState state) {
        CropBreakDetector.onBreak(new BlockEvent.BreakEvent(level, BlockPos.ZERO, state, breaker));
    }

    @Test
    void nonServerPlayerIgnored() {
        Player plain = mock(Player.class);
        breakBlock(plain, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        verify(bus, never()).post(any());
    }

    @Test
    void fakePlayerIgnored() {
        breakBlock(fakePlayer, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        verify(bus, never()).post(any());
    }

    @Test
    void immatureCropIgnored() {
        breakBlock(player, Blocks.WHEAT.defaultBlockState()); // age 0
        verify(bus, never()).post(any());
    }

    @Test
    void matureCropPublishesBreakEvent() {
        breakBlock(player, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    @Test
    void cancelledEventIgnored() {
        // LOWEST + receiveCanceled=false：已被其他监听器取消的破坏不进入 handler；
        // handler 内仍防御性检查 isCanceled()。
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, BlockPos.ZERO,
                Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), player);
        event.setCanceled(true);
        CropBreakDetector.onBreak(event);
        verify(bus, never()).post(any());
    }
}
