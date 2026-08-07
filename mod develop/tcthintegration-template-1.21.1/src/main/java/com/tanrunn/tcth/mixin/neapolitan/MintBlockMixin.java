package com.tanrunn.tcth.mixin.neapolitan;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;
import com.teamabnormals.neapolitan.common.block.MintBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click harvest detection for Neapolitan mint ({@code neapolitan:mint}).
 *
 * <p><strong>RETURN-only</strong>. JAR 6.0.1: mature age==4 resets age to 1 and
 * returns {@code InteractionResult.sidedSuccess(level.isClientSide)} (server →
 * CONSUME).
 */
@Mixin(MintBlock.class)
public abstract class MintBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void tcth$settleRightClick(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player,
                HarvestInteractionMixinSupport.isSuccess(cir.getReturnValue()));
    }
}
