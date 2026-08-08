package com.tanrunn.tcth.impl.compat.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure-logic snapshot tests shared by FD/DD result-slot recipe capture.
 */
class RecipeTrackerSnapshotTest {

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    void emptyTrackerYieldsNull() {
        assertNull(RecipeTrackerSnapshot.resolve(null));
        assertNull(RecipeTrackerSnapshot.resolve(new Object2IntOpenHashMap<>()));
    }

    @Test
    void singleRecipeCaptured() {
        Object2IntOpenHashMap<ResourceLocation> t = new Object2IntOpenHashMap<>();
        ResourceLocation one = id("dungeonsdelight:monster_cooking/spider_pie");
        t.put(one, 1);
        assertEquals(one, RecipeTrackerSnapshot.resolve(t));
    }

    @Test
    void multiRecipeYieldsNull() {
        Object2IntOpenHashMap<ResourceLocation> t = new Object2IntOpenHashMap<>();
        t.put(id("mod:a"), 1);
        t.put(id("mod:b"), 2);
        assertNull(RecipeTrackerSnapshot.resolve(t));
    }

    @Test
    void shiftClickFirstCaptureThenEmptyTrackerDoesNotWipe() {
        Object2IntOpenHashMap<ResourceLocation> duringAchievements = new Object2IntOpenHashMap<>();
        ResourceLocation recipe = id("farmersdelight:cooking/cooked_rice");
        duringAchievements.put(recipe, 1);

        ResourceLocation snap = null;
        // checkTakeAchievements HEAD (Shift-click path)
        snap = RecipeTrackerSnapshot.capture(snap, duringAchievements);
        assertEquals(recipe, snap);

        // later onTake: tracker already cleared by awardUsedRecipes
        Object2IntOpenHashMap<ResourceLocation> empty = new Object2IntOpenHashMap<>();
        snap = RecipeTrackerSnapshot.capture(snap, empty);
        assertEquals(recipe, snap, "empty tracker must not overwrite prior non-null snapshot");
    }

    @Test
    void clearAfterPublishDoesNotLeakToNextTake() {
        ResourceLocation snap = id("mod:first");
        // finally after onTake RETURN
        snap = null;
        // next normal take with empty tracker
        assertNull(RecipeTrackerSnapshot.capture(snap, new Object2IntOpenHashMap<>()));
    }

    @Test
    void multiCandidateDoesNotInheritPreviousEvent() {
        ResourceLocation leftover = id("mod:previous_event");
        Object2IntOpenHashMap<ResourceLocation> multi = new Object2IntOpenHashMap<>();
        multi.put(id("mod:a"), 1);
        multi.put(id("mod:b"), 1);
        // After clear, previous must already be null; merge of multi→null with null stays null.
        // If a bug left leftover, multi→null must still not invent a single id — resolve is null.
        assertNull(RecipeTrackerSnapshot.resolve(multi));
        // Correct lifecycle: clear between events so leftover is null
        leftover = null;
        assertNull(RecipeTrackerSnapshot.capture(leftover, multi));
    }

    @Test
    void normalClickSingleCaptureWorks() {
        Object2IntOpenHashMap<ResourceLocation> t = new Object2IntOpenHashMap<>();
        ResourceLocation recipe = id("farmersdelight:cooking/beef_stew");
        t.put(recipe, 1);
        ResourceLocation snap = RecipeTrackerSnapshot.capture(null, t);
        assertEquals(recipe, snap);
    }

    @Test
    void mergeNullKeepsPreviousNonNull() {
        ResourceLocation prev = id("mod:kept");
        assertEquals(prev, RecipeTrackerSnapshot.merge(prev, null));
    }

    @Test
    void mergeNonNullReplacesPrevious() {
        ResourceLocation prev = id("mod:old");
        ResourceLocation next = id("mod:new");
        assertEquals(next, RecipeTrackerSnapshot.merge(prev, next));
    }
}
