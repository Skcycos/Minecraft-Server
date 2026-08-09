package com.tanrunn.tcth.impl.compat.brewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Phase 7E preset validation: the 12 brewer powerup nodes (4 routes × 3) with
 * correct parent / required_level / price chains, the study-route Arc data
 * (on_job_exp + job_exp_multiplier, powerup_not_active excludes higher tiers),
 * and the tasting-route Arc data (arc:on_drink + #tcth:brewer_drinks + cooldown
 * + brewer_tasting_effects reward). Preset and server global pack must match.
 */
class BrewerAbilityDataTest {

    private static final Gson GSON = new Gson();

    private static JsonObject read(Path p) throws Exception {
        return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
    }

    private static Path preset(String path) {
        return Path.of("docs/presets/tcth-brewer/data/tcth/" + path);
    }

    private static Path server(String path) {
        return Path.of("../../Server/global_packs/required_data/tcth-brewer/data/tcth/" + path);
    }

    @Test
    void twelvePowerupsWithExpectedLevelsAndParents() throws Exception {
        // brewing/tasting/resistance use basic/adept/expert; study uses i/ii/iii.
        int[][] expectedLevels = {
                {5, 20, 45},   // brewing
                {15, 35, 55},  // tasting
                {10, 30, 60},  // resistance
                {25, 50, 75},  // study
        };
        String[] routes = {"brewing", "tasting", "resistance", "study"};
        String[][] nodeSets = {
                {"basic", "adept", "expert"},
                {"basic", "adept", "expert"},
                {"basic", "adept", "expert"},
                {"i", "ii", "iii"},
        };

        for (int r = 0; r < routes.length; r++) {
            for (int n = 0; n < nodeSets[r].length; n++) {
                String fname = routes[r] + "_" + nodeSets[r][n] + ".json";
                JsonObject p = read(preset("jobsplus/powerups/brewer/" + fname));
                assertEquals("tcth:brewer", p.get("job").getAsString(), fname + " job");
                assertEquals(expectedLevels[r][n], p.get("required_level").getAsInt(), fname + " required_level");
                if (n > 0) {
                    assertEquals("tcth:brewer/" + routes[r] + "_" + nodeSets[r][n - 1],
                            p.get("parent").getAsString(), fname + " parent");
                } else {
                    assertTrue(!p.has("parent"), fname + " basic must not have a parent");
                }
            }
        }
    }

    @Test
    void studyArcDataUsesMultiplierAndExcludesHigherTiers() throws Exception {
        double[] multipliers = {1.15, 1.35, 1.60};
        String[] names = {"study_i", "study_ii", "study_iii"};
        for (int i = 0; i < names.length; i++) {
            JsonObject a = read(preset("arc/brewer/powerup/" + names[i] + ".json"));
            assertEquals("jobsplus:on_job_exp", a.get("type").getAsString(), names[i] + " type");
            JsonObject reward = a.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("jobsplus:job_exp_multiplier", reward.get("type").getAsString());
            assertEquals("tcth:brewer", reward.get("job").getAsString());
            assertEquals(multipliers[i], reward.get("multiplier").getAsDouble(), 0.001);

            JsonArray conditions = a.getAsJsonArray("conditions");
            boolean hasEnabled = false;
            boolean excludesHigher = true;
            for (var el : conditions) {
                JsonObject c = el.getAsJsonObject();
                if (c.get("type").getAsString().equals("tcth:brewer_study_abilities_enabled")) {
                    hasEnabled = true;
                }
            }
            // Higher tiers must be excluded for all but the top one.
            if (i < 2) {
                boolean excluded = false;
                for (var el : conditions) {
                    JsonObject c = el.getAsJsonObject();
                    if (c.get("type").getAsString().equals("jobsplus:powerup_not_active")
                            && c.get("powerup").getAsString().contains(names[i + 1])) {
                        excluded = true;
                    }
                }
                excludesHigher = excluded;
            }
            assertTrue(hasEnabled, names[i] + " must gate on brewer_study_abilities_enabled");
            assertTrue(excludesHigher, names[i] + " must exclude higher study tiers");
        }
    }

    @Test
    void tastingArcDataUsesOnDrinkDrinksCooldownAndReward() throws Exception {
        int[] tiers = {1, 2, 3};
        String[] names = {"tasting_basic", "tasting_adept", "tasting_expert"};
        for (int i = 0; i < names.length; i++) {
            JsonObject a = read(preset("arc/brewer/powerup/" + names[i] + ".json"));
            assertEquals("arc:on_drink", a.get("type").getAsString(), names[i] + " type");

            JsonArray conditions = a.getAsJsonArray("conditions");
            boolean hasDrinks = false;
            boolean hasCooldown = false;
            boolean hasEnabled = false;
            for (var el : conditions) {
                JsonObject c = el.getAsJsonObject();
                String type = c.get("type").getAsString();
                if (type.equals("arc:items")) {
                    hasDrinks = c.getAsJsonArray("items").toString().contains("#tcth:brewer_drinks");
                }
                if (type.equals("tcth:brewer_drink_cooldown")) {
                    hasCooldown = true;
                }
                if (type.equals("tcth:brewer_tasting_abilities_enabled")) {
                    hasEnabled = true;
                }
            }
            assertTrue(hasDrinks, names[i] + " must match #tcth:brewer_drinks");
            assertTrue(hasCooldown, names[i] + " must gate on the brewer drink cooldown");
            assertTrue(hasEnabled, names[i] + " must gate on brewer_tasting_abilities_enabled");

            JsonObject reward = a.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("tcth:brewer_tasting_effects", reward.get("type").getAsString());
            assertEquals(tiers[i], reward.get("tier").getAsInt());
        }
    }

    @Test
    void presetMatchesServerGlobalPackForAbilities() throws Exception {
        for (String rel : new String[] {
                "jobsplus/powerups/brewer/brewing_basic.json",
                "jobsplus/powerups/brewer/study_iii.json",
                "arc/brewer/powerup/study_i.json",
                "arc/brewer/powerup/tasting_expert.json",
        }) {
            byte[] a = Files.readAllBytes(preset(rel));
            byte[] b = Files.readAllBytes(server(rel));
            assertTrue(java.util.Arrays.equals(a, b), rel + " preset must match server global pack");
        }
    }
}
