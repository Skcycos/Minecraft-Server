package com.tanrunn.tcth.mixin.dungeonsdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot;
import com.tanrunn.tcth.impl.compat.dungeonsdelight.DungeonsDelightDishAdapter;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotBlockEntity;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotResultSlot;

/**
 * Fires TCTH dish events when a player takes a meal from a Dungeon's Delight
 * monster pot.
 *
 * <p>JAR evidence (1.5.0): Shift-click runs
 * {@code onQuickCraft} → {@code checkTakeAchievements} →
 * {@code awardUsedRecipes} (clears {@code usedRecipeTracker}) before
 * {@code onTake}. Recipe id is therefore captured at
 * {@code checkTakeAchievements} HEAD via {@link RecipeTrackerSnapshot}.
 * {@code onTake} HEAD only signs; RETURN is the sole publish site.
 *
 * <p>Gated by {@code requiredMods=["dungeonsdelight"]}.
 */
@Mixin(MonsterPotResultSlot.class)
public abstract class MonsterPotResultSlotMixin {

    @Unique
    private ResourceLocation tcth$recipeIdSnapshot = null;

    @Unique
    private CookingSignature tcth$previousSignature = null;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void tcth$captureRecipeIdOnAchievements(ItemStack stack, CallbackInfo ci) {
        MonsterPotResultSlot slot = (MonsterPotResultSlot) (Object) this;
        MonsterPotBlockEntity pot = slot.tileEntity;
        this.tcth$recipeIdSnapshot = RecipeTrackerSnapshot.capture(
                this.tcth$recipeIdSnapshot,
                ((MonsterPotBlockEntityAccessor) (Object) pot).tcth$getUsedRecipeTracker());
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void tcth$signOnTake(Player player, ItemStack stack, CallbackInfo ci) {
        this.tcth$previousSignature = null;
        if (CookingSignatureComponents.isRegistered() && stack != null && !stack.isEmpty()) {
            this.tcth$previousSignature = stack.get(CookingSignatureComponents.type());
        }
        if (player instanceof ServerPlayer serverPlayer) {
            DishSignatureService.sign(serverPlayer, stack);
        }
    }

    @Inject(method = "onTake", at = @At("RETURN"))
    private void tcth$onDishTaken(Player player, ItemStack stack, CallbackInfo ci) {
        try {
            MonsterPotResultSlot slot = (MonsterPotResultSlot) (Object) this;
            MonsterPotBlockEntity pot = slot.tileEntity;
            Level level = pot.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            DungeonsDelightDishAdapter.onDishTaken(
                    serverPlayer, stack, this.tcth$recipeIdSnapshot, serverLevel, pot.getBlockPos());
        } catch (RuntimeException | LinkageError e) {
            restorePreviousSignature(stack);
            TCTHIntegration.LOGGER.error("[TCTH] Monster pot dish publish failed: {}", e.toString());
        } finally {
            this.tcth$recipeIdSnapshot = null;
            this.tcth$previousSignature = null;
        }
    }

    @Unique
    private void restorePreviousSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !CookingSignatureComponents.isRegistered()) {
            return;
        }
        try {
            if (this.tcth$previousSignature == null) {
                stack.remove(CookingSignatureComponents.type());
            } else {
                stack.set(CookingSignatureComponents.type(), this.tcth$previousSignature);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best-effort restore
        }
    }
}
