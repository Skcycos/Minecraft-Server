package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 6D/6D.1: the three real served items of the Stuffed Hoglin feast must
 * be present in the authoritative classification, dish_tiers, chef tag and
 * Field Guide, as T2 (no new T3).
 */
class PortioningServingDataTest {

    private static final Gson GSON = new Gson();
    private static final List<String> SERVED = List.of(
            "plate_of_stuffed_hoglin",
            "plate_of_stuffed_hoglin_ham",
            "plate_of_stuffed_hoglin_snout");

    private static JsonObject read(Path path) throws Exception {
        return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
    }

    @Test
    void allThreeServedItemsHaveT2DishTier() throws Exception {
        for (String item : SERVED) {
            JsonObject tier = read(Path.of(
                    "docs/presets/tcth-chef/data/tcth/dish_tiers/items/mynethersdelight/" + item + ".json"));
            assertEquals("T2", tier.get("tier").getAsString(),
                    item + " must be T2 (human-assigned, no new T3)");
        }
    }

    @Test
    void allThreeServedItemsAreInChefT2Tag() throws Exception {
        JsonObject tag = read(Path.of("docs/presets/tcth-chef/data/tcth/tags/item/chef_t2.json"));
        String values = tag.getAsJsonArray("values").toString();
        for (String item : SERVED) {
            assertTrue(values.contains("mynethersdelight:" + item),
                    item + " must be in chef_t2 tag");
        }
    }

    @Test
    void allThreeServedItemsAreInFieldGuideChefT2() throws Exception {
        JsonObject fg = read(Path.of(
                "docs/presets/tcth-chef/data/tcth/fieldguide/categories/chef_t2.json"));
        var contents = fg.getAsJsonArray("contents");
        java.util.Set<String> ids = new java.util.HashSet<>();
        contents.forEach(el -> ids.add(el.getAsJsonObject().get("id").getAsString()));
        for (String item : SERVED) {
            assertTrue(ids.contains("item:mynethersdelight/" + item),
                    item + " must have a Field Guide entry");
        }
    }

    @Test
    void servingContainerTagIsClassificationDataOnlyNotJavaGated() throws Exception {
        // The tag exists for classification/documentation; the publish logic is
        // driven by the Mixin injecting Inventory.add, not by this tag. Assert
        // the tag lists the whole-dish containers and Java never reads it.
        JsonObject tag = read(Path.of(
                "docs/presets/tcth-chef/data/tcth/tags/item/serving_dish_containers.json"));
        String values = tag.getAsJsonArray("values").toString();
        assertTrue(values.contains("mynethersdelight:roast_stuffed_hoglin"));
        assertTrue(values.contains("minecraft:cake"));

        // Java must not reference the tag name (pure data, no runtime gating).
        var java = Files.walk(Path.of("src/main/java"));
        assertTrue(java.filter(p -> p.toString().endsWith(".java"))
                .noneMatch(p -> {
                    try {
                        return Files.readString(p, StandardCharsets.UTF_8)
                                .contains("serving_dish_containers");
                    } catch (Exception e) {
                        return false;
                    }
                }),
                "serving_dish_containers must not be referenced from Java (classification data only)");
    }
}
