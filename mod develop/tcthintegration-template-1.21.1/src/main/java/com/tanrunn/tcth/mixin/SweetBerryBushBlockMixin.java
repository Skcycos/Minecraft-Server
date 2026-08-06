package com.tanrunn.tcth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click harvest detection for vanilla sweet berry bushes.
 *
 * <p><strong>RETURN-only</strong> (no {@code @Unique} snapshot field, no HEAD):
 * the original method arguments plus the return value are passed to the
 * stateless {@link HarvestInteractionMixinSupport}, which re-reads the
 * post-harvest state and requires a strict age decrease (age 3 → 1). If the
 * original method throws, RETURN never runs and nothing is left behind.
 *
 * <p><strong>Server-side success value</strong>: vanilla
 * {@code SweetBerryBushBlock.useWithoutItem} returns
 * {@code InteractionResult.sidedSuccess(level.isClientSide)}, which on the
 * server evaluates to {@link InteractionResult#CONSUME} (bytecode-verified),
 * not {@code SUCCESS}. Both values therefore count as a successful pick on
 * the server; {@link InteractionResult#PASS} (immature bush) does not.
 */
@Mixin(SweetBerryBushBlock.class)
public abstract class SweetBerryBushBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void tcth$settleRightClick(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        boolean success = result == InteractionResult.SUCCESS || result == InteractionResult.CONSUME;
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player, success);
    }
}
