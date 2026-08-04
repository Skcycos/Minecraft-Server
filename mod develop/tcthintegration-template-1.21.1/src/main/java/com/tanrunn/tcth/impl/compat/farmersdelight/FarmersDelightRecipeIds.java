package com.tanrunn.tcth.impl.compat.farmersdelight;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Recipe-id resolution helpers for the Farmer's Delight integration.
 *
 * <p>Farmer's Delight 1.3.2's {@code getRecipeUsed()} always returns
 * {@code null} (verified against the installed JAR), so the recipe id is read
 * from the cooking pot's internal {@code usedRecipeTracker} before it is
 * cleared. Because the tracker accumulates entries over time, the id is only
 * reported when exactly one entry is present — with multiple candidates there
 * is no reliable match and {@code null} is used instead.
 */
public final class FarmersDelightRecipeIds {

    private FarmersDelightRecipeIds() {
    }

    /**
     * Resolves a recipe id from the used-recipe tracker.
     *
     * @param tracker the cooking pot's used-recipe tracker
     * @return the single tracked recipe id, or {@code null} when the tracker
     *         is empty or holds multiple (ambiguous) entries
     */
    @Nullable
    public static ResourceLocation resolveRecipeId(@Nullable Object2IntOpenHashMap<ResourceLocation> tracker) {
        if (tracker == null || tracker.size() != 1) {
            return null;
        }
        return tracker.keySet().iterator().next();
    }
}
