package com.tanrunn.tcth.mixin.mynethersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.soytutta.mynethersdelight.common.block.crops.PowderyCannonBlock;
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
 * Special-case right-click harvest for My Nether's Delight powdery cannon
 * ({@code mynethersdelight:powdery_cannon}).
 *
 * <p>JAR 1.10.4 control flow (useItemOn): harvest only when {@code lit} and
 * knife/shears; sets {@code lit=false}; returns
 * {@code ItemInteractionResult.sidedSuccess(level.isClientSide)} (server →
 * CONSUME). Age is not a maturity criterion. Bare-hand explosion is a
 * separate {@code useWithoutItem} path and is never settled here.
 *
 * <p><strong>RETURN-only</strong>, no snapshot fields. Must not be placed in
 * generic vertical_crops / harvestables tags.
 */
@Mixin(PowderyCannonBlock.class)
public abstract class PowderyCannonBlockMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tcth$settlePowderyCannon(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit,
                                          CallbackInfoReturnable<ItemInteractionResult> cir) {
        HarvestInteractionMixinSupport.handlePowderyCannonReturn(level, pos, state, player,
                HarvestInteractionMixinSupport.isSuccess(cir.getReturnValue()));
    }
}
