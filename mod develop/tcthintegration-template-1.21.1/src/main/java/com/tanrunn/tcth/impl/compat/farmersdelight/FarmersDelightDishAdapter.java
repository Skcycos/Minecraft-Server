package com.tanrunn.tcth.impl.compat.farmersdelight;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Adapter that turns a Farmer's Delight cooking-pot take-out into a TCTH
 * {@link com.tanrunn.tcth.api.cooking.DishCookedEvent}.
 *
 * <p>This class lives in the farmersdelight compat module and is only ever
 * loaded when Farmer's Delight is installed (its mixin config is gated by
 * {@code requiredMods=["farmersdelight"]}).
 *
 * <p>Automated flag: Farmer's Delight's {@code onTake} is a player slot
 * callback, so the actor is normally a {@link ServerPlayer}. If the caller is
 * not a server player, the event is published with {@code player=null} and
 * {@code automated=true}.
 */
public final class FarmersDelightDishAdapter {

    private FarmersDelightDishAdapter() {
    }

    /**
     * Publishes a dish event for one real cooking-pot take-out.
     *
     * @param player the taking player, or {@code null} for automated actors
     * @param result the taken dish stack
     * @param recipe the matched recipe (may be {@code null})
     * @param pot    the cooking pot block entity
     * @param level  the server level
     */
    public static void onDishTaken(@Nullable ServerPlayer player, ItemStack result,
                                   @Nullable RecipeHolder<?> recipe, CookingPotBlockEntity pot, ServerLevel level) {
        ResourceLocation recipeId = recipe != null ? recipe.id() : null;
        DishCookedEventDispatcher.publish(player, recipeId, result, CookingDevice.FARMERS_DELIGHT_COOKING_POT,
                DishQuality.UNKNOWN, player == null, level, pot.getBlockPos());
    }
}
