package com.tanrunn.tcth.impl.compat.fieldguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Static validation of the generated chef cookbook data inside the tcth-chef
 * preset: tier tags are mutually exclusive, the catalog is their union,
 * raw_dough is excluded, categories parse and reference the right tags, and
 * every vanilla item exists in the registries (mod items are validated by
 * resource-location shape; their registry presence is verified on the server).
 */
class FieldGuideDataTest {

    private static final Path PRESET = Path.of(
            "docs/presets/tcth-chef/data/tcth");
    private static final Gson GSON = new Gson();
    private static final String RAW_DOUGH = "kaleidoscope_cookery:raw_dough";

    private static final Map<String, Path> TIER_TAGS = Map.of(
            "COMMON", PRESET.resolve("tags/item/chef_common.json"),
            "T2", PRESET.resolve("tags/item/chef_t2.json"),
            "T3", PRESET.resolve("tags/item/chef_t3.json"));

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private static Set<String> tagValues(Path tag) throws IOException {
        JsonObject obj = GSON.fromJson(
                Files.readString(tag, StandardCharsets.UTF_8), JsonObject.class);
        Set<String> values = new HashSet<>();
        for (JsonElement el : obj.getAsJsonArray("values")) {
            values.add(el.getAsString());
        }
        return values;
    }

    private static Set<String> catalogRefs() throws IOException {
        Path catalog = PRESET.resolve("tags/item/chef_catalog.json");
        JsonObject obj = GSON.fromJson(
                Files.readString(catalog, StandardCharsets.UTF_8), JsonObject.class);
        Set<String> refs = new HashSet<>();
        for (JsonElement el : obj.getAsJsonArray("values")) {
            refs.add(el.getAsString());
        }
        return refs;
    }

    // ---- 16. COMMON/T2/T3 标签互斥 ----

    @Test
    void tierTagsAreMutuallyExclusive() throws IOException {
        Set<String> common = tagValues(TIER_TAGS.get("COMMON"));
        Set<String> t2 = tagValues(TIER_TAGS.get("T2"));
        Set<String> t3 = tagValues(TIER_TAGS.get("T3"));

        assertTrue(intersection(common, t2).isEmpty(), "COMMON and T2 must not overlap");
        assertTrue(intersection(common, t3).isEmpty(), "COMMON and T3 must not overlap");
        assertTrue(intersection(t2, t3).isEmpty(), "T2 and T3 must not overlap");
        assertTrue(intersection(intersection(common, t2), t3).isEmpty());
    }

    // ---- 17. catalog 等于三等级并集 ----

    @Test
    void catalogEqualsUnionOfTierTags() throws IOException {
        Set<String> union = new HashSet<>();
        for (Path tag : TIER_TAGS.values()) {
            union.addAll(tagValues(tag));
        }
        // The catalog references the three tags; resolve them to item ids.
        Set<String> refs = catalogRefs();
        assertEquals(Set.of("#tcth:chef_common", "#tcth:chef_t2", "#tcth:chef_t3"), refs,
                "catalog must reference exactly the three tier tags");
        assertEquals(union.size(), union.stream().distinct().count(), "catalog must have no duplicates");
        assertEquals(166, union.size(), "expected 166 dishes (84 COMMON / 58 T2 / 24 T3)");
    }

    // ---- 18. raw_dough 被排除 ----

    @Test
    void rawDoughIsExcludedFromEveryTag() throws IOException {
        for (Path tag : TIER_TAGS.values()) {
            assertFalse(tagValues(tag).contains(RAW_DOUGH),
                    "raw_dough must not appear in " + tag);
        }
    }

    // ---- 19/20. 分类 JSON 可解析且条目正确 ----

    @Test
    void categoryJsonParsesWithEntryPerItemAndIcon() throws IOException {
        Map<String, Integer> expected = Map.of(
                "chef_common", 84,
                "chef_t2", 58,
                "chef_t3", 24);
        for (Map.Entry<String, Integer> en : expected.entrySet()) {
            Path cat = PRESET.resolve("fieldguide/categories/" + en.getKey() + ".json");
            JsonObject obj = GSON.fromJson(
                    Files.readString(cat, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray contents = obj.getAsJsonArray("contents");
            assertEquals(en.getValue().intValue(), contents.size(),
                    en.getKey() + " must list one entry per dish");
            assertNotNull(obj.get("icon"), "category must define a texture icon");
            assertNotNull(obj.get("sort_index"), "category must define a sort index");
        }
    }

    // ---- Field Guide 1.13.4 默认 OBTAIN 触发器必须被 gate 阻止 ----

    @Test
    void everyEntryPinsUnlockGatePrerequisite() throws IOException {
        for (String tier : new String[] {"chef_common", "chef_t2", "chef_t3"}) {
            Path cat = PRESET.resolve("fieldguide/categories/" + tier + ".json");
            JsonObject obj = GSON.fromJson(
                    Files.readString(cat, StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement el : obj.getAsJsonArray("contents")) {
                JsonObject content = el.getAsJsonObject();
                assertEquals("entry", content.get("type").getAsString(),
                        tier + ": explicit entries (auto_populate cannot disable OBTAIN)");
                assertTrue(content.get("id").getAsString().startsWith("item:"),
                        tier + ": entry id must use the Field Guide item key form");
                JsonObject unlock = content.getAsJsonObject("unlock");
                assertNotNull(unlock, tier + ": each entry must pin an explicit unlock block");
                assertTrue(unlock.getAsJsonArray("prerequisites").toString().contains("chef_cookbook_gate"),
                        tier + ": must reference the never-satisfied gate");
            }
        }
    }

    // ---- 最小验证：3 道料理必须入册 ----

    @Test
    void minimalThreeDishesAreInCatalog() throws IOException {
        Set<String> catalog = new HashSet<>();
        for (Path tag : TIER_TAGS.values()) {
            catalog.addAll(tagValues(tag));
        }
        assertTrue(catalog.contains("minecraft:cooked_cod"));
        assertTrue(catalog.contains("farmersdelight:cooked_chicken_cuts"));
        assertTrue(catalog.contains("kaleidoscope_cookery:blaze_lamb_chop"));
    }

    // ---- 每个原版物品存在于注册表；mod 物品校验格式 ----

    @Test
    void vanillaItemsExistInRegistryAndModItemsHaveValidIds() throws IOException {
        Set<String> catalog = new HashSet<>();
        for (Path tag : TIER_TAGS.values()) {
            catalog.addAll(tagValues(tag));
        }
        for (String id : catalog) {
            ResourceLocation rl = ResourceLocation.parse(id);
            if (rl.getNamespace().equals("minecraft")) {
                assertTrue(BuiltInRegistries.ITEM.containsKey(rl),
                        "vanilla item missing from registry: " + id);
            } else {
                assertTrue(rl.getPath().matches("[a-z0-9/._-]+"),
                        "mod item id has invalid path: " + id);
                assertTrue(List.of("farmersdelight", "kaleidoscope_cookery", "minecraft")
                        .contains(rl.getNamespace()),
                        "unexpected namespace in catalog: " + id);
            }
        }
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }
}
