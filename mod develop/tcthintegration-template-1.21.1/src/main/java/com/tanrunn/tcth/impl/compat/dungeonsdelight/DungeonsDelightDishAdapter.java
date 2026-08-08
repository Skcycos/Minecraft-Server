package com.tanrunn.tcth.impl.compat.dungeonsdelight;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Publishes a TCTH dish event for one real monster-pot take-out.
 *
 * <p>Public API surface: only TCTH types. No Dungeon's Delight classes appear
 * in method signatures so the adapter can be referenced from tests without
 * loading optional mod types.
 */
public final class DungeonsDelightDishAdapter {

    private DungeonsDelightDishAdapter() {
    }

    /**
     * @param player   taking player, or {@code null} for non-player actors
     * @param result   actual onTake stack
     * @param recipeId single-tracker id or {@code null}
     * @param level    server level
     * @param pos      pot block position (may be null)
     */
    public static void onDishTaken(@Nullable ServerPlayer player, ItemStack result,
                                   @Nullable ResourceLocation recipeId, ServerLevel level,
                                   @Nullable BlockPos pos) {
        if (!DishClassifier.isDish(result)) {
            return;
        }
        DishCookedEventDispatcher.publish(
                player,
                recipeId,
                result,
                CookingDevice.DUNGEONS_DELIGHT_MONSTER_POT,
                DishQuality.UNKNOWN,
                player == null,
                level,
                pos);
    }
}
