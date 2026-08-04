package com.tanrunn.tcth.impl.compat.kaleidoscope;

import org.jetbrains.annotations.Nullable;

import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Adapter that turns Kaleidoscope Cookery take-outs into TCTH
 * {@link com.tanrunn.tcth.api.cooking.DishCookedEvent}s.
 *
 * <p>This class lives in the kaleidoscope compat module and is only ever
 * loaded when Kaleidoscope Cookery is installed (its mixin config is gated by
 * {@code requiredMods=["kaleidoscope_cookery"]}).
 *
 * <p>The Kaleidoscope {@link Quality} enum is mapped onto TCTH's neutral
 * {@link DishQuality} here, inside the compat module — the public API never
 * references third-party types.
 */
public final class KaleidoscopeDishAdapter {

    private KaleidoscopeDishAdapter() {
    }

    /**
     * Maps a Kaleidoscope Cookery {@link Quality} onto {@link DishQuality}.
     */
    public static DishQuality mapQuality(ItemStack result) {
        if (result == null || result.isEmpty() || !QualityUtils.hasQuality(result)) {
            return DishQuality.UNKNOWN;
        }
        return switch (QualityUtils.getQuality(result)) {
            case SUPERB -> DishQuality.SUPERB;
            case EXCELLENT -> DishQuality.EXCELLENT;
            case STANDARD -> DishQuality.STANDARD;
            case POOR -> DishQuality.POOR;
            default -> DishQuality.UNKNOWN;
        };
    }

    /**
     * Publishes a dish event for one real take-out.
     *
     * @param player the taking player, or {@code null} for automated actors
     * @param result the taken dish stack
     * @param device the producing KC device
     * @param level  the server level
     * @param pos    the device block position
     */
    public static void onDishTaken(@Nullable ServerPlayer player, ItemStack result, CookingDevice device,
                                   ServerLevel level, BlockPos pos) {
        DishCookedEventDispatcher.publish(player, null, result, device, mapQuality(result), player == null, level, pos);
    }
}
