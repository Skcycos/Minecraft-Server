package com.tanrunn.tcth.impl.compat.cooking;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared recipe-id snapshot semantics for cooking-pot style result slots
 * (Farmer's Delight cooking pot, Dungeon's Delight monster pot).
 *
 * <p>Lifecycle assumption (JAR-verified for both mods):
 * <pre>
 * Shift-click: onQuickCraft → checkTakeAchievements → awardUsedRecipes (clears tracker)
 *              → later onTake (tracker often already empty)
 * Normal click: onTake → checkTakeAchievements → awardUsedRecipes
 * </pre>
 * Therefore recipe capture must run at {@code checkTakeAchievements} HEAD, not
 * only at {@code onTake} HEAD. Publish remains on {@code onTake} RETURN; clear
 * the snapshot in {@code finally} after publish.
 *
 * <p>Update rules:
 * <ul>
 *   <li>tracker null/empty → resolved null</li>
 *   <li>exactly one recipe id → capture that id</li>
 *   <li>multiple candidates → resolved null</li>
 *   <li>when applying a resolution: a null resolution must <em>not</em> overwrite
 *       a non-null snapshot already captured earlier in the same take (Shift-click
 *       path where tracker is cleared before onTake)</li>
 * </ul>
 */
public final class RecipeTrackerSnapshot {

    private RecipeTrackerSnapshot() {
    }

    /**
     * Resolves a recipe id from a used-recipe tracker.
     *
     * @return the single key when size == 1; otherwise {@code null}
     */
    @Nullable
    public static ResourceLocation resolve(@Nullable Object2IntOpenHashMap<ResourceLocation> tracker) {
        if (tracker == null || tracker.size() != 1) {
            return null;
        }
        return tracker.keySet().iterator().next();
    }

    /**
     * Merges a newly resolved id into an existing snapshot without wiping a
     * prior non-null capture with a later null (empty tracker).
     *
     * @param previous existing snapshot (may be null)
     * @param resolved result of {@link #resolve(Object2IntOpenHashMap)}
     * @return updated snapshot
     */
    @Nullable
    public static ResourceLocation merge(@Nullable ResourceLocation previous, @Nullable ResourceLocation resolved) {
        if (resolved != null) {
            return resolved;
        }
        // null resolution: keep prior non-null for the same take sequence
        return previous;
    }

    /**
     * Capture helper used by mixins at checkTakeAchievements HEAD.
     */
    @Nullable
    public static ResourceLocation capture(
            @Nullable ResourceLocation previous,
            @Nullable Object2IntOpenHashMap<ResourceLocation> tracker) {
        return merge(previous, resolve(tracker));
    }
}
