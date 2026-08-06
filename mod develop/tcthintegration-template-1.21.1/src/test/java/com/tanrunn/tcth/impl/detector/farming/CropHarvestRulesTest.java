package com.tanrunn.tcth.impl.detector.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.impl.detector.farming.CropHarvestRules.Assessment;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Phase 4A.2: maturity rules of {@link CropHarvestRules} — the real class
 * hierarchy (CropBlock max age, age-property crops, always-mature fruits,
 * vertical-crop upper segment, stem/decorative/unknown exclusions).
 */
class CropHarvestRulesTest {

    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private Assessment assess(BlockState state) {
        level = Mockito.mock(ServerLevel.class);
        return CropHarvestRules.assess(level, BlockPos.ZERO, state);
    }

    private Assessment assessWithBelow(BlockState state, BlockState below) {
        level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.getBlockState(BlockPos.ZERO.below())).thenReturn(below);
        return CropHarvestRules.assess(level, BlockPos.ZERO, state);
    }

    // ---- CropBlock: real max age ----

    @Test
    void wheatMatureAndImmature() {
        assertEquals(Assessment.HARVESTABLE_IMMATURE, assess(Blocks.WHEAT.defaultBlockState()), "age=0 未成熟");
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)), "age=7 成熟");
    }

    @Test
    void carrotsPotatoesBeetrootsMatureAndImmature() {
        for (BlockState immature : new BlockState[]{
                Blocks.CARROTS.defaultBlockState(),
                Blocks.POTATOES.defaultBlockState(),
                Blocks.BEETROOTS.defaultBlockState()}) {
            assertEquals(Assessment.HARVESTABLE_IMMATURE, assess(immature));
        }
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7)));
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7)));
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3)));
    }

    // ---- age-property non-CropBlock crops (cocoa, nether wart) ----

    @Test
    void cocoaMatureAndImmature() {
        assertEquals(Assessment.HARVESTABLE_IMMATURE, assess(Blocks.COCOA.defaultBlockState()));
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2)));
    }

    @Test
    void netherWartMatureAndImmature() {
        assertEquals(Assessment.HARVESTABLE_IMMATURE, assess(Blocks.NETHER_WART.defaultBlockState()));
        assertEquals(Assessment.HARVESTABLE_MATURE,
                assess(Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3)));
    }

    // ---- always-mature fruits ----

    @Test
    void pumpkinAndMelonAreNotHarvestable() {
        // 阶段 4A.3 用户决策：南瓜/西瓜不再发收获事件（放置-破坏可刷经验，
        // 方块无法区分放置/生长来源）。
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.PUMPKIN.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.MELON.defaultBlockState()));
    }

    // ---- stems excluded ----

    @Test
    void cropStemsAreExcluded() {
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.PUMPKIN_STEM.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.MELON_STEM.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.ATTACHED_MELON_STEM.defaultBlockState()));
    }

    // ---- vertical crops: only the upper segment ----

    @Test
    void sugarCaneUpperSegmentAllowedBaseExcluded() {
        BlockState cane = Blocks.SUGAR_CANE.defaultBlockState();
        // 上层：下方仍是甘蔗 → 奖励
        assertEquals(Assessment.HARVESTABLE_MATURE, assessWithBelow(cane, cane));
        // 基部：下方是泥土 → 不奖励
        assertEquals(Assessment.NOT_HARVESTABLE, assessWithBelow(cane, Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void cactusUpperSegmentAllowedBaseExcluded() {
        BlockState cactus = Blocks.CACTUS.defaultBlockState();
        assertEquals(Assessment.HARVESTABLE_MATURE, assessWithBelow(cactus, cactus));
        assertEquals(Assessment.NOT_HARVESTABLE, assessWithBelow(cactus, Blocks.SAND.defaultBlockState()));
    }

    // ---- fail-closed ----

    @Test
    void decorativePlantsAndUnknownBlocksFailClosed() {
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.DANDELION.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.OAK_LOG.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.STONE.defaultBlockState()));
        assertEquals(Assessment.NOT_HARVESTABLE, assess(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
                "sweet berry bush is a right-click crop; breaking it is not a harvest");
    }

    @Test
    void assessmentFlagsConsistent() {
        assertTrue(Assessment.HARVESTABLE_MATURE.harvestable && Assessment.HARVESTABLE_MATURE.fullyGrown);
        assertTrue(Assessment.HARVESTABLE_IMMATURE.harvestable && !Assessment.HARVESTABLE_IMMATURE.fullyGrown);
        assertFalse(Assessment.NOT_HARVESTABLE.harvestable);
    }
}
