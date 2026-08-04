package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.impl.compat.farmersdelight.FarmersDelightDishAdapter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotResultSlot;

/**
 * Fires a TCTH dish event when a player takes a meal out of a Farmer's Delight
 * cooking pot.
 *
 * <p>{@code CookingPotResultSlot.onTake(Player, ItemStack)} is invoked only
 * after a real successful take-out (no failure path), so posting at RETURN is
 * safe and cannot mis-fire on failed interactions.
 *
 * <p>Applied only when {@code farmersdelight} is installed
 * ({@code requiredMods=["farmersdelight"]} on this config).
 */
@Mixin(CookingPotResultSlot.class)
public abstract class CookingPotResultSlotMixin {

    @Inject(method = "onTake", at = @At("RETURN"))
    private void tcth$onDishTaken(Player player, ItemStack stack, CallbackInfo ci) {
        CookingPotResultSlot slot = (CookingPotResultSlot) (Object) this;
        CookingPotBlockEntity pot = slot.cookingPot;
        Level level = pot.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        FarmersDelightDishAdapter.onDishTaken(serverPlayer, stack, pot.getRecipeUsed(), pot, serverLevel);
    }
}
