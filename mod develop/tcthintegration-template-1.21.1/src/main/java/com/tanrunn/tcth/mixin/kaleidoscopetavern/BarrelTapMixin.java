package com.tanrunn.tcth.mixin.kaleidoscopetavern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.tanrunn.tcth.impl.compat.kaleidoscopetavern.KaleidoscopeBrewingAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Publishes the real Barrel output when a player passes the tap preflight. */
@Mixin(BarrelBlockEntity.class)
public abstract class BarrelTapMixin {

    @Inject(method = "canTapExtract", at = @At("RETURN"))
    private void tcth$onTapReady(Level level, BlockPos position, LivingEntity entity,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BarrelBlockEntity barrel = (BarrelBlockEntity) (Object) this;
        ItemStack result = barrel.getOutput().getStackInSlot(0).copy();
        ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        KaleidoscopeBrewingAdapter.onBarrelReady(
                player, result, serverLevel, position, barrel.getRecipeId());
    }
}
