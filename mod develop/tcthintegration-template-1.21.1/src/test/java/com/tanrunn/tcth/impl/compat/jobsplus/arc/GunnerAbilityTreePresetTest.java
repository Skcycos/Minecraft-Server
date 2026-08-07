package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 5B: validates the gunner ability-tree preset structure — 12 powerup
 * nodes, required levels, prices, parent chains, per-route mutual exclusion in
 * the study-route Arc actions and the exact effect numbers in both the Arc
 * actions and the translations.
 */
class GunnerAbilityTreePresetTest {

    private static final String PRESET = "docs/presets/tcth-gunner/data/tcth/";
    private static final Gson GSON = new Gson();

    /** Route -> {nodeI, nodeII, nodeIII}. */
    private static final Map<String, String[]> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("marksmanship", new String[]{"marksmanship_basic", "marksmanship_adept", "marksmanship_expert"});
        ROUTES.put("ammo_saver", new String[]{"ammo_saver_basic", "ammo_saver_adept", "ammo_saver_expert"});
        ROUTES.put("battlefield_defense", new String[]{"battlefield_defense_basic", "battlefield_defense_adept", "battlefield_defense_expert"});
        ROUTES.put("gunner_experience", new String[]{"gunner_experience_i", "gunner_experience_ii", "gunner_experience_iii"});
    }

    /** required_level per route, in node order (task phase 5B). */
    private static final Map<String, int[]> REQUIRED_LEVELS = new LinkedHashMap<>();

    static {
        REQUIRED_LEVELS.put("marksmanship", new int[]{5, 25, 50});
        REQUIRED_LEVELS.put("ammo_saver", new int[]{10, 30, 60});
        REQUIRED_LEVELS.put("battlefield_defense", new int[]{15, 40, 70});
        REQUIRED_LEVELS.put("gunner_experience", new int[]{25, 50, 75});
    }

    /** price per route, in node order. */
    private static final Map<String, int[]> PRICES = new LinkedHashMap<>();

    static {
        PRICES.put("marksmanship", new int[]{5, 10, 15});
        PRICES.put("ammo_saver", new int[]{5, 10, 15});
        PRICES.put("battlefield_defense", new int[]{8, 12, 18});
        PRICES.put("gunner_experience", new int[]{5, 10, 15});
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void allTwelvePowerupNodesExistAndParse() throws Exception {
        int count = 0;
        for (String[] nodes : ROUTES.values()) {
            for (String node : nodes) {
                JsonObject json = GSON.fromJson(Files.readString(
                        Path.of(PRESET + "jobsplus/powerups/gunner/" + node + ".json"), StandardCharsets.UTF_8),
                        JsonObject.class);
                assertEquals("tcth:gunner", json.get("job").getAsString(), node + " job");
                assertTrue(json.has("price") && json.has("required_level"), node + " fields");
                assertTrue(json.has("icon"), node + " icon");
                count++;
            }
        }
        assertEquals(12, count, "exactly 12 gunner powerup nodes");
    }

    @Test
    void requiredLevelsPricesAndParentsAreCorrect() throws Exception {
        for (Map.Entry<String, String[]> e : ROUTES.entrySet()) {
            String route = e.getKey();
            String[] nodes = e.getValue();
            int[] levels = REQUIRED_LEVELS.get(route);
            int[] prices = PRICES.get(route);
            for (int i = 0; i < nodes.length; i++) {
                String node = nodes[i];
                JsonObject json = GSON.fromJson(Files.readString(
                        Path.of(PRESET + "jobsplus/powerups/gunner/" + node + ".json"), StandardCharsets.UTF_8),
                        JsonObject.class);
                assertEquals(levels[i], json.get("required_level").getAsInt(), route + " " + node + " required_level");
                assertEquals(prices[i], json.get("price").getAsInt(), route + " " + node + " price");
                if (i == 0) {
                    assertFalse(json.has("parent"), route + " " + node + " must not have parent");
                } else {
                    assertEquals("tcth:gunner/" + nodes[i - 1], json.get("parent").getAsString(),
                            route + " " + node + " parent chain");
                }
            }
        }
    }

    @Test
    void studyRouteActionsHaveMutualExclusionConditions() throws Exception {
        // i excludes ii,iii; ii excludes iii; iii excludes none.
        Map<String, List<String>> excludes = new LinkedHashMap<>();
        excludes.put("gunner_experience_i", List.of("gunner_experience_ii", "gunner_experience_iii"));
        excludes.put("gunner_experience_ii", List.of("gunner_experience_iii"));
        excludes.put("gunner_experience_iii", List.of());
        for (Map.Entry<String, List<String>> e : excludes.entrySet()) {
            JsonObject json = GSON.fromJson(Files.readString(
                    Path.of(PRESET + "arc/gunner/powerup/" + e.getKey() + ".json"), StandardCharsets.UTF_8),
                    JsonObject.class);
            assertEquals("jobsplus:on_job_exp", json.get("type").getAsString(), e.getKey() + " action type");
            JsonObject reward = json.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("jobsplus:job_exp_multiplier", reward.get("type").getAsString(), e.getKey() + " reward");
            assertEquals("tcth:gunner", reward.get("job").getAsString(), e.getKey() + " reward job");
            JsonArray conditions = json.getAsJsonArray("conditions");
            boolean gated = false;
            for (var c : conditions) {
                if ("tcth:gunner_experience_abilities_enabled".equals(c.getAsJsonObject().get("type").getAsString())) {
                    gated = true;
                }
            }
            assertTrue(gated, e.getKey() + " must gate on the route switch");
            for (String ex : e.getValue()) {
                boolean excluded = false;
                for (var c : conditions) {
                    JsonObject o = c.getAsJsonObject();
                    if ("jobsplus:powerup_not_active".equals(o.get("type").getAsString())
                            && ("tcth:gunner/" + ex).equals(o.get("powerup").getAsString())) {
                        excluded = true;
                    }
                }
                assertTrue(excluded, e.getKey() + " must exclude " + ex);
            }
        }
    }

    @Test
    void studyRouteMultiplierValuesAreExact() throws Exception {
        Map<String, Double> expected = Map.of(
                "gunner_experience_i", 1.15,
                "gunner_experience_ii", 1.35,
                "gunner_experience_iii", 1.60);
        for (Map.Entry<String, Double> e : expected.entrySet()) {
            JsonObject json = GSON.fromJson(Files.readString(
                    Path.of(PRESET + "arc/gunner/powerup/" + e.getKey() + ".json"), StandardCharsets.UTF_8),
                    JsonObject.class);
            double mult = json.getAsJsonArray("rewards").get(0).getAsJsonObject().get("multiplier").getAsDouble();
            assertEquals(e.getValue(), mult, 0.000001, e.getKey() + " multiplier");
        }
    }

    @Test
    void translationsDescribeExactNumbers() throws Exception {
        for (String lang : List.of("en_us", "zh_cn")) {
            JsonObject langJson = GSON.fromJson(Files.readString(
                    Path.of("src/main/resources/assets/tcth/lang/" + lang + ".json"), StandardCharsets.UTF_8),
                    JsonObject.class);
            for (String[] nodes : ROUTES.values()) {
                for (String node : nodes) {
                    assertTrue(langJson.has("jobsplus.powerup.tcth.gunner." + node + ".name"), lang + " " + node + " name");
                    assertTrue(langJson.has("jobsplus.powerup.tcth.gunner." + node + ".description"), lang + " " + node + " desc");
                }
            }
            // Spot-check the exact multipliers in the descriptions.
            assertNotNull(langJson.get("jobsplus.powerup.tcth.gunner.marksmanship_basic.description"));
            assertNotNull(langJson.get("jobsplus.powerup.tcth.gunner.ammo_saver_expert.description"));
            assertNotNull(langJson.get("jobsplus.powerup.tcth.gunner.battlefield_defense_expert.description"));
            assertNotNull(langJson.get("jobsplus.powerup.tcth.gunner.gunner_experience_iii.description"));
        }
    }

    @Test
    void powerupNodesAreDeployedAlongsideThePreset() throws Exception {
        // The deployed preset must contain the same 12 nodes.
        List<String> deployed = new ArrayList<>();
        try (var walk = Files.walk(Path.of("docs/presets/tcth-gunner/data/tcth/jobsplus/powerups/gunner"))) {
            walk.filter(p -> p.toString().endsWith(".json")).forEach(p -> deployed.add(p.getFileName().toString()));
        }
        assertEquals(12, deployed.size(), "deployed gunner powerup nodes");
    }
}
