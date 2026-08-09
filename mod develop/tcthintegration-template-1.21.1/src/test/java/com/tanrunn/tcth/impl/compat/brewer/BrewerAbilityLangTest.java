package com.tanrunn.tcth.impl.compat.brewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 7E: every brewer ability node must have a name and a description in
 * BOTH en_us and zh_cn, and the 12 nodes × 2 keys must match the 12 powerup
 * data files exactly (no missing, no orphaned keys). The numeric values in the
 * descriptions are verified by {@link BrewerAbilityDataTest} against the
 * powerup data files; here we only guarantee key presence/parity per language.
 */
class BrewerAbilityLangTest {

    private static final Gson GSON = new Gson();

    private static final List<String> NODES = List.of(
            "brewing_basic", "brewing_adept", "brewing_expert",
            "tasting_basic", "tasting_adept", "tasting_expert",
            "resistance_basic", "resistance_adept", "resistance_expert",
            "study_i", "study_ii", "study_iii");

    private static JsonObject lang(String file) throws Exception {
        return GSON.fromJson(Files.readString(Path.of(file), StandardCharsets.UTF_8), JsonObject.class);
    }

    @Test
    void everyNodeHasNameAndDescriptionInBothLanguages() throws Exception {
        JsonObject en = lang("src/main/resources/assets/tcth/lang/en_us.json");
        JsonObject zh = lang("src/main/resources/assets/tcth/lang/zh_cn.json");
        for (String node : NODES) {
            String nameEn = "jobsplus.powerup.tcth.brewer." + node + ".name";
            String descEn = "jobsplus.powerup.tcth.brewer." + node + ".description";
            String nameZh = "jobsplus.powerup.tcth.brewer." + node + ".name";
            String descZh = "jobsplus.powerup.tcth.brewer." + node + ".description";
            assertTrue(en.has(nameEn) && en.has(descEn), "en_us must define " + node + " name+description");
            assertTrue(zh.has(nameZh) && zh.has(descZh), "zh_cn must define " + node + " name+description");
            assertFalse(en.get(nameEn).getAsString().isBlank(), "en_us " + node + " name must not be blank");
            assertFalse(zh.get(nameZh).getAsString().isBlank(), "zh_cn " + node + " name must not be blank");
        }
    }

    @Test
    void exactlyTwentyFourPowerupKeysPerLanguage() throws Exception {
        for (String file : new String[] {
                "src/main/resources/assets/tcth/lang/en_us.json",
                "src/main/resources/assets/tcth/lang/zh_cn.json"}) {
            JsonObject lang = lang(file);
            int powerupKeys = lang.keySet().stream()
                    .filter(k -> k.startsWith("jobsplus.powerup.tcth.brewer."))
                    .mapToInt(k -> 1).sum();
            assertTrue(powerupKeys == 24,
                    file + " must define exactly 24 brewer powerup keys, got " + powerupKeys);
        }
    }

    @Test
    void noOrphanedBrewerPowerupKeys() throws Exception {
        for (String file : new String[] {
                "src/main/resources/assets/tcth/lang/en_us.json",
                "src/main/resources/assets/tcth/lang/zh_cn.json"}) {
            JsonObject lang = lang(file);
            for (String key : lang.keySet()) {
                if (!key.startsWith("jobsplus.powerup.tcth.brewer.")) {
                    continue;
                }
                // key format: jobsplus.powerup.tcth.brewer.<node>.<name|description>
                String suffix = key.substring("jobsplus.powerup.tcth.brewer.".length());
                int dot = suffix.lastIndexOf('.');
                assertTrue(dot > 0, file + " malformed key: " + key);
                String node = suffix.substring(0, dot);
                String field = suffix.substring(dot + 1);
                assertTrue(NODES.contains(node), file + " orphaned node key: " + key);
                assertTrue(field.equals("name") || field.equals("description"),
                        file + " unexpected field: " + key);
            }
        }
    }

    @Test
    void sevenEDescriptionAndConfigKeysPresent() throws Exception {
        for (String file : new String[] {
                "src/main/resources/assets/tcth/lang/en_us.json",
                "src/main/resources/assets/tcth/lang/zh_cn.json"}) {
            JsonObject lang = lang(file);
            // Config keys
            for (String cfg : new String[] {"brewerAbilitiesEnabled", "brewerBrewingAbilitiesEnabled",
                    "brewerTastingAbilitiesEnabled", "brewerResistanceAbilitiesEnabled",
                    "brewerStudyAbilitiesEnabled", "brewerDrinkCooldownTicks"}) {
                assertTrue(lang.has("tcth.configuration." + cfg), file + " missing config key " + cfg);
            }
            // Condition keys (name + description)
            for (String cond : new String[] {"brewer_study_abilities_enabled",
                    "brewer_tasting_abilities_enabled", "brewer_drink_cooldown"}) {
                assertTrue(lang.has("tcth.condition." + cond + ".name"), file + " missing condition name " + cond);
                assertTrue(lang.has("tcth.condition." + cond + ".desc"), file + " missing condition desc " + cond);
            }
            // Reward keys
            assertTrue(lang.has("tcth.reward.brewer_tasting_effects.name"), file + " missing reward name");
            assertTrue(lang.has("tcth.reward.brewer_tasting_effects.desc"), file + " missing reward desc");
        }
    }
}
