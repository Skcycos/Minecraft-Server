package com.tanrunn.tcth.mixin.dungeonsdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeSuppression;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeTransaction;
import com.tanrunn.tcth.impl.compat.dungeonsdelight.DungeonsDelightDishAdapter;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotBlockEntity;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotMenu;

/**
 * Publishes TCTH dish events for Dungeon's Delight monster-pot
 * <em>Shift-click</em> take-outs, with the same transactional semantics as
 * {@code CookingPotMenuShiftTakeMixin} (phase 6B.2.1 / 6B.2.2):
 *
 * <ul>
 *   <li>HEAD — begin + {@link ShiftTakeSuppression#enter} (suppresses the
 *       result-slot mixin so a partial move cannot double-publish); any stale
 *       state from a previous take is reset here;</li>
 *   <li>{@code Slot.onTake} AFTER — commit with actually-delivered count,
 *       publish once, restore previous signature on remaining items;</li>
 *   <li>RETURN — abort if never published, clear state.</li>
 * </ul>
 *
 * <p>Exception note (6B.2.2/6B.2.3): this Mixin runtime does not support
 * {@code @At("THROW")} / {@code @WrapOperation}; the {@code RETURN} injection
 * covers only normal returns and the token is not guaranteed to close on an
 * in-flight exception (reset by the next HEAD). See
 * {@code CookingPotMenuShiftTakeMixin} for the full known-limitation
 * statement.
 *
 * Gated by {@code requiredMods=["dungeonsdelight"]}.
 */
@Mixin(MonsterPotMenu.class)
public abstract class MonsterPotMenuShiftTakeMixin {

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
        // DD monster pot result slot is index 8 (JAR 1.5.0 layout).
        if (index != 8) {
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
        MonsterPotMenu menu = (MonsterPotMenu) (Object) this;
        MonsterPotBlockEntity pot = menu.blockEntity;
        ResourceLocation recipeId = RecipeTrackerSnapshot.capture(
                null, ((MonsterPotBlockEntityAccessor) (Object) pot).tcth$getUsedRecipeTracker());
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
            MonsterPotMenu menu = (MonsterPotMenu) (Object) this;
            MonsterPotBlockEntity pot = menu.blockEntity;
            if (pot.getLevel() instanceof ServerLevel serverLevel) {
                ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
                DungeonsDelightDishAdapter.onDishTaken(
                        serverPlayer, eventStack, tx.recipeId(), serverLevel, pot.getBlockPos());
            }
        } catch (RuntimeException | LinkageError e) {
            tx.abort();
            TCTHIntegration.LOGGER.error("[TCTH] Monster pot shift-take publish failed: {}", e.toString());
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
            if (this.tcth$suppressToken != null) {
                this.tcth$suppressToken.close();
                this.tcth$suppressToken = null;
            }
            return;
        }
        try {
            if (!tx.isFinished()) {
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
