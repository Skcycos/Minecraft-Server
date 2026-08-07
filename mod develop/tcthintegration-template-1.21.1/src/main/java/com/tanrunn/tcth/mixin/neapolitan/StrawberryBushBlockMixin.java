package com.tanrunn.tcth.mixin.neapolitan;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.detector.farming.HarvestInteractionMixinSupport;
import com.teamabnormals.neapolitan.common.block.StrawberryBushBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click harvest detection for Neapolitan strawberry bushes
 * ({@code neapolitan:strawberry_bush}).
 *
 * <p><strong>RETURN-only</strong> (no {@code @Unique} snapshot field, no HEAD).
 * JAR 6.0.1 bytecode: mature age==6 resets age to 1 and returns
 * {@code InteractionResult.sidedSuccess(level.isClientSide)} (server →
 * {@link InteractionResult#CONSUME}). Ordinary red and white strawberries
 * share the same block (WHITE property); both settle through this path.
 */
@Mixin(StrawberryBushBlock.class)
public abstract class StrawberryBushBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("RETURN"))
    private void tcth$settleRightClick(BlockState state, Level level, BlockPos pos, Player player,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        HarvestInteractionMixinSupport.handleReturn(level, pos, state, player,
                HarvestInteractionMixinSupport.isSuccess(cir.getReturnValue()));
    }
}
