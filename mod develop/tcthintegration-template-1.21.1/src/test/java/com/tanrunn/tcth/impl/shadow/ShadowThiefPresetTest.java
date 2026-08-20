package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Phase 8E preset test: {@code docs/presets/tcth-shadow-thief/} — the job
 * definition, the 12 powerup nodes with exact required_level/price/parent,
 * the per-route {@code jobsplus:powerup_not_active} exclusion structure, the
 * XP reward bands of the five Arc actions, the bilingual node keys and the
 * preset-not-in-main-JAR rule.
 */
class ShadowThiefPresetTest {

    private static final Path PRESET = Path.of("docs/presets/tcth-shadow-thief");
    private static final Path LANG_EN = Path.of("src/main/resources/assets/tcth/lang/en_us.json");
    private static final Path LANG_ZH = Path.of("src/main/resources/assets/tcth/lang/zh_cn.json");

    /** node id → (required_level, price, parent id or null). */
    private static final Map<String, int[]> NODES = new HashMap<>();
    private static final Map<String, String> PARENTS = new HashMap<>();

    static {
        NODES.put("sleight_of_hand_i", new int[] { 5, 5 });
        NODES.put("sleight_of_hand_ii", new int[] { 20, 10 });
        NODES.put("sleight_of_hand_iii", new int[] { 45, 15 });
        NODES.put("life_siphon_i", new int[] { 10, 5 });
        NODES.put("life_siphon_ii", new int[] { 30, 10 });
        NODES.put("life_siphon_iii", new int[] { 60, 15 });
        NODES.put("spell_theft_i", new int[] { 15, 5 });
        NODES.put("spell_theft_ii", new int[] { 35, 10 });
        NODES.put("spell_theft_iii", new int[] { 55, 15 });
        NODES.put("shadow_escape_i", new int[] { 25, 5 });
        NODES.put("shadow_escape_ii", new int[] { 50, 10 });
        NODES.put("shadow_escape_iii", new int[] { 75, 15 });
        PARENTS.put("sleight_of_hand_ii", "sleight_of_hand_i");
        PARENTS.put("sleight_of_hand_iii", "sleight_of_hand_ii");
        PARENTS.put("life_siphon_ii", "life_siphon_i");
        PARENTS.put("life_siphon_iii", "life_siphon_ii");
        PARENTS.put("spell_theft_ii", "spell_theft_i");
        PARENTS.put("spell_theft_iii", "spell_theft_ii");
        PARENTS.put("shadow_escape_ii", "shadow_escape_i");
        PARENTS.put("shadow_escape_iii", "shadow_escape_ii");
    }

    /** node id → route chain (exclusion order). */
    private static final Map<String, List<String>> CHAINS = new HashMap<>();

    static {
        CHAINS.put("sleight_of_hand", List.of("sleight_of_hand_i", "sleight_of_hand_ii", "sleight_of_hand_iii"));
        CHAINS.put("life_siphon", List.of("life_siphon_i", "life_siphon_ii", "life_siphon_iii"));
        CHAINS.put("spell_theft", List.of("spell_theft_i", "spell_theft_ii", "spell_theft_iii"));
        CHAINS.put("shadow_escape", List.of("shadow_escape_i", "shadow_escape_ii", "shadow_escape_iii"));
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject lang(Path path) throws IOException {
        return readJson(path);
    }

    // ---- job ----

    @Test
    void jobDefinitionIsExact() throws IOException {
        JsonObject job = readJson(PRESET.resolve("data/tcth/jobsplus/jobs/shadow_thief.json"));
        assertEquals(100, job.get("max_level").getAsInt());
        assertFalse(job.get("is_default").getAsBoolean());
        assertEquals("minecraft:echo_shard", job.getAsJsonObject("icon").get("id").getAsString());
        // The job JSON must NOT hardcode name/description — language keys only.
        assertFalse(job.has("name"), "no hardcoded job name");
        assertFalse(job.has("description"), "no hardcoded job description");
        assertFalse(job.has("display_name"), "no hardcoded display name");
    }

    // ---- 12 nodes ----

    @Test
    void allTwelveNodesExistWithExactValues() throws IOException {
        Path powerups = PRESET.resolve("data/tcth/jobsplus/powerups/shadow_thief");
        try (Stream<Path> walk = Files.walk(powerups)) {
            List<String> files = walk.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
            assertEquals(12, files.size(), "exactly 12 powerup nodes");
        }
        for (Map.Entry<String, int[]> entry : NODES.entrySet()) {
            JsonObject node = readJson(powerups.resolve(entry.getKey() + ".json"));
            assertEquals("tcth:shadow_thief", node.get("job").getAsString());
            assertEquals(entry.getValue()[0], node.get("required_level").getAsInt(),
                    entry.getKey() + " required_level");
            assertEquals(entry.getValue()[1], node.get("price").getAsInt(),
                    entry.getKey() + " price");
            String parent = PARENTS.get(entry.getKey());
            if (parent == null) {
                assertFalse(node.has("parent"), entry.getKey() + " must have no parent");
            } else {
                assertEquals("tcth:shadow_thief/" + parent, node.get("parent").getAsString(),
                        entry.getKey() + " parent");
            }
            assertEquals("minecraft:echo_shard",
                    node.getAsJsonObject("icon").get("id").getAsString());
        }
    }

    // ---- mutual exclusion ----

    @Test
    void eachRouteExcludesHigherTiers() throws IOException {
        Path arc = PRESET.resolve("data/tcth/arc/shadow_thief/powerup");
        for (Map.Entry<String, List<String>> chain : CHAINS.entrySet()) {
            List<String> nodes = chain.getValue();
            for (int i = 0; i < nodes.size(); i++) {
                JsonObject action = readJson(arc.resolve(nodes.get(i) + ".json"));
                // holder = the node's own powerup (ACTIVE required by Jobs+).
                assertEquals("jobsplus:powerup",
                        action.getAsJsonObject("holder").get("type").getAsString());
                assertEquals("tcth:shadow_thief/" + nodes.get(i),
                        action.getAsJsonObject("holder").get("id").getAsString());
                assertEquals("tcth:on_shadow_theft_success", action.get("type").getAsString());
                JsonArray conditions = action.getAsJsonArray("conditions");
                List<String> excluded = new ArrayList<>();
                for (JsonElement condition : conditions) {
                    JsonObject c = condition.getAsJsonObject();
                    if ("jobsplus:powerup_not_active".equals(c.get("type").getAsString())) {
                        excluded.add(c.get("powerup").getAsString());
                    }
                }
                // I excludes II and III; II excludes III; III excludes nothing.
                List<String> expected = nodes.subList(i + 1, nodes.size()).stream()
                        .map(n -> "tcth:shadow_thief/" + n).toList();
                assertEquals(expected, excluded,
                        nodes.get(i) + " must exclude exactly " + expected);
            }
        }
    }

    @Test
    void exclusionActionsAreRewardFreeDeclarations() throws IOException {
        Path arc = PRESET.resolve("data/tcth/arc/shadow_thief/powerup");
        for (String node : NODES.keySet()) {
            JsonObject action = readJson(arc.resolve(node + ".json"));
            assertTrue(action.has("rewards"));
            assertEquals(0, action.getAsJsonArray("rewards").size(),
                    node + " exclusion action carries no rewards");
        }
    }

    // ---- XP bands ----

    @Test
    void xpBandsAreExact() throws IOException {
        Path arc = PRESET.resolve("data/tcth/arc/shadow_thief");
        Map<String, int[]> expected = new HashMap<>();
        expected.put("entity.json", new int[] { 1, 2 });
        expected.put("player_item.json", new int[] { 3, 5 });
        expected.put("player_health.json", new int[] { 2, 4 });
        expected.put("player_hunger.json", new int[] { 2, 4 });
        expected.put("player_effect.json", new int[] { 4, 6 });
        for (Map.Entry<String, int[]> e : expected.entrySet()) {
            JsonObject action = readJson(arc.resolve(e.getKey()));
            assertEquals("jobsplus:job", action.getAsJsonObject("holder").get("type").getAsString());
            assertEquals("tcth:shadow_thief", action.getAsJsonObject("holder").get("id").getAsString());
            assertEquals("tcth:on_shadow_theft_success", action.get("type").getAsString());
            JsonObject reward = action.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("jobsplus:job_exp", reward.get("type").getAsString());
            assertEquals(100, reward.get("chance").getAsInt());
            assertEquals(e.getValue()[0], reward.get("min").getAsInt(), e.getKey() + " min");
            assertEquals(e.getValue()[1], reward.get("max").getAsInt(), e.getKey() + " max");
        }
    }

    @Test
    void xpActionsCarryTheGatingConditions() throws IOException {
        Path arc = PRESET.resolve("data/tcth/arc/shadow_thief");
        for (String file : List.of("entity.json", "player_item.json", "player_health.json",
                "player_hunger.json", "player_effect.json")) {
            JsonObject action = readJson(arc.resolve(file));
            boolean rewardsEnabled = false;
            boolean automated = false;
            for (JsonElement c : action.getAsJsonArray("conditions")) {
                String type = c.getAsJsonObject().get("type").getAsString();
                if ("tcth:shadow_rewards_enabled".equals(type)) {
                    rewardsEnabled = true;
                }
                if ("tcth:automated".equals(type)
                        && !c.getAsJsonObject().get("value").getAsBoolean()) {
                    automated = true;
                }
            }
            assertTrue(rewardsEnabled, file + " must require tcth:shadow_rewards_enabled");
            assertTrue(automated, file + " must require automated=false");
        }
    }

    // ---- language keys ----

    @Test
    void jobAndNodeKeysExistInBothLanguages() throws IOException {
        JsonObject en = lang(LANG_EN);
        JsonObject zh = lang(LANG_ZH);
        assertEquals(en.keySet(), zh.keySet(), "en_us and zh_cn must have identical key sets");
        assertEquals("Shadow Thief", en.get("jobsplus.job.tcth.shadow_thief.name").getAsString());
        assertEquals("影窃者", zh.get("jobsplus.job.tcth.shadow_thief.name").getAsString());
        for (String node : NODES.keySet()) {
            String name = "jobsplus.powerup.tcth.shadow_thief." + node + ".name";
            String desc = "jobsplus.powerup.tcth.shadow_thief." + node + ".description";
            assertTrue(en.has(name) && zh.has(name), "missing name key " + node);
            assertTrue(en.has(desc) && zh.has(desc), "missing description key " + node);
            assertFalse(en.get(name).getAsString().isBlank(), "blank EN name " + node);
            assertFalse(zh.get(name).getAsString().isBlank(), "blank ZH name " + node);
            assertFalse(en.get(desc).getAsString().isBlank(), "blank EN description " + node);
            assertFalse(zh.get(desc).getAsString().isBlank(), "blank ZH description " + node);
        }
    }

    @Test
    void descriptionsMatchTheCodeNumbers() throws IOException {
        JsonObject en = lang(LANG_EN);
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.sleight_of_hand_i.description")
                .getAsString().contains("+5%"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.sleight_of_hand_i.description")
                .getAsString().contains("200→180"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.sleight_of_hand_ii.description")
                .getAsString().contains("200→160"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.sleight_of_hand_iii.description")
                .getAsString().contains("200→140"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.life_siphon_iii.description")
                .getAsString().contains("4 health"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.spell_theft_iii.description")
                .getAsString().contains("30 seconds"));
        assertTrue(en.get("jobsplus.powerup.tcth.shadow_thief.shadow_escape_iii.description")
                .getAsString().contains("×0.4"));
        // The zh descriptions carry the same numbers.
        assertTrue(zhLang().get("jobsplus.powerup.tcth.shadow_thief.sleight_of_hand_ii.description")
                .getAsString().contains("200→160"));
        assertTrue(zhLang().get("jobsplus.powerup.tcth.shadow_thief.spell_theft_i.description")
                .getAsString().contains("10 秒"));
    }

    private static JsonObject zhLang() throws IOException {
        return lang(LANG_ZH);
    }

    // ---- packaging ----

    @Test
    void presetIsNotShippedInTheMainJarResources() throws IOException {
        Path resources = Path.of("src/main/resources");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = resources.relativize(p).toString().replace('\\', '/');
                if (rel.contains("shadow_thief") && (rel.contains("jobsplus") || rel.contains("/arc/"))) {
                    offenders.add(rel);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "no shadow_thief job/powerup/arc data may ship in the main JAR: " + offenders);
    }

    @Test
    void packMetaIsFormat48() throws IOException {
        JsonObject meta = readJson(PRESET.resolve("pack.mcmeta"));
        assertEquals(48, meta.getAsJsonObject("pack").get("pack_format").getAsInt());
    }

    @Test
    void presetReadmeExists() {
        assertTrue(PRESET.resolve("README.md").toFile().isFile());
    }
}
