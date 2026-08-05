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
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

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
 * <p>{@code takeOutProduct(Level, LivingEntity, ItemStack)} — the third
 * parameter is the <em>held shovel/carrier</em>, <strong>not</strong> the dish.
 * The actual dish is captured at HEAD from {@code PotBlockEntity.getResult()}
 * (defensive copy, real count). The event is only posted when:
 * <ul>
 *   <li>the HEAD status was {@code FINISHED} (burnt dishes are excluded);</li>
 *   <li>the RETURN value is {@code true} (a real take-out happened);</li>
 *   <li>the result snapshot is non-empty.</li>
 * </ul>
 * The snapshot is always cleared at the end of the RETURN handler so no state
 * leaks into later calls.
 *
 * <p>Applied only when {@code kaleidoscope_cookery} is installed.
 */
@Mixin(PotBlockEntity.class)
public abstract class PotBlockEntityMixin {

    @Unique
    private int tcth$status = -1;

    @Unique
    private ItemStack tcth$resultSnapshot = null;

    @Unique
    private CookingSignature tcth$previousSignature = null;

    @Unique
    private boolean tcth$hadPreviousSignature = false;

    @Inject(method = "takeOutProduct", at = @At("HEAD"))
    private void tcth$captureStatusAndResult(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        PotBlockEntity self = (PotBlockEntity) (Object) this;
        this.tcth$status = ((IPot) self).getStatus();
        ItemStack liveResult = self.getResult();
        if (this.tcth$status == IPot.FINISHED && liveResult != null && !liveResult.isEmpty()) {
            // Order matters: 1) save any previous signature, 2) sign the live
            // result, 3) THEN copy the signed result as the event snapshot so
            // the DishCookedEvent carries the same signature as the delivered
            // stack. On a failed take the previous signature is restored.
            this.tcth$previousSignature = liveResult.get(CookingSignatureComponents.type());
            this.tcth$hadPreviousSignature = this.tcth$previousSignature != null;
            if (entity instanceof ServerPlayer serverPlayer) {
                DishSignatureService.sign(serverPlayer, liveResult);
            }
            this.tcth$resultSnapshot = liveResult.copy();
        }
    }

    @Inject(method = "takeOutProduct", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue()) {
                // Failed take: the result was NOT delivered. Restore the
                // signature state from before this take-out (previous chef's
                // signature if there was one, otherwise remove ours).
                restorePreviousSignature();
                return;
            }
            if (this.tcth$status != IPot.FINISHED || this.tcth$resultSnapshot == null || this.tcth$resultSnapshot.isEmpty()) {
                return;
            }
            BlockEntity self = (BlockEntity) (Object) this;
            if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
            KaleidoscopeDishAdapter.onDishTaken(player, this.tcth$resultSnapshot, CookingDevice.KALEIDOSCOPE_COOKING_POT,
                    serverLevel, self.getBlockPos());
        } finally {
            this.tcth$resultSnapshot = null;
            this.tcth$status = -1;
            this.tcth$previousSignature = null;
            this.tcth$hadPreviousSignature = false;
        }
    }

    @Unique
    private void restorePreviousSignature() {
        PotBlockEntity self = (PotBlockEntity) (Object) this;
        ItemStack live = self.getResult();
        if (live == null || live.isEmpty()) {
            return;
        }
        if (this.tcth$hadPreviousSignature) {
            live.set(CookingSignatureComponents.type(), this.tcth$previousSignature);
        } else {
            live.remove(CookingSignatureComponents.type());
        }
    }
}
