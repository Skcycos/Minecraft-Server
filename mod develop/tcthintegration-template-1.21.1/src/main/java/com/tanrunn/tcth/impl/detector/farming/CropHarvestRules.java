package com.tanrunn.tcth.impl.detector.farming;

import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Harvest maturity rules for the unified crop-harvest event.
 *
 * <p>Rule order (fail-closed):
 * <ol>
 *   <li>explicit exclusion (block tag {@code tcth:farmer_excluded} or
 *       {@code StemBlock}) — crop stems never reward;</li>
 *   <li>vertical crops (block tag {@code tcth:farmer_vertical_crops} or sugar
 *       cane / cactus) — only the upper segment (the block directly below is
 *       the same crop) counts; breaking the base is not a harvest;</li>
 *   <li>{@code CropBlock} — maturity via the block's own max age
 *       ({@link CropBlock#isMaxAge(BlockState)});</li>
 *   <li>native non-{@code CropBlock} crops with an {@code age} property
 *       (cocoa, nether wart) — mature only when the current age equals the
 *       legal maximum;</li>
 *   <li>the explicit harvestables tag ({@code tcth:farmer_harvestables}) —
 *       blocks with an {@code age} property must be at the legal maximum,
 *       blocks without one are treated as mature;</li>
 *   <li>{@code CropBlock} — maturity via the block's own max age
 *       ({@link CropBlock#isMaxAge(BlockState)});</li>
 *   <li>anything else is not harvestable (fail-closed: decorative plants,
 *       flowers, leaves, grass, logs…).</li>
 * </ol>
 *
 * <p>Tags live in the tcth-farmer data pack; optional mod entries use
 * {@code "required": false}. Tags select the crop, maturity itself is always
 * verified by code.
 */
public final class CropHarvestRules {

    /** Explicit harvest white-list (additional non-CropBlock crops). */
    public static final TagKey<Block> FARMER_HARVESTABLES =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tcth", "farmer_harvestables"));
    /** Vertical crops where only the upper segment counts (sugar cane, cactus). */
    public static final TagKey<Block> FARMER_VERTICAL_CROPS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tcth", "farmer_vertical_crops"));
    /** Hard exclusions (crop stems, decorative plants, non-harvest blocks). */
    public static final TagKey<Block> FARMER_EXCLUDED =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("tcth", "farmer_excluded"));

    private CropHarvestRules() {
    }

    /** Outcome of the maturity assessment. */
    public enum Assessment {
        NOT_HARVESTABLE(false, false),
        HARVESTABLE_MATURE(true, true),
        HARVESTABLE_IMMATURE(true, false);

        public final boolean harvestable;
        public final boolean fullyGrown;

        Assessment(boolean harvestable, boolean fullyGrown) {
            this.harvestable = harvestable;
            this.fullyGrown = fullyGrown;
        }
    }

    /**
     * Assesses whether {@code state} at {@code pos} in {@code level} is a
     * harvestable, mature crop.
     */
    public static Assessment assess(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        Holder<Block> holder = block.builtInRegistryHolder();

        // 1. Explicit exclusions first.
        if (holder.is(FARMER_EXCLUDED) || block instanceof StemBlock) {
            return Assessment.NOT_HARVESTABLE;
        }

        // 2. Vertical crops: only the upper segment counts.
        if (holder.is(FARMER_VERTICAL_CROPS) || block instanceof SugarCaneBlock || block instanceof CactusBlock) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(block) ? Assessment.HARVESTABLE_MATURE : Assessment.NOT_HARVESTABLE;
        }

        // 3. CropBlock: real max age (wheat, carrots, potatoes, beetroots, FD
        //    cabbage/onion/rice panicles/tomato, KC lettuce/chili/rice, …).
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state) ? Assessment.HARVESTABLE_MATURE : Assessment.HARVESTABLE_IMMATURE;
        }

        // 4. Native non-CropBlock crops with an age property (cocoa, nether wart).
        if (block instanceof CocoaBlock || block instanceof NetherWartBlock) {
            return atMaxAge(state) ? Assessment.HARVESTABLE_MATURE : Assessment.HARVESTABLE_IMMATURE;
        }

        // 5. Explicit harvestables tag (mod/custom non-CropBlock crops; age-verified).
        if (holder.is(FARMER_HARVESTABLES)) {
            if (findAgeProperty(state) != null) {
                return atMaxAge(state) ? Assessment.HARVESTABLE_MATURE : Assessment.HARVESTABLE_IMMATURE;
            }
            return Assessment.HARVESTABLE_MATURE;
        }

        // 6. Fail-closed. (Pumpkin/melon fruit blocks were previously treated
        //    as always-mature; phase 4A.3 user decision removed them because
        //    placed fruits cannot be distinguished from naturally grown ones
        //    and placement-break cycles could farm XP. See phase 4A.3 report.)
        return Assessment.NOT_HARVESTABLE;
    }

    /** Whether the {@code age} property (if any) is at its legal maximum. */
    public static boolean atMaxAge(BlockState state) {
        IntegerProperty age = findAgeProperty(state);
        if (age == null) {
            return false;
        }
        int current = state.getValue(age);
        int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
        return current == max;
    }

    /** The {@code age} IntegerProperty of the state, or {@code null}. */
    static IntegerProperty findAgeProperty(BlockState state) {
        for (var property : state.getProperties()) {
            if (property.getName().equals("age") && property instanceof IntegerProperty intProp) {
                return intProp;
            }
        }
        return null;
    }
}
