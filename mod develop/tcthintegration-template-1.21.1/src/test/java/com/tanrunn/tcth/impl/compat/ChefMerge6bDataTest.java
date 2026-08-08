package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Phase 6B / 6B.1: aggregate counts plus per-item regression for all 167 NEW dishes.
 */
class ChefMerge6bDataTest {

    private static final Gson GSON = new Gson();
    private static final Path PRESET = Path.of("docs/presets/tcth-chef/data/tcth");
    /** Workspace-relative from project root (mod develop/tcthintegration-template-1.21.1). */
    private static final Path MERGE_PREVIEW = Path.of(
            "../../配方与经济管理/统一配方表/新增食物模组厨师合并预览.csv");
    private static final Path AUTHORITY = Path.of(
            "../../配方与经济管理/统一配方表/食物三档分类表.csv");
    private static final Path SERVING = Path.of(
            "../../配方与经济管理/统一配方表/新增食物模组整盘料理清单.csv");
    private static final Path DRINK = Path.of(
            "../../配方与经济管理/统一配方表/新增食物模组饮品清单.csv");
    private static final Path INGREDIENT = Path.of(
            "../../配方与经济管理/统一配方表/新增食物模组中间产物清单.csv");

    @Test
    void itemTierAre572WithExpectedDistribution() throws Exception {
        Map<String, String> tiers = loadItemTiers();
        assertEquals(575, tiers.size()); // 6D.1: +3 plate_of_stuffed_hoglin T2
        long common = tiers.values().stream().filter(t -> t.equals("COMMON")).count();
        long t2 = tiers.values().stream().filter(t -> t.equals("T2")).count();
        long t3 = tiers.values().stream().filter(t -> t.equals("T3")).count();
        assertEquals(353, common);
        assertEquals(198, t2); // 6D.1: +3 plate_of_stuffed_hoglin T2
        assertEquals(24, t3);
    }

    @Test
    void recipeTierStillOne() throws Exception {
        Path recipes = PRESET.resolve("dish_tiers/recipes");
        try (var walk = Files.walk(recipes)) {
            long n = walk.filter(p -> p.toString().endsWith(".json")).count();
            assertEquals(1, n);
        }
    }

    @Test
    void chefTagsMutuallyExclusiveAndFg333() throws Exception {
        Set<String> common = tagValues("chef_common.json");
        Set<String> t2 = tagValues("chef_t2.json");
        Set<String> t3 = tagValues("chef_t3.json");
        assertEquals(122, common.size());
        assertEquals(190, t2.size()); // 6D.1: +3 plate_of_stuffed_hoglin T2
        assertEquals(24, t3.size());
        assertTrue(common.stream().noneMatch(t2::contains));
        assertTrue(t2.stream().noneMatch(t3::contains));
        assertTrue(common.stream().noneMatch(t3::contains));
        assertFalse(common.contains("kaleidoscope_cookery:raw_dough"));

        Map<String, String> entryToCat = loadFgEntryCategories();
        assertEquals(336, entryToCat.size()); // 6D.1: +3 plate_of_stuffed_hoglin T2
    }

    @Test
    void all167MergePreviewItemsPresentExactlyOnceWithCorrectTiersAndFg() throws Exception {
        assertTrue(Files.isRegularFile(MERGE_PREVIEW), "missing merge preview: " + MERGE_PREVIEW.toAbsolutePath());
        assertTrue(Files.isRegularFile(AUTHORITY), "missing authority csv");

        List<String[]> mergeRows = readCsv(MERGE_PREVIEW);
        assertFalse(mergeRows.isEmpty());
        // header: item_id,中文名,...,6A建议档次,...
        List<String[]> data = mergeRows.subList(1, mergeRows.size());
        assertEquals(167, data.size(), "expected 167 NEW dishes in merge preview");

        Map<String, Integer> authCounts = new HashMap<>();
        Map<String, String> authLevel = new HashMap<>();
        for (String[] r : readCsv(AUTHORITY).subList(1, readCsv(AUTHORITY).size())) {
            if (r.length < 6) continue;
            String id = r[5].trim();
            if (id.isEmpty()) continue;
            authCounts.merge(id, 1, Integer::sum);
            authLevel.put(id, r[0].trim());
        }
        assertEquals(598, authCounts.size(), "authority unique ids"); // 6D.2: +3 plate_of_stuffed_hoglin T2

        Map<String, String> itemTiers = loadItemTiers();
        Map<String, String> entryToCat = loadFgEntryCategories();
        Set<String> commonTag = tagValues("chef_common.json");
        Set<String> t2Tag = tagValues("chef_t2.json");
        Set<String> t3Tag = tagValues("chef_t3.json");

        Set<String> serving = idSet(SERVING, 1);
        Set<String> drink = idSet(DRINK, 1);
        Set<String> ingredient = idSet(INGREDIENT, 1);

        Set<String> seen167 = new HashSet<>();
        int commonN = 0, t2N = 0;
        for (String[] r : data) {
            String id = r[0].trim();
            String suggest = r[4].trim();
            assertTrue(seen167.add(id), "duplicate in merge preview: " + id);
            assertEquals(1, authCounts.getOrDefault(id, 0), "authority occurrences for " + id);
            String expectedLevel = suggest.equals("COMMON") ? "1" : "2";
            assertEquals(expectedLevel, authLevel.get(id), "authority level for " + id);
            assertTrue(itemTiers.containsKey(id), "missing item tier json for " + id);
            assertEquals(suggest, itemTiers.get(id), "item tier for " + id);

            String entryId = "item:" + id.replace(':', '/');
            assertTrue(entryToCat.containsKey(entryId), "missing FG entry " + entryId);
            String cat = entryToCat.get(entryId);
            if (suggest.equals("COMMON")) {
                assertEquals("chef_common", cat, id);
                assertTrue(commonTag.contains(id), id + " in chef_common tag");
                assertFalse(t2Tag.contains(id));
                assertFalse(t3Tag.contains(id));
                commonN++;
            } else {
                assertEquals("T2", suggest);
                assertEquals("chef_t2", cat, id);
                assertTrue(t2Tag.contains(id), id + " in chef_t2 tag");
                assertFalse(commonTag.contains(id));
                assertFalse(t3Tag.contains(id));
                t2N++;
            }
            assertFalse(serving.contains(id), "SERVING_DISH leaked: " + id);
            assertFalse(drink.contains(id), "DRINK leaked: " + id);
            assertFalse(ingredient.contains(id), "INGREDIENT leaked: " + id);
            assertFalse(id.equals("kaleidoscope_cookery:raw_dough"));
        }
        assertEquals(38, commonN);
        assertEquals(129, t2N);
        assertEquals(167, seen167.size());
    }

    private static Map<String, String> loadItemTiers() throws IOException {
        Path items = PRESET.resolve("dish_tiers/items");
        Map<String, String> tiers = new HashMap<>();
        try (var walk = Files.walk(items)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
                Path rel = items.relativize(f);
                String ns = rel.getName(0).toString();
                String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
                path = path.substring(0, path.length() - ".json".length());
                String id = ns + ":" + path;
                assertFalse(tiers.containsKey(id), "dup tier " + id);
                tiers.put(id, o.get("tier").getAsString());
            }
        }
        return tiers;
    }

    private static Map<String, String> loadFgEntryCategories() throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String cat : List.of("chef_common", "chef_t2", "chef_t3")) {
            JsonObject o = GSON.fromJson(Files.readString(
                    PRESET.resolve("fieldguide/categories/" + cat + ".json"), StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement e : o.getAsJsonArray("contents")) {
                String entryId = e.getAsJsonObject().get("id").getAsString();
                assertFalse(map.containsKey(entryId), "cross-category FG dup " + entryId);
                map.put(entryId, cat);
            }
        }
        return map;
    }

    private static Set<String> tagValues(String file) throws Exception {
        JsonObject o = GSON.fromJson(Files.readString(PRESET.resolve("tags/item/" + file), StandardCharsets.UTF_8),
                JsonObject.class);
        return o.getAsJsonArray("values").asList().stream()
                .map(JsonElement::getAsString)
                .filter(s -> !s.startsWith("#"))
                .collect(Collectors.toSet());
    }

    private static Set<String> idSet(Path csv, int idCol) throws IOException {
        if (!Files.isRegularFile(csv)) return Set.of();
        List<String[]> rows = readCsv(csv);
        Set<String> s = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).length > idCol) s.add(rows.get(i)[idCol].trim());
        }
        return s;
    }

    private static List<String[]> readCsv(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<String[]> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else inQ = false;
                } else cell.append(c);
            } else if (c == '"') {
                inQ = true;
            } else if (c == ',') {
                cur.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                cur.add(cell.toString());
                cell.setLength(0);
                if (!(cur.size() == 1 && cur.get(0).isEmpty())) {
                    rows.add(cur.toArray(new String[0]));
                }
                cur = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !cur.isEmpty()) {
            cur.add(cell.toString());
            rows.add(cur.toArray(new String[0]));
        }
        return rows;
    }
}
