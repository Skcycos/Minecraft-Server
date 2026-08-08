package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeSuppression;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeTransaction;
import com.tanrunn.tcth.impl.compat.farmersdelight.FarmersDelightDishAdapter;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu;

/**
 * Publishes TCTH dish events for Farmer's Delight cooking-pot
 * <em>Shift-click</em> take-outs, with full transactional semantics
 * (phase 6B.2.1 / 6B.2.2).
 *
 * <p>JAR evidence (FD 1.3.2, {@code CookingPotMenu.quickMoveStack}): the
 * cooked meal is moved by {@code moveItemStackTo} <em>before</em>
 * {@code slot.onTake} runs, and {@code onTake} is invoked for <em>any</em>
 * successful move (full or partial) with the remaining stack. The
 * {@code onTake} HEAD/RETURN mixin on {@link CookingPotResultSlotMixin}
 * therefore serves only the normal-click path and must be suppressed during
 * a Shift-click, otherwise a partial move double-publishes.
 *
 * <p>This mixin:
 * <ul>
 *   <li>HEAD — {@link ShiftTakeTransaction#begin} (previous signature →
 *       sign → signed event snapshot) and {@link ShiftTakeSuppression#enter}
 *       so the result-slot mixin skips its own sign/publish; any stale state
 *       from a previous (aborted) take is reset here;</li>
 *   <li>{@code Slot.onTake} AFTER — {@link ShiftTakeTransaction#commit} with
 *       the actually-delivered count; publishes exactly once; a partial move
 *       restores the previous signature on the items remaining in the slot;</li>
 *   <li>RETURN — abort (restore) if never published, clear state.</li>
 * </ul>
 *
 * <p>Exception note (6B.2.2/6B.2.3): this Mixin runtime (sponge-mixin 0.8.7 /
 * NeoForge 21.1.247) supports neither {@code @At("THROW")} nor
 * {@code @WrapOperation}, so an in-flight exception cannot be intercepted
 * here. The {@code RETURN} injection runs only on normal return and does
 * <em>not</em> cover exceptions; if {@code moveItemStackTo} /
 * {@code Slot.onTake} / the publish call throws, the token is <em>not</em>
 * guaranteed to be closed and the snapshots may persist until the next
 * {@code quickMoveStack} HEAD resets them. Known limitation, kept on purpose:
 * {@code ShiftTakeTransaction.abort} is idempotent (no-op once finished), so
 * a stale token is cleared by the next HEAD, and a stale recipe-id snapshot
 * is cleared by the result-slot mixin's unconditional {@code finally}.
 *
 * <p>Gated by {@code requiredMods=["farmersdelight"]}.
 */
@Mixin(CookingPotMenu.class)
public abstract class CookingPotMenuShiftTakeMixin {

    @Unique
    private ShiftTakeTransaction tcth$shiftTake = null;

    @Unique
    private ShiftTakeSuppression.ShiftTakeToken tcth$suppressToken = null;

    @Inject(method = "quickMoveStack", at = @At("HEAD"))
    private void tcth$captureShiftTake(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        this.tcth$shiftTake = null;
        if (this.tcth$suppressToken != null) {
            this.tcth$suppressToken.close();
            this.tcth$suppressToken = null;
        }
        if (index != CookingPotMenu.INDEX_OUTPUT) {
            return;
        }
        AbstractContainerMenu abstractMenu = (AbstractContainerMenu) (Object) this;
        if (index < 0 || index >= abstractMenu.slots.size()) {
            return;
        }
        Slot slot = abstractMenu.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return;
        }
        ItemStack real = slot.getItem();
        if (real.isEmpty()) {
            return;
        }
        CookingPotMenu menu = (CookingPotMenu) (Object) this;
        CookingPotBlockEntity pot = menu.blockEntity;
        ResourceLocation recipeId = RecipeTrackerSnapshot.capture(
                null, ((CookingPotBlockEntityAccessor) (Object) pot).tcth$getUsedRecipeTracker());
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        this.tcth$shiftTake = ShiftTakeTransaction.begin(serverPlayer, real, recipeId);
        if (this.tcth$shiftTake != null) {
            this.tcth$suppressToken = ShiftTakeSuppression.enter(menu);
        }
    }

    @Inject(method = "quickMoveStack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
                    shift = At.Shift.AFTER))
    private void tcth$publishShiftTake(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        ShiftTakeTransaction tx = this.tcth$shiftTake;
        if (tx == null) {
            return;
        }
        try {
            int remaining = tx.remainingCount();
            ItemStack eventStack = tx.commit(remaining);
            if (eventStack == null || eventStack.isEmpty()) {
                return;
            }
            CookingPotMenu menu = (CookingPotMenu) (Object) this;
            CookingPotBlockEntity pot = menu.blockEntity;
            if (pot.getLevel() instanceof ServerLevel serverLevel) {
                ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
                FarmersDelightDishAdapter.onDishTaken(
                        serverPlayer, eventStack, tx.recipeId(), pot, serverLevel);
            }
        } catch (RuntimeException | LinkageError e) {
            tx.abort();
            TCTHIntegration.LOGGER.error("[TCTH] Cooking pot shift-take publish failed: {}", e.toString());
        }
    }

    @Inject(method = "quickMoveStack", at = @At("RETURN"))
    private void tcth$finishShiftTake(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        cleanup();
    }


    @Unique
    private void cleanup() {
        ShiftTakeTransaction tx = this.tcth$shiftTake;
        if (tx == null) {
            // Nothing to do, but still release any stray token.
            if (this.tcth$suppressToken != null) {
                this.tcth$suppressToken.close();
                this.tcth$suppressToken = null;
            }
            return;
        }
        try {
            if (!tx.isFinished()) {
                // Move failed or never reached onTake:
                // restore the slot's previous signature.
                tx.abort();
            }
        } finally {
            tx.end();
            this.tcth$shiftTake = null;
            if (this.tcth$suppressToken != null) {
                this.tcth$suppressToken.close();
                this.tcth$suppressToken = null;
            }
        }
    }
}
