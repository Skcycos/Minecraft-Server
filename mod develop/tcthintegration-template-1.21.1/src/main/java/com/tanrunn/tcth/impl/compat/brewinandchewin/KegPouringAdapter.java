package com.tanrunn.tcth.impl.compat.brewinandchewin;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.brewing.BeverageTierManager;
import com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Publishes one {@code BeveragePreparedEvent} for a real Keg pouring
 * (phase 7B).
 *
 * <p>Public API surface: only TCTH types, so the adapter is unit-testable
 * without loading Brewin' and Chewin' classes. The delivered stack is the
 * REAL stack extracted by the Keg (never fabricated); recipeId is always
 * {@code null} because {@code KegPouringRecipe} exposes no id (7A.1).
 */
public final class KegPouringAdapter {

    private KegPouringAdapter() {
    }

    /**
     * @param player      the pouring player; {@code null} for automated actors
     * @param delivered   the real delivered beverage stack (already extracted);
     *                    its count reflects the actual amount to publish
     * @param level       server level
     * @param pos         keg position (may be {@code null} when the device
     *                    position is not reliably available, 7B.1.1)
     * @return {@code true} when an event was published
     */
    public static boolean onPouringDelivered(@Nullable ServerPlayer player, ItemStack delivered,
                                             ServerLevel level, @Nullable BlockPos pos) {
        if (delivered == null || delivered.isEmpty()) {
            return false;
        }
        // Non-runtime beverages (T3/INGREDIENT/containers/EXCLUDED) resolve to
        // UNKNOWN via the tier manager and must not publish.
        BeverageTier tier = BeverageTierManager.tierFor(delivered);
        if (tier == BeverageTier.UNKNOWN) {
            return false;
        }
        return BrewerIntegrationDispatcher.publish(player, null, delivered, BeverageDevice.KEG, tier, level, pos)
                == BrewerIntegrationDispatcher.Result.POSTED;
    }
}
