package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.BaseCropBlock;
import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click harvest detection for Kaleidoscope Cookery crops that inherit
 * the base harvest implementation, e.g. {@code RiceCropBlock} (which does not
 * declare its own {@code useItemOn}).
 *
 * <p><strong>RETURN-only</strong> (no {@code @Unique} snapshot field): the
 * original arguments plus the success result are passed to the stateless
 * {@link HarvestInteractionMixinSupport}; the strict age-decrease rule applies
 * (KC crops reset to age 5 via {@code BaseCropBlock.onUseBreakCrop}).
 *
 * <p>This mixin covers only crops that actually inherit the base method.
 * Subclasses that override {@code useItemOn} (e.g. {@code ChiliCropBlock})
 * need their own mixin; subclasses whose override is not a harvest path
 * (e.g. {@code LettuceCropBlock} returning
 * {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}) must not be claimed as
 * right-click-supported.
 */
@Mixin(BaseCropBlock.class)
public abstract class KcBaseCropBlockMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tcth$settleRightClick(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                       Player player, InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<ItemInteractionResult> cir) {
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player,
                cir.getReturnValue() == ItemInteractionResult.SUCCESS);
    }
}
