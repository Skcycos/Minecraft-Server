package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.ChiliCropBlock;
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
 * Right-click harvest detection for {@code KaleidoscopeCookery.ChiliCropBlock},
 * which overrides {@code useItemOn} (sickle check → mature drop →
 * {@code BaseCropBlock.onUseBreakCrop} age reset → SUCCESS) and does
 * <em>not</em> call the base method, so the {@code BaseCropBlock} mixin alone
 * cannot capture it.
 *
 * <p><strong>RETURN-only</strong> (no {@code @Unique} snapshot field): the
 * original arguments plus the success result are passed to the stateless
 * {@link HarvestInteractionMixinSupport}; the strict age-decrease rule applies
 * (age 7 → 5).
 */
@Mixin(ChiliCropBlock.class)
public abstract class KcChiliCropBlockMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tcth$settleRightClick(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                       Player player, InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<ItemInteractionResult> cir) {
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player,
                cir.getReturnValue() == ItemInteractionResult.SUCCESS);
    }
}
