package com.tanrunn.tcth.impl.compat.kaleidoscopetavern;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.brewing.BeverageTierManager;
import com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * TCTH-only bridge for Kaleidoscope Tavern beverage completion paths.
 *
 * <p>The mixins keep all Tavern class references on the conditional side of
 * the integration. This adapter owns the shared runtime-tier check and sends
 * the event through the same dispatcher used by the Brewin' and Chewin' keg.
 */
public final class KaleidoscopeBrewingAdapter {

    private KaleidoscopeBrewingAdapter() {
    }

    /** Publishes a beverage made by the hand-held Tavern Shaker. */
    public static boolean onShakerPrepared(@Nullable ServerPlayer player, ItemStack result,
                                           ServerLevel level, @Nullable BlockPos position) {
        return publish(player, null, result, BeverageDevice.SHAKER, level, position);
    }

    /** Publishes a beverage ready for extraction from a Tavern Barrel. */
    public static boolean onBarrelReady(@Nullable ServerPlayer player, ItemStack result,
                                        ServerLevel level, @Nullable BlockPos position,
                                        @Nullable ResourceLocation recipeId) {
        return publish(player, recipeId, result, BeverageDevice.BARREL, level, position);
    }

    private static boolean publish(@Nullable ServerPlayer player, @Nullable ResourceLocation recipeId,
                                   ItemStack result, BeverageDevice device, ServerLevel level,
                                   @Nullable BlockPos position) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        BeverageTier tier = BeverageTierManager.tierFor(result);
        if (tier == BeverageTier.UNKNOWN) {
            return false;
        }
        return BrewerIntegrationDispatcher.publish(player, recipeId, result, device, tier, level, position)
                == BrewerIntegrationDispatcher.Result.POSTED;
    }
}
