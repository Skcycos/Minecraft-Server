package com.tanrunn.tcth.impl.compat.dungeonsdelight;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Recipe-id helpers for Dungeon's Delight monster pot.
 *
 * <p>Same semantics as Farmer's Delight: {@code usedRecipeTracker} may hold
 * multiple historical entries, so the id is reported only when the tracker
 * contains exactly one key; otherwise {@code null}.
 */
public final class DungeonsDelightRecipeIds {

    private DungeonsDelightRecipeIds() {
    }

    /**
     * @see com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot#resolve
     */
    @Nullable
    public static ResourceLocation resolveRecipeId(@Nullable Object2IntOpenHashMap<ResourceLocation> tracker) {
        return com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot.resolve(tracker);
    }
}
