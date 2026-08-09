package com.tanrunn.tcth.impl.compat.brewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Phase 7C preset and Action-data validation: the tcth-brewer Arc action
 * holders (COMMON 1-2 / T2 3-5), mutually exclusive tier conditions,
 * automated=false gate, brewer job flags, and the BeverageActionDispatcher
 * data fields.
 */
class BrewerPresetDataTest {

    private static final Gson GSON = new Gson();

    private static JsonObject read(Path p) throws Exception {
        return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
    }

    @Test
    void brewCommonRewardRange() throws Exception {
        JsonObject action = read(Path.of(
                "docs/presets/tcth-brewer/data/tcth/arc/brewer/brew_common.json"));
        assertEquals("tcth:on_beverage_prepared", action.get("type").getAsString());
        JsonObject reward = action.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals("jobsplus:job_exp", reward.get("type").getAsString());
        assertEquals(1, reward.get("min").getAsInt());
        assertEquals(2, reward.get("max").getAsInt());
    }

    @Test
    void brewT2RewardRange() throws Exception {
        JsonObject action = read(Path.of(
                "docs/presets/tcth-brewer/data/tcth/arc/brewer/brew_t2.json"));
        JsonObject reward = action.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals(3, reward.get("min").getAsInt());
        assertEquals(5, reward.get("max").getAsInt());
    }

    @Test
    void tiersAreMutuallyExclusiveAndGated() throws Exception {
        Set<String> tiers = new HashSet<>();
        for (String f : new String[] {"brew_common.json", "brew_t2.json"}) {
            JsonObject action = read(Path.of("docs/presets/tcth-brewer/data/tcth/arc/brewer/" + f));
            // holder job
            assertEquals("tcth:brewer",
                    action.getAsJsonObject("holder").get("id").getAsString());
            // conditions
            JsonArray conds = action.getAsJsonArray("conditions");
            boolean hasRewardsEnabled = false;
            boolean hasAutomatedFalse = false;
            boolean hasTier = false;
            for (var el : conds) {
                JsonObject c = el.getAsJsonObject();
                String type = c.get("type").getAsString();
                if (type.equals("tcth:brewer_rewards_enabled")) {
                    hasRewardsEnabled = true;
                }
                if (type.equals("tcth:automated") && !c.get("value").getAsBoolean()) {
                    hasAutomatedFalse = true;
                }
                if (type.equals("tcth:beverage_tier")) {
                    hasTier = true;
                    tiers.add(c.get("tier").getAsString());
                }
            }
            assertTrue(hasRewardsEnabled, f + " must require tcth:brewer_rewards_enabled");
            assertTrue(hasAutomatedFalse, f + " must require automated=false");
            assertTrue(hasTier, f + " must have a tier condition");
        }
        assertEquals(Set.of("COMMON", "T2"), tiers, "COMMON and T2 must be mutually exclusive");
    }

    @Test
    void brewerJobKeepsFlags() throws Exception {
        JsonObject job = read(Path.of(
                "docs/presets/tcth-brewer/data/tcth/jobsplus/jobs/brewer.json"));
        assertEquals(100, job.get("max_level").getAsInt());
        assertFalse(job.get("is_default").getAsBoolean());
    }

    @Test
    void clientTranslationKeysPresent() throws Exception {
        String zh = Files.readString(
                Path.of("src/main/resources/assets/tcth/lang/zh_cn.json"), StandardCharsets.UTF_8);
        String en = Files.readString(
                Path.of("src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8);
        assertTrue(zh.contains("jobsplus.job.tcth.brewer.name"));
        assertTrue(zh.contains("jobsplus.job.tcth.brewer.description"));
        assertTrue(en.contains("jobsplus.job.tcth.brewer.name"));
        assertTrue(en.contains("jobsplus.job.tcth.brewer.description"));
    }
}
