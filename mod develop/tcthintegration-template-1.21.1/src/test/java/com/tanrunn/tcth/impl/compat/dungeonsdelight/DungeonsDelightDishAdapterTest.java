package com.tanrunn.tcth.impl.compat.dungeonsdelight;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.cooking.CookingDevice;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Unit tests for DD recipe-id resolution and public adapter surface.
 * Does not claim player-live take-out validation.
 */
class DungeonsDelightDishAdapterTest {

    @Test
    void recipeIdOnlyWhenTrackerHasExactlyOneEntry() {
        Object2IntOpenHashMap<ResourceLocation> empty = new Object2IntOpenHashMap<>();
        assertNull(DungeonsDelightRecipeIds.resolveRecipeId(empty));
        assertNull(DungeonsDelightRecipeIds.resolveRecipeId(null));

        Object2IntOpenHashMap<ResourceLocation> one = new Object2IntOpenHashMap<>();
        ResourceLocation id = ResourceLocation.parse("dungeonsdelight:monster_cooking/spider_donut");
        one.put(id, 1);
        assertEquals(id, DungeonsDelightRecipeIds.resolveRecipeId(one));

        Object2IntOpenHashMap<ResourceLocation> multi = new Object2IntOpenHashMap<>();
        multi.put(ResourceLocation.parse("dungeonsdelight:a"), 1);
        multi.put(ResourceLocation.parse("dungeonsdelight:b"), 2);
        assertNull(DungeonsDelightRecipeIds.resolveRecipeId(multi));
    }

    @Test
    void cookingDeviceEnumPresent() {
        assertEquals("DUNGEONS_DELIGHT_MONSTER_POT", CookingDevice.DUNGEONS_DELIGHT_MONSTER_POT.name());
        assertEquals("BAKERIES_OVEN", CookingDevice.BAKERIES_OVEN.name());
        assertEquals("BAKERIES_BLENDER", CookingDevice.BAKERIES_BLENDER.name());
    }

    @Test
    void adapterRejectsEmptyStackWithoutThrowing() {
        // Empty stacks are never dishes; no server level required for this path.
        assertDoesNotThrow(() -> DungeonsDelightDishAdapter.onDishTaken(
                null, ItemStack.EMPTY, null, null, null));
    }
}
