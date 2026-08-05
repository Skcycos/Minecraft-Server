package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.kaleidoscope.KaleidoscopeDishAdapter;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fires a TCTH dish event when food is really taken from a Kaleidoscope
 * Cookery steamer.
 *
 * <p>{@code takeFood} returns {@code false} when nothing was taken and has no
 * result parameter, so the taken stack is recovered by comparing the item
 * slots before and after the call. The event is only posted when the RETURN
 * value is {@code true} and a matching slot actually lost items.
 *
 * <p>Applied only when {@code kaleidoscope_cookery} is installed.
 */
@Mixin(SteamerBlockEntity.class)
public abstract class SteamerBlockEntityMixin {

    @Unique
    private ItemStack[] tcth$before = null;

    @Unique
    private CookingSignature[] tcth$previousSignatures = null;

    @Unique
    private boolean[] tcth$hadPreviousSignatures = null;

    @Inject(method = "takeFood", at = @At("HEAD"))
    private void tcth$snapshot(Level level, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        NonNullList<ItemStack> items = ((SteamerBlockEntity) (Object) this).getItems();
        int n = items.size();
        this.tcth$previousSignatures = new CookingSignature[n];
        this.tcth$hadPreviousSignatures = new boolean[n];
        // 1) Save each slot's PREVIOUS signature state.
        for (int i = 0; i < n; i++) {
            CookingSignature prev = items.get(i).get(CookingSignatureComponents.type());
            this.tcth$previousSignatures[i] = prev;
            this.tcth$hadPreviousSignatures[i] = prev != null;
        }
        // 2) Sign each real dish slot (raw/undish slots are never signed).
        if (entity instanceof ServerPlayer serverPlayer) {
            for (int i = 0; i < n; i++) {
                ItemStack slot = items.get(i);
                if (!slot.isEmpty() && DishClassifier.isDish(slot)) {
                    DishSignatureService.sign(serverPlayer, slot);
                }
            }
        }
        // 3) THEN snapshot the (now signed) slots — the diff and the event's
        //    taken stack are produced from this signed snapshot.
        this.tcth$before = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            this.tcth$before[i] = items.get(i).copy();
        }
    }

    @Inject(method = "takeFood", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue()) {
                return; // restore handled in finally (slots not consumed)
            }
            if (this.tcth$before == null) {
                return;
            }
            NonNullList<ItemStack> after = ((SteamerBlockEntity) (Object) this).getItems();
            ItemStack taken = null;
            for (int i = 0; i < this.tcth$before.length; i++) {
                ItemStack before = this.tcth$before[i];
                if (before == null || before.isEmpty()) {
                    continue;
                }
                ItemStack now = i < after.size() ? after.get(i) : ItemStack.EMPTY;
                if (now.isEmpty() || now.getCount() < before.getCount()) {
                    taken = before.copy();
                    taken.setCount(before.getCount() - now.getCount());
                    break;
                }
            }
            if (taken == null || taken.isEmpty()) {
                return;
            }
            BlockEntity self = (BlockEntity) (Object) this;
            if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
            KaleidoscopeDishAdapter.onDishTaken(player, taken, CookingDevice.KALEIDOSCOPE_STEAMER,
                    serverLevel, self.getBlockPos());
        } finally {
            // Restore the pre-take signature state of any slot that was NOT
            // actually delivered to the player (successful deliveries emptied
            // their slots and keep the current chef's signature; failed or
            // exceptional paths left the slots untouched and must revert).
            restoreUndeliveredSlots();
            this.tcth$before = null;
            this.tcth$previousSignatures = null;
            this.tcth$hadPreviousSignatures = null;
        }
    }

    @Unique
    private void restoreUndeliveredSlots() {
        if (this.tcth$before == null || this.tcth$hadPreviousSignatures == null) {
            return;
        }
        SteamerBlockEntity self = (SteamerBlockEntity) (Object) this;
        NonNullList<ItemStack> items = self.getItems();
        for (int i = 0; i < this.tcth$hadPreviousSignatures.length && i < items.size(); i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty()) {
                continue; // delivered: the signed stack went to the player
            }
            if (this.tcth$hadPreviousSignatures[i]) {
                slot.set(CookingSignatureComponents.type(), this.tcth$previousSignatures[i]);
            } else {
                slot.remove(CookingSignatureComponents.type());
            }
        }
    }
}
