package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.impl.compat.farmersdelight.FarmersDelightDishAdapter;
import com.tanrunn.tcth.impl.compat.farmersdelight.FarmersDelightRecipeIds;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

import net.minecraft.resources.ResourceLocation;
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
 * <p>{@code onTake(Player, ItemStack)} is invoked only after a real successful
 * take-out. The recipe id is captured at HEAD from the pot's internal
 * {@code usedRecipeTracker} (before {@code awardUsedRecipes} clears it) and is
 * only reported when the tracker holds exactly one entry; ambiguous/missing
 * ids become {@code null}. The RETURN handler publishes the actual onTake
 * stack together with the HEAD-captured id and always clears the snapshot
 * (including on failure paths), so no state leaks into later calls.
 *
 * <p>Applied only when {@code farmersdelight} is installed
 * ({@code requiredMods=["farmersdelight"]} on this config).
 */
@Mixin(CookingPotResultSlot.class)
public abstract class CookingPotResultSlotMixin {

    @Unique
    private ResourceLocation tcth$recipeIdSnapshot = null;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void tcth$captureRecipeId(Player player, ItemStack stack, CallbackInfo ci) {
        CookingPotResultSlot slot = (CookingPotResultSlot) (Object) this;
        CookingPotBlockEntity pot = slot.cookingPot;
        this.tcth$recipeIdSnapshot = FarmersDelightRecipeIds.resolveRecipeId(
                ((CookingPotBlockEntityAccessor) (Object) pot).tcth$getUsedRecipeTracker());
        // Sign the real onTake stack BEFORE the super call hands it to the
        // player, so the delivered dish carries the signature.
        if (player instanceof ServerPlayer serverPlayer) {
            DishSignatureService.sign(serverPlayer, stack);
        }
    }

    @Inject(method = "onTake", at = @At("RETURN"))
    private void tcth$onDishTaken(Player player, ItemStack stack, CallbackInfo ci) {
        try {
            CookingPotResultSlot slot = (CookingPotResultSlot) (Object) this;
            CookingPotBlockEntity pot = slot.cookingPot;
            Level level = pot.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            FarmersDelightDishAdapter.onDishTaken(serverPlayer, stack, this.tcth$recipeIdSnapshot, pot, serverLevel);
        } finally {
            this.tcth$recipeIdSnapshot = null;
        }
    }
}
