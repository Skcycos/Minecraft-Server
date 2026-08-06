package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import vectorwing.farmersdelight.common.block.TomatoBlock;

/**
 * Right-click harvest detection for Farmers Delight tomatoes (both the
 * {@code farmersdelight:tomatoes} vine and {@code farmersdelight:tomatoes_on_rope}
 * hanging variant, which share {@link TomatoBlock}).
 *
 * <p><strong>RETURN-only</strong> (no {@code @Unique} snapshot field): the
 * original arguments plus the success result are passed to the stateless
 * {@link HarvestInteractionMixinSupport}; the strict age-decrease rule applies
 * (mature → 0). {@code useItemOn} only handles bone meal and otherwise falls
 * through, so a single interaction settles at most once.
 */
@Mixin(TomatoBlock.class)
public abstract class TomatoBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void tcth$settleRightClick(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player,
                cir.getReturnValue() == InteractionResult.SUCCESS);
    }
}
