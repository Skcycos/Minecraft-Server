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
 * <p>{@code takeOutProduct(Level, LivingEntity, ItemStack)} — the third
 * parameter is the <em>held carrier</em>, <strong>not</strong> the dish. The
 * actual dish is captured at HEAD from {@code StockpotBlockEntity.getResult()}
 * (defensive copy). A stockpot serves one portion per take-out, so the event
 * carries {@code copyWithCount(1)}. The event is only posted when:
 * <ul>
 *   <li>the HEAD status was {@code FINISHED};</li>
 *   <li>the RETURN value is {@code true} (a real take-out happened);</li>
 *   <li>the result snapshot is non-empty.</li>
 * </ul>
 * The snapshot is always cleared at the end of the RETURN handler.
 *
 * <p>Applied only when {@code kaleidoscope_cookery} is installed.
 */
@Mixin(StockpotBlockEntity.class)
public abstract class StockpotBlockEntityMixin {

    @Unique
    private int tcth$status = -1;

    @Unique
    private ItemStack tcth$resultSnapshot = null;

    @Inject(method = "takeOutProduct", at = @At("HEAD"))
    private void tcth$captureStatusAndResult(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        StockpotBlockEntity self = (StockpotBlockEntity) (Object) this;
        this.tcth$status = ((IStockpot) self).getStatus();
        this.tcth$resultSnapshot = this.tcth$status == IStockpot.FINISHED ? self.getResult().copy() : null;
    }

    @Inject(method = "takeOutProduct", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue()) {
                return;
            }
            if (this.tcth$status != IStockpot.FINISHED || this.tcth$resultSnapshot == null || this.tcth$resultSnapshot.isEmpty()) {
                return;
            }
            BlockEntity self = (BlockEntity) (Object) this;
            if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
            KaleidoscopeDishAdapter.onDishTaken(player, this.tcth$resultSnapshot.copyWithCount(1),
                    CookingDevice.KALEIDOSCOPE_STOCKPOT, serverLevel, self.getBlockPos());
        } finally {
            this.tcth$resultSnapshot = null;
            this.tcth$status = -1;
        }
    }
}
