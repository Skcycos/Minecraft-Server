package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeSuppression;
import com.tanrunn.tcth.impl.compat.farmersdelight.FarmersDelightDishAdapter;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
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
 * <p>JAR lifecycle (FD 1.3.2 / same pattern as DD 1.5.0):
 * <ul>
 *   <li>Shift-click: {@code onQuickCraft} → {@code checkTakeAchievements} →
 *       {@code awardUsedRecipes} (clears tracker) → later {@code onTake}</li>
 *   <li>Normal click: {@code onTake} → {@code checkTakeAchievements} → clear</li>
 * </ul>
 * Recipe id is captured at {@code checkTakeAchievements} HEAD via
 * {@link RecipeTrackerSnapshot}. {@code onTake} HEAD only signs the real
 * delivery stack; {@code onTake} RETURN is the sole publish site. Snapshot is
 * cleared in {@code finally} after publish.
 *
 * <p>Applied only when {@code farmersdelight} is installed
 * ({@code requiredMods=["farmersdelight"]}).
 */
@Mixin(CookingPotResultSlot.class)
public abstract class CookingPotResultSlotMixin {

    @Unique
    private ResourceLocation tcth$recipeIdSnapshot = null;

    @Unique
    private CookingSignature tcth$previousSignature = null;

    /**
     * Capture recipe id before awardUsedRecipes clears the tracker (covers
     * Shift-click where this runs from onQuickCraft before onTake).
     */
    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void tcth$captureRecipeIdOnAchievements(ItemStack stack, CallbackInfo ci) {
        CookingPotResultSlot slot = (CookingPotResultSlot) (Object) this;
        CookingPotBlockEntity pot = slot.cookingPot;
        this.tcth$recipeIdSnapshot = RecipeTrackerSnapshot.capture(
                this.tcth$recipeIdSnapshot,
                ((CookingPotBlockEntityAccessor) (Object) pot).tcth$getUsedRecipeTracker());
    }

    /**
     * Sign the real delivery stack only. Do not re-resolve tracker here —
     * after Shift-click the tracker is often already empty. Skipped entirely
     * while a Shift-click take is in progress (the menu mixin already signed
     * the real stack before the move; signing again would overwrite the
     * restored previous signature on a partial move).
     */
    @Inject(method = "onTake", at = @At("HEAD"))
    private void tcth$signOnTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (isShiftTakeSuppressed(player)) {
            return;
        }
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
            // While a Shift-click take is in progress the menu mixin is the
            // sole publisher; skip only the publish, never the cleanup below.
            if (isShiftTakeSuppressed(player)) {
                return;
            }
            CookingPotResultSlot slot = (CookingPotResultSlot) (Object) this;
            CookingPotBlockEntity pot = slot.cookingPot;
            Level level = pot.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            FarmersDelightDishAdapter.onDishTaken(serverPlayer, stack, this.tcth$recipeIdSnapshot, pot, serverLevel);
        } catch (RuntimeException | LinkageError e) {
            restorePreviousSignature(stack);
            TCTHIntegration.LOGGER.error("[TCTH] Cooking pot dish publish failed: {}", e.toString());
        } finally {
            // Must clear after EVERY take (including suppressed Shift-click
            // and non-server paths) so the next take never inherits a stale
            // recipe id or signature snapshot.
            this.tcth$recipeIdSnapshot = null;
            this.tcth$previousSignature = null;
        }
    }

    @Unique
    private static boolean isShiftTakeSuppressed(Player player) {
        if (player == null || player.containerMenu == null) {
            return false;
        }
        return ShiftTakeSuppression.isSuppressed(player.containerMenu);
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
            // best-effort
        }
    }
}
