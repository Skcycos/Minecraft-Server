package com.tanrunn.tcth.mixin.kaleidoscope;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.impl.compat.kaleidoscope.KaleidoscopeDishAdapter;

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

    @Inject(method = "takeFood", at = @At("HEAD"))
    private void tcth$snapshot(Level level, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        NonNullList<ItemStack> items = ((SteamerBlockEntity) (Object) this).getItems();
        this.tcth$before = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            this.tcth$before[i] = items.get(i).copy();
        }
    }

    @Inject(method = "takeFood", at = @At("RETURN"))
    private void tcth$afterTake(Level level, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || this.tcth$before == null) {
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
    }
}
