package com.tanrunn.tcth.impl.detector.farming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Phase 4A.2.1: right-click harvest adapter ({@link HarvestInteractionMixinSupport})
 * — stateless RETURN-only settlement with the strict age-decrease rule. A
 * plain {@code !current.equals(oldState)} is never accepted as harvest
 * evidence.
 */
class HarvestInteractionMixinSupportTest {

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

    private BlockState matureBush() {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
    }

    private BlockState immatureBush() {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
    }

    private void settle(BlockState oldState, BlockState current, Player who, boolean success) {
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(current);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, oldState, who, success);
    }

    // ---- gating ----

    @Test
    void clientContextNotTracked() {
        when(level.isClientSide()).thenReturn(true);
        settle(matureBush(), immatureBush(), player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void fakePlayerNotTracked() {
        settle(matureBush(), immatureBush(), fakePlayer, true);
        verify(bus, never()).post(any());
    }

    @Test
    void nullPlayerNotTracked() {
        settle(matureBush(), immatureBush(), null, true);
        verify(bus, never()).post(any());
    }

    @Test
    void stateWithoutAgePropertyNotSettled() {
        settle(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), player, true);
        verify(bus, never()).post(any());
    }

    // ---- strict age-decrease matrix ----

    @Test
    void matureWithStrictAgeDecreasePublishes() {
        settle(matureBush(), immatureBush(), player, true);
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    @Test
    void matureWithOtherPropertyChangeButSameAgeDoesNotPublish() {
        // 可可豆：年龄不变、其他属性（FACING）变化 → 不发布
        BlockState oldCocoa = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.AGE, 2).setValue(CocoaBlock.FACING, Direction.NORTH);
        BlockState currentCocoa = oldCocoa.setValue(CocoaBlock.FACING, Direction.SOUTH);
        settle(oldCocoa, currentCocoa, player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void matureWithSameAgeStateChangeDoesNotPublish() {
        // 成功返回但状态未变（同 age 同属性）→ 不发布
        settle(matureBush(), matureBush(), player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void matureReplacedByUnrelatedBlockDoesNotPublish() {
        settle(matureBush(), Blocks.AIR.defaultBlockState(), player, true);
        verify(bus, never()).post(any());
    }

    // ---- KC rice special rule: right-click harvest removes the block ----

    @Test
    void kcRiceMatureWithBlockRemovedPublishes() {
        // 专项规则（字节码证明）：KC 稻米右键收获会移除方块；成熟 + 移除 → 发布。
        try {
            // 用甜浆果模拟"移除型收获"作物，注入 fake ID 集合验证逻辑分支。
            com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport
                    .setRightClickRemoveCropsForTesting(java.util.Set.of("minecraft:sweet_berry_bush"));
            settle(matureBush(), Blocks.AIR.defaultBlockState(), player, true);
            verify(bus, times(1)).post(any(CropHarvestedEvent.class));
        } finally {
            com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport
                    .setRightClickRemoveCropsForTesting(
                            java.util.Set.of("kaleidoscope_cookery:rice_crop"));
        }
    }

    @Test
    void nonRemoveCropMatureWithBlockRemovedDoesNotPublish() {
        // 甜浆果不在移除集合：成熟 + 变 AIR → 不发布（专项不泛滥）。
        settle(matureBush(), Blocks.AIR.defaultBlockState(), player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void kcRiceDefaultRemoveSetContainsRice() {
        // 默认专项集合必须包含字节码证明的 kaleidoscope_cookery:rice_crop。
        java.lang.reflect.Field field;
        try {
            field = com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport.class
                    .getDeclaredField("RIGHT_CLICK_REMOVE_CROPS");
            field.setAccessible(true);
            java.util.Set<String> set = (java.util.Set<String>) field.get(null);
            assertTrue(set.contains("kaleidoscope_cookery:rice_crop"),
                    "default remove-on-harvest set must contain KC rice");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void immatureWithAgeChangeDoesNotPublish() {
        // 未成熟（age 1）→ age 0：年龄变化但不成熟 → 不发布
        settle(immatureBush(), Blocks.SWEET_BERRY_BUSH.defaultBlockState(), player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void successButStateUnchangedDoesNotPublish() {
        settle(matureBush(), matureBush(), player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void failureReturnWithAgeChangeDoesNotPublish() {
        settle(matureBush(), immatureBush(), player, false);
        verify(bus, never()).post(any());
    }

    @Test
    void ageIncreaseDoesNotPublish() {
        // 当前 age 更大（增长/异常）→ 不发布
        BlockState oldAge1 = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
        BlockState currentAge3 = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        settle(oldAge1, currentAge3, player, true);
        verify(bus, never()).post(any());
    }

    // ---- single-event semantics ----

    @Test
    void singleInteractionSettlesOnce() {
        settle(matureBush(), immatureBush(), player, true);
        // 同一交互只结算一次（RETURN-only：原方法只返回一次）
        verify(bus, times(1)).post(any());
    }

    @Test
    void failedThenSucceededPathsSettleOnce() {
        settle(matureBush(), immatureBush(), player, false); // 失败不发
        settle(matureBush(), immatureBush(), player, true);  // 成功发一次
        verify(bus, times(1)).post(any());
    }

    @Test
    void helperIsStateless() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/com/tanrunn/tcth/impl/detector/farming",
                        "HarvestInteractionMixinSupport.java")));
        assertFalse(source.contains("Snapshot"), "support helper must be stateless (no snapshot fields)");
    }
}
