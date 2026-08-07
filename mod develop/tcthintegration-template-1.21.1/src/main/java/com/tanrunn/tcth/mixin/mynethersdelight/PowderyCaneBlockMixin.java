package com.tanrunn.tcth.mixin.mynethersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.soytutta.mynethersdelight.common.block.crops.PowderyCaneBlock;
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
 * Special-case right-click harvest for My Nether's Delight powdery cane
 * ({@code mynethersdelight:powdery_cane}).
 *
 * <p>JAR 1.10.4 control flow (useItemOn): harvest only when
 * {@code age > 1 && lit} and the held item is a knife or shears; resets
 * {@code lit=false, age=0, pressure=0}; returns
 * {@code ItemInteractionResult.sidedSuccess(level.isClientSide)} (server →
 * {@link ItemInteractionResult#CONSUME}). Bare-hand paths explode and must
 * not publish a harvest event (wrong tool / empty hand never reaches the
 * success return with a proper harvest reset that this handler requires).
 *
 * <p><strong>RETURN-only</strong>, no snapshot fields.
 */
@Mixin(PowderyCaneBlock.class)
public abstract class PowderyCaneBlockMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void tcth$settlePowderyCane(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                        Player player, InteractionHand hand, BlockHitResult hit,
                                        CallbackInfoReturnable<ItemInteractionResult> cir) {
        HarvestInteractionMixinSupport.handlePowderyCaneReturn(level, pos, state, player,
                HarvestInteractionMixinSupport.isSuccess(cir.getReturnValue()));
    }
}
