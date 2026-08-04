package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
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
 * Cookery wok-style pot.
 *
 * <p>{@code takeOutProduct} returns {@code false} when nothing was taken
 * (wrong status, shovel mis-click), so the event is only posted when:
 * <ul>
 *   <li>the HEAD status was {@code FINISHED} (burnt dishes are excluded);</li>
 *   <li>the RETURN value is {@code true} (a real take-out happened).</li>
 * </ul>
 *
 * <p>Applied only when {@code kaleidoscope_cookery} is installed.
 */
@Mixin(PotBlockEntity.class)
public abstract class PotBlockEntityMixin {

    @Unique
    private int tcth$status = -1;

    @Inject(method = "takeOutProduct", at = @At("HEAD"))
    private void tcth$captureStatus(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        this.tcth$status = ((IPot) (Object) this).getStatus();
    }

    @Inject(method = "takeOutProduct", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        if (this.tcth$status != IPot.FINISHED) {
            return;
        }
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
        KaleidoscopeDishAdapter.onDishTaken(player, stack, CookingDevice.KALEIDOSCOPE_COOKING_POT,
                serverLevel, self.getBlockPos());
    }
}
