package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.impl.compat.kaleidoscope.KaleidoscopeDishAdapter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fires a TCTH dish event when a dish is really taken out of a Kaleidoscope
 * Cookery stockpot.
 *
 * <p>{@code takeOutProduct} returns {@code false} when nothing was taken
 * (lid on, wrong status, empty result); the event is only posted when the HEAD
 * status was {@code FINISHED} and the RETURN value is {@code true}.
 *
 * <p>Applied only when {@code kaleidoscope_cookery} is installed.
 */
@Mixin(StockpotBlockEntity.class)
public abstract class StockpotBlockEntityMixin {

    @Unique
    private int tcth$status = -1;

    @Inject(method = "takeOutProduct", at = @At("HEAD"))
    private void tcth$captureStatus(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        this.tcth$status = ((IStockpot) (Object) this).getStatus();
    }

    @Inject(method = "takeOutProduct", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        if (this.tcth$status != IStockpot.FINISHED) {
            return;
        }
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
        KaleidoscopeDishAdapter.onDishTaken(player, stack, CookingDevice.KALEIDOSCOPE_STOCKPOT,
                serverLevel, self.getBlockPos());
    }
}
