package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.detector.farming.CropHarvestRules;
import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import vectorwing.farmersdelight.common.block.MushroomColonyBlock;

/**
 * Conditional right-click harvest detection for Farmer's Delight
 * {@link MushroomColonyBlock}, filtered by {@code tcth:farmer_colony_harvestables}.
 *
 * <p>Only My Nether's Delight colonies
 * ({@code mynethersdelight:warped_fungus_colony},
 * {@code mynethersdelight:crimson_fungus_colony}) are listed in that tag.
 * FD's own brown/red mushroom colonies are never rewarded. When My Nether's
 * Delight is absent the tag is empty ({@code required: false} entries) and
 * this mixin publishes nothing. The mixin never references My Nether's
 * Delight Java classes.
 *
 * <p>FD 1.3.2: harvest when {@code COLONY_AGE > 0} with shears (age-1) or
 * knife (age→0); returns {@code ItemInteractionResult.sidedSuccess(false)}
 * on the server (= CONSUME).
 *
 * <p><strong>RETURN-only</strong>, no snapshot fields.
 */
@Mixin(MushroomColonyBlock.class)
public abstract class MushroomColonyBlockMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tcth$settleColony(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                   Player player, InteractionHand hand, BlockHitResult hit,
                                   CallbackInfoReturnable<ItemInteractionResult> cir) {
        boolean tagAllowed = state.getBlock().builtInRegistryHolder()
                .is(CropHarvestRules.FARMER_COLONY_HARVESTABLES);
        HarvestInteractionMixinSupport.handleColonyReturn(level, pos, state, player,
                HarvestInteractionMixinSupport.isSuccess(cir.getReturnValue()), tagAllowed);
    }
}
