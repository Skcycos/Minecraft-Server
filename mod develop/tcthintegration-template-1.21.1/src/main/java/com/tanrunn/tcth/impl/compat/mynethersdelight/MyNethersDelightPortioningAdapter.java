package com.tanrunn.tcth.impl.compat.mynethersdelight;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Publishes a TCTH dish event for one real serving taken from a placed
 * MyNethersDelight feast (e.g. Stuffed Hoglin).
 *
 * <p>Phase 6D.1 semantics — the delivered stack is signed <em>in place</em>
 * via {@link #signServingStack} on the REAL stack that is passed to
 * {@code Inventory.add} / {@code Player.drop} (never a fabricated stack
 * created after the fact). {@link #onServingDelivered} publishes the event
 * once, on the inventory-add path only.
 *
 * <p>Public API surface: only TCTH types, so the adapter is unit-testable
 * without loading MyNethersDelight classes.
 */
public final class MyNethersDelightPortioningAdapter {

    private MyNethersDelightPortioningAdapter() {
    }

    /**
     * Signs the real serving stack in place (best-effort; returns the stack
     * unchanged when it is not a dish or signing is unavailable). This is the
     * stack actually handed to {@code Inventory.add} / {@code Player.drop}.
     *
     * @param stack  the real stack about to be added/dropped
     * @param player the taking player (used for the chef signature); may be
     *               {@code null}, in which case no signature is written
     */
    public static ItemStack signServingStack(ItemStack stack, @Nullable net.minecraft.world.entity.player.Player player) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        if (!DishClassifier.isDish(stack)) {
            return stack; // non-dishes pass through untouched
        }
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        if (serverPlayer != null) {
            DishSignatureService.sign(serverPlayer, stack);
        }
        return stack;
    }

    /**
     * Publishes one PORTIONING event for the serving being delivered.
     *
     * @param player      taking player; {@code null} for non-player actors
     * @param servingItem the served dish item delivered (one serving)
     * @param level       server level
     * @param pos         feast block position
     * @return {@code true} when an event was actually published
     */
    public static boolean onServingDelivered(@Nullable ServerPlayer player, Item servingItem,
                                             ServerLevel level, @Nullable BlockPos pos) {
        if (servingItem == null) {
            return false;
        }
        if (player == null) {
            return false; // automated / non-player: 0 events
        }
        ItemStack reportStack = new ItemStack(servingItem, 1);
        if (!DishClassifier.isDish(reportStack)) {
            return false; // non-dish servings never publish
        }
        // Sign the report stack so the published event's result carries the
        // current chef signature (the real delivered stack is already signed
        // separately by signServingStack at the add/drop call site).
        DishSignatureService.sign(player, reportStack);
        DishCookedEventDispatcher.publish(player, null, reportStack, CookingDevice.PORTIONING,
                DishQuality.UNKNOWN, false, level, pos);
        return true;
    }
}
