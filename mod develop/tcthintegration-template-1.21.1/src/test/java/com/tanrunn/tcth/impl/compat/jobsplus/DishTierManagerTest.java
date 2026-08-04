package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;

/**
 * Unit tests for {@link DishTierManager} dual-map (recipe/item) resolution.
 */
class DishTierManagerTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private void apply(String... entries) {
        java.util.Map<ResourceLocation, com.google.gson.JsonElement> map = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            map.put(ResourceLocation.parse(parts[0]), JsonParser.parseString(parts[1]));
        }
        new DishTierManager().apply(map, null, null);
    }

    private static String tierJson(String tier) {
        JsonObject json = new JsonObject();
        json.addProperty("tier", tier);
        return json.toString();
    }

    @Test
    void recipeMappingTakesPriorityOverItemMapping() {
        apply(
                "tcth:recipes/minecraft/cooked_beef=" + tierJson("T2"),
                "tcth:items/minecraft/cooked_beef=" + tierJson("T3"));

        // recipeId present -> recipe mapping wins
        var def = DishTierManager.resolve(ResourceLocation.parse("minecraft:cooked_beef"),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF));
        assertTrue(def.isPresent());
        assertEquals(DishTier.T2, def.get().tier());
    }

    @Test
    void nullRecipeIdFallsBackToItemMapping() {
        apply("tcth:items/minecraft/cooked_beef=" + tierJson("T3"));

        var def = DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF));
        assertTrue(def.isPresent(), "item mapping must cover recipeId=null dishes");
        assertEquals(DishTier.T3, def.get().tier());
    }

    @Test
    void neitherMappingResolvesToEmpty() {
        apply();
        assertTrue(DishTierManager.resolve(ResourceLocation.parse("minecraft:unknown"),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).isEmpty());
        assertTrue(DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT)).isEmpty());
        assertTrue(DishTierManager.resolve(null, null).isEmpty());
    }

    @Test
    void tierNamesAreCaseInsensitive() {
        apply("tcth:items/minecraft/cooked_beef=" + tierJson("t2"));
        assertEquals(DishTier.T2, DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).orElseThrow().tier());
    }

    @Test
    void invalidEntryIsIsolatedAndIgnored() {
        apply(
                "tcth:items/minecraft/bad_recipe={\"tier\": \"NOT_A_TIER\"}",
                "tcth:items/minecraft/apple=" + tierJson("COMMON"));

        assertTrue(DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK)).isEmpty());
        assertEquals(DishTier.COMMON, DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE)).orElseThrow().tier());
    }

    @Test
    void invalidEntryDoesNotPoisonOtherMappings() {
        apply(
                "tcth:recipes/minecraft/cooked_beef=" + tierJson("T2"),
                "tcth:bad_entry={\"tier\": \"COMMON\"}");

        assertEquals(DishTier.T2, DishTierManager.resolve(ResourceLocation.parse("minecraft:cooked_beef"),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).orElseThrow().tier());
    }

    @Test
    void reloadReplacesMapsAtomically() {
        apply("tcth:items/minecraft/cooked_beef=" + tierJson("T2"));
        assertFalse(DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).isEmpty());

        // Reload with an empty dataset: old entries must be gone (no stale data).
        apply();
        assertTrue(DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).isEmpty());
    }

    @Test
    void itemMappingsAreNamespaceIndependent() {
        apply("tcth:items/minecraft/cooked_beef=" + tierJson("T2"),
                "tcth:items/minecraft/cooked_porkchop=" + tierJson("T3"));

        assertEquals(DishTier.T2, DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF)).orElseThrow().tier());
        assertEquals(DishTier.T3, DishTierManager.resolve(null,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_PORKCHOP)).orElseThrow().tier());
    }
}
