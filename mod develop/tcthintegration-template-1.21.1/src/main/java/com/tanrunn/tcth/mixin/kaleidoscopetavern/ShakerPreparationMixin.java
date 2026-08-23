package com.tanrunn.tcth.mixin.kaleidoscopetavern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.ysbbbbbb.kaleidoscopetavern.item.ShakerItem;
import com.tanrunn.tcth.impl.compat.kaleidoscopetavern.KaleidoscopeBrewingAdapter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Publishes the real result after a player completes a Tavern Shaker recipe. */
@Mixin(ShakerItem.class)
public abstract class ShakerPreparationMixin {

    @Inject(method = "releaseUsing",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/ysbbbbbb/kaleidoscopetavern/item/ShakerItem;handRecipe(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)V",
                    shift = At.Shift.AFTER))
    private void tcth$onRecipeHanded(ItemStack shaker, Level level, LivingEntity entity,
                                     int timeLeft, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        KaleidoscopeBrewingAdapter.onShakerPrepared(
                player, ShakerItem.getResult(shaker), serverLevel, null);
    }
}
