package com.tanrunn.tcth.impl.compat.brewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Phase 7D Field Guide brewer catalogue validation: the two categories
 * (COMMON 18 / T2 46, total 64) are mutually exclusive, T3/ingredients/
 * containers never enter, every entry id is a strict ResourceLocation, and
 * every explicit entry pins the never-satisfied brewer gate prerequisite so
 * Field Guide's implicit OBTAIN trigger cannot unlock. The preset output and
 * the server global pack copy must be identical.
 */
class BrewerFieldGuideDataTest {

    private static final Gson GSON = new Gson();
    private static final String GATE = "tcth:brewer_cookbook_gate";

    private static final String[] CATEGORIES = {"brew_common.json", "brew_t2.json"};
    private static final int[] EXPECTED = {18, 46};

    private static final Pattern NS_RE = Pattern.compile("^[a-z0-9_.-]+$");
    private static final Pattern PATH_RE = Pattern.compile("^[a-z0-9/._-]+$");

    private static JsonObject read(Path p) throws Exception {
        return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
    }

    private static Path presetCat(String name) {
        return Path.of("docs/presets/tcth-brewer/data/tcth/fieldguide/categories/" + name);
    }

    private static Path serverCat(String name) {
        return Path.of("../../Server/global_packs/required_data/tcth-brewer/data/tcth/fieldguide/categories/" + name);
    }

    @Test
    void twoCategoriesWithExpectedCounts() throws Exception {
        for (int i = 0; i < CATEGORIES.length; i++) {
            JsonObject cat = read(presetCat(CATEGORIES[i]));
            JsonArray contents = cat.getAsJsonArray("contents");
            assertEquals(EXPECTED[i], contents.size(), CATEGORIES[i] + " entry count");
            for (var el : contents) {
                JsonObject e = el.getAsJsonObject();
                assertEquals("entry", e.get("type").getAsString());
                assertTrue(e.has("id"), "entry must have an id");
            }
        }
    }

    @Test
    void categoriesAreMutuallyExclusiveAndTotal64() throws Exception {
        Set<String> ids = new HashSet<>();
        int total = 0;
        for (String name : CATEGORIES) {
            JsonArray contents = read(presetCat(name)).getAsJsonArray("contents");
            total += contents.size();
            for (var el : contents) {
                assertTrue(ids.add(el.getAsJsonObject().get("id").getAsString()),
                        "duplicate entry id across categories");
            }
        }
        assertEquals(64, total, "total entries must be 64 (18 COMMON + 46 T2)");
        assertEquals(64, ids.size());
    }

    @Test
    void everyEntryPinsTheBrewerGateAndIsValidResourceLocation() throws Exception {
        for (String name : CATEGORIES) {
            JsonArray contents = read(presetCat(name)).getAsJsonArray("contents");
            for (var el : contents) {
                JsonObject e = el.getAsJsonObject();
                String id = e.get("id").getAsString();
                assertTrue(id.startsWith("item:"), id);
                String itemPart = id.substring("item:".length());
                int slash = itemPart.indexOf('/');
                assertTrue(slash > 0 && slash < itemPart.length() - 1, id);
                String ns = itemPart.substring(0, slash);
                String path = itemPart.substring(slash + 1);
                assertTrue(NS_RE.matcher(ns).matches(), "invalid namespace: " + id);
                assertTrue(PATH_RE.matcher(path).matches(), "invalid path: " + id);
                assertFalse(ns.contains("..") || path.contains(".."), "path traversal: " + id);
                JsonArray prereqs = e.getAsJsonObject("unlock").getAsJsonArray("prerequisites");
                assertEquals(1, prereqs.size(), id);
                assertEquals(GATE, prereqs.get(0).getAsString(), id);
            }
        }
    }

    @Test
    void noT3CandidateIngredientOrContainerEnters() throws Exception {
        Set<String> ids = new HashSet<>();
        for (String name : CATEGORIES) {
            JsonArray contents = read(presetCat(name)).getAsJsonArray("contents");
            for (var el : contents) {
                ids.add(el.getAsJsonObject().get("id").getAsString());
            }
        }
        // T3 candidates (red_rum / saccharine_rum) must never appear.
        assertFalse(ids.contains("item:brewinandchewin/red_rum"));
        assertFalse(ids.contains("item:brewinandchewin/saccharine_rum"));
        // container / ingredient / excluded items must never appear.
        assertFalse(ids.contains("item:brewinandchewin/tankard"));
        assertFalse(ids.contains("item:minecraft/glass_bottle"));
        assertFalse(ids.contains("item:minecraft/water_bucket"));
        // Empty containers and raw ingredients are not beverages.
        assertFalse(ids.contains("item:minecraft/honeycomb"));
        assertFalse(ids.contains("item:brewinandchewin/tankard"));
    }

    @Test
    void presetMatchesServerGlobalPackCopy() throws Exception {
        for (String name : CATEGORIES) {
            byte[] preset = Files.readAllBytes(presetCat(name));
            byte[] server = Files.readAllBytes(serverCat(name));
            assertTrue(java.util.Arrays.equals(preset, server),
                    name + " preset must match the server global pack copy");
        }
    }
}
