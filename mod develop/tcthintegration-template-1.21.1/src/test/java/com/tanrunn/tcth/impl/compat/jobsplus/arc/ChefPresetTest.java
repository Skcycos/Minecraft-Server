package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.ICondition;
import com.daqem.arc.api.player.ArcPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.AutomatedCondition;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.DishActionDispatcher;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.AutomatedCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.CookingDeviceCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishQualityCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishTierCondition;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Validates the tcth:chef preset in {@code docs/presets/tcth-chef/}: powerup
 * Arc actions with matching multipliers, mutually-exclusive tier rewards,
 * quality bonus, automation exclusion and translation keys.
 */
class ChefPresetTest {

    private static final String PRESET = "docs/presets/tcth-chef/data/tcth/";
    private static final Gson GSON = new Gson();

    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private JsonObject preset(String relative) throws Exception {
        return GSON.fromJson(Files.readString(Path.of(PRESET, relative), StandardCharsets.UTF_8), JsonObject.class);
    }

    private JsonObject rewardFile(String name) throws Exception {
        return preset("arc/chef/" + name);
    }

    private ActionData dishData(DishTier tier, DishQuality quality, boolean automated) {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), automated ? null : player, null,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, quality, automated, level, null);
        return DishActionDispatcher.buildActionData(Mockito.mock(ArcPlayer.class), e, tier);
    }

    /** Evaluates the conditions of a preset reward file against event data. */
    private boolean hits(JsonObject rewardFile, ActionData data) throws Exception {
        JsonArray conditions = rewardFile.getAsJsonArray("conditions");
        for (com.google.gson.JsonElement element : conditions) {
            JsonObject cond = element.getAsJsonObject();
            String type = cond.get("type").getAsString();
            boolean inverted = cond.has("inverted") && cond.get("inverted").getAsBoolean();
            ICondition condition = switch (type) {
                case "tcth:dish_tier" -> new DishTierCondition.Serializer().fromJson(
                        ResourceLocation.parse(type), cond, inverted);
                case "tcth:dish_quality" -> new DishQualityCondition.Serializer().fromJson(
                        ResourceLocation.parse(type), cond, inverted);
                case "tcth:cooking_device" -> new CookingDeviceCondition.Serializer().fromJson(
                        ResourceLocation.parse(type), cond, inverted);
                case "tcth:automated" -> new AutomatedCondition.Serializer().fromJson(
                        ResourceLocation.parse(type), cond, inverted);
                default -> throw new IllegalArgumentException("unexpected condition " + type);
            };
            if (!condition.isMet(data)) {
                return false;
            }
        }
        return true;
    }

    // ---- powerups ----

    @Test
    void powerupIHasMultiplier125AndExcludesHigherTiers() throws Exception {
        JsonObject json = preset("arc/chef/powerup/culinary_experience_i.json");
        assertEquals("jobsplus:powerup", json.getAsJsonObject("holder").get("type").getAsString());
        assertEquals("tcth:chef/culinary_experience_i",
                json.getAsJsonObject("holder").get("id").getAsString());
        assertEquals("jobsplus:on_job_exp", json.get("type").getAsString());
        JsonObject reward = json.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals("jobsplus:job_exp_multiplier", reward.get("type").getAsString());
        assertEquals("tcth:chef", reward.get("job").getAsString());
        assertEquals(1.25, reward.get("multiplier").getAsDouble(), 0.0001);

        JsonArray conditions = json.getAsJsonArray("conditions");
        assertEquals(3, conditions.size(), "I must carry the master switch plus exclude II and III");
        assertTrue(conditions.toString().contains("tcth:chef_abilities_enabled"));
        assertTrue(conditions.toString().contains("culinary_experience_ii"));
        assertTrue(conditions.toString().contains("culinary_experience_iii"));
    }

    @Test
    void powerupIIHasMultiplier15AndExcludesIII() throws Exception {
        JsonObject json = preset("arc/chef/powerup/culinary_experience_ii.json");
        JsonObject reward = json.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals(1.5, reward.get("multiplier").getAsDouble(), 0.0001);
        assertEquals(2, json.getAsJsonArray("conditions").size(), "II must carry the master switch plus exclude III only");
        assertTrue(json.getAsJsonArray("conditions").toString().contains("culinary_experience_iii"));
    }

    @Test
    void powerupIIIHasMultiplier20WithOnlyMasterSwitch() throws Exception {
        JsonObject json = preset("arc/chef/powerup/culinary_experience_iii.json");
        JsonObject reward = json.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals(2.0, reward.get("multiplier").getAsDouble(), 0.0001);
        JsonArray conditions = json.getAsJsonArray("conditions");
        assertEquals(1, conditions.size(), "III must carry only the master-switch condition");
        assertTrue(conditions.toString().contains("tcth:chef_abilities_enabled"),
                "III must be stoppable by the master switch");
        assertFalse(conditions.toString().contains("powerup_not_active"),
                "III must not exclude any higher tier");
    }

    @Test
    void powerupMultpliersMatchDescriptions() throws Exception {
        double[] multipliers = {1.25, 1.5, 2.0};
        JsonObject en = GSON.fromJson(Files.readString(Path.of(
                "src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8), JsonObject.class);
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/culinary_experience_" + new String[]{"i", "ii", "iii"}[i] + ".json");
            double m = arc.getAsJsonArray("rewards").get(0).getAsJsonObject().get("multiplier").getAsDouble();
            assertEquals(multipliers[i], m, 0.0001);
            String description = en.get("jobsplus.powerup.tcth.chef.culinary_experience_"
                    + new String[]{"i", "ii", "iii"}[i] + ".description").getAsString();
            boolean matches = description.contains(String.valueOf(m))
                    || (m == Math.floor(m) && description.contains(String.valueOf((int) m)));
            assertTrue(matches, "translation description must match the multiplier: " + description);
        }
    }

    // ---- reward combination rules ----

    @Test
    void baseTierRewardsAreMutuallyExclusive() throws Exception {
        JsonObject common = rewardFile("dish_cooked_common.json");
        JsonObject t2 = rewardFile("dish_cooked_t2.json");
        JsonObject t3 = rewardFile("dish_cooked_t3.json");

        assertTrue(hits(common, dishData(DishTier.COMMON, DishQuality.UNKNOWN, false)));
        assertFalse(hits(t2, dishData(DishTier.COMMON, DishQuality.UNKNOWN, false)));
        assertFalse(hits(t3, dishData(DishTier.COMMON, DishQuality.UNKNOWN, false)));

        assertFalse(hits(common, dishData(DishTier.T2, DishQuality.UNKNOWN, false)));
        assertTrue(hits(t2, dishData(DishTier.T2, DishQuality.UNKNOWN, false)));
        assertFalse(hits(t3, dishData(DishTier.T2, DishQuality.UNKNOWN, false)));

        assertFalse(hits(common, dishData(DishTier.T3, DishQuality.UNKNOWN, false)));
        assertFalse(hits(t2, dishData(DishTier.T3, DishQuality.UNKNOWN, false)));
        assertTrue(hits(t3, dishData(DishTier.T3, DishQuality.UNKNOWN, false)));
    }

    @Test
    void t3SuperbHitsT3AndQualityBonusExactlyOnce() throws Exception {
        JsonObject t3 = rewardFile("dish_cooked_t3.json");
        JsonObject excellent = rewardFile("dish_cooked_excellent.json");
        ActionData data = dishData(DishTier.T3, DishQuality.SUPERB, false);

        assertTrue(hits(t3, data));
        assertTrue(hits(excellent, data), "SUPERB must also hit the quality bonus");

        // No other base tier matches.
        assertFalse(hits(rewardFile("dish_cooked_common.json"), data));
        assertFalse(hits(rewardFile("dish_cooked_t2.json"), data));
    }

    @Test
    void automatedNeverMatchesAnyReward() throws Exception {
        ActionData automated = dishData(DishTier.T3, DishQuality.SUPERB, true);
        assertFalse(hits(rewardFile("dish_cooked_common.json"), automated));
        assertFalse(hits(rewardFile("dish_cooked_t2.json"), automated));
        assertFalse(hits(rewardFile("dish_cooked_t3.json"), automated));
        assertFalse(hits(rewardFile("dish_cooked_excellent.json"), automated),
                "automated production must grant nothing");
    }

    @Test
    void noStandaloneManualRewardRemains() {
        assertFalse(Files.exists(Path.of(PRESET, "arc/chef/dish_cooked_manual.json")),
                "the standalone manual reward must be removed");
    }

    @Test
    void tasteMealIsMigratedToTcthChef() throws Exception {
        JsonObject taste = preset("arc/chef/taste_meal.json");
        assertEquals("jobsplus:job", taste.getAsJsonObject("holder").get("type").getAsString());
        assertEquals("tcth:chef", taste.getAsJsonObject("holder").get("id").getAsString());
        assertEquals("arc:on_eat", taste.get("type").getAsString(), "taste_meal keeps arc:on_eat");

        JsonObject reward = taste.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals("jobsplus:job_exp", reward.get("type").getAsString());
        assertEquals(1, reward.get("min").getAsInt());
        assertEquals(1, reward.get("max").getAsInt(), "taste_meal grants exactly 1 XP");

        JsonObject condition = taste.getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertEquals("arc:items", condition.get("type").getAsString());
        assertEquals("#tcth:chef_meals",
                condition.getAsJsonArray("items").get(0).getAsString(),
                "condition must reference the migrated #tcth:chef_meals tag");
    }

    @Test
    void presetHasNoLegacyCookOrPrepareActions() throws Exception {
        Path arcChef = Path.of(PRESET, "arc/chef");
        try (var stream = java.nio.file.Files.list(arcChef)) {
            var files = stream.map(p -> p.getFileName().toString()).toList();
            assertFalse(files.contains("cook_food.json"), "cook_food is replaced by tcth:on_dish_cooked");
            assertFalse(files.contains("prepare_meal.json"), "prepare_meal is replaced by tcth:on_dish_cooked");
            assertFalse(files.contains("prepare_snack.json"), "prepare_snack is replaced by tcth:on_dish_cooked");
        }
        // No arc:on_smelt_item / arc:on_craft_item action remains in the preset.
        try (var stream = java.nio.file.Files.list(arcChef)) {
            var offenders = stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class)
                                    .get("type").getAsString().matches("arc:on_(smelt_item|craft_item)");
                        } catch (Exception e) {
                            return false;
                        }
                    }).toList();
            assertTrue(offenders.isEmpty(), "legacy cook/prepare actions must not be migrated: " + offenders);
        }
    }

    @Test
    void chefMealsTagExistsInPreset() throws Exception {
        JsonObject tag = preset("tags/item/chef_meals.json");
        assertTrue(tag.has("values"));
        assertTrue(tag.getAsJsonArray("values").size() > 0);
    }

    // ---- translation keys (provided by the TCTH mod, not the preset) ----

    private static final String[] NODE_IDS = {
            "knife_basic", "knife_adept", "knife_expert",
            "hearth_basic", "hearth_master", "hearth_expert",
            "tasting_basic", "tasting_nourishing", "tasting_feast",
            "culinary_experience_i", "culinary_experience_ii", "culinary_experience_iii"
    };

    private JsonObject mainLang(String file) throws Exception {
        return GSON.fromJson(Files.readString(Path.of("src/main/resources/assets/tcth/lang/" + file),
                StandardCharsets.UTF_8), JsonObject.class);
    }

    @Test
    void mainModLangContainsAllPowerupKeysInBothLanguages() throws Exception {
        JsonObject en = mainLang("en_us.json");
        JsonObject zh = mainLang("zh_cn.json");
        for (String id : NODE_IDS) {
            assertTrue(en.has("jobsplus.powerup.tcth.chef." + id + ".name"), "en_us missing name " + id);
            assertTrue(en.has("jobsplus.powerup.tcth.chef." + id + ".description"), "en_us missing description " + id);
            assertTrue(zh.has("jobsplus.powerup.tcth.chef." + id + ".name"), "zh_cn missing name " + id);
            assertTrue(zh.has("jobsplus.powerup.tcth.chef." + id + ".description"), "zh_cn missing description " + id);
        }
        assertTrue(en.has("jobsplus.job.tcth.chef.name"));
        assertTrue(en.has("jobsplus.job.tcth.chef.description"));
        assertTrue(zh.has("jobsplus.job.tcth.chef.name"));
        assertTrue(zh.has("jobsplus.job.tcth.chef.description"));
    }

    @Test
    void presetHasNoLangDirectory() {
        assertFalse(Files.exists(Path.of(PRESET, "lang")),
                "preset data must not ship client language resources");
    }

    @Test
    void presetJobAndPowerupsHaveNoInlineNames() throws Exception {
        assertNull(preset("jobsplus/jobs/chef.json").get("name"));
        assertNull(preset("jobsplus/jobs/chef.json").get("description"));
        for (String id : NODE_IDS) {
            JsonObject ui = preset("jobsplus/powerups/chef/" + id + ".json");
            assertNull(ui.get("name"), "UI powerup must not embed name: " + id);
            assertNull(ui.get("description"), "UI powerup must not embed description: " + id);
            if (id.startsWith("knife_")) {
                continue; // knife route is Java-driven since 4C: no arc file
            }
            JsonObject arc = preset("arc/chef/powerup/" + id + ".json");
            assertNull(arc.get("name"));
            assertNull(arc.get("description"));
        }
    }

    @Test
    void zhDescriptionsReflectExactMultipliers() throws Exception {
        JsonObject zh = mainLang("zh_cn.json");
        String i = zh.get("jobsplus.powerup.tcth.chef.culinary_experience_i.description").getAsString();
        String ii = zh.get("jobsplus.powerup.tcth.chef.culinary_experience_ii.description").getAsString();
        String iii = zh.get("jobsplus.powerup.tcth.chef.culinary_experience_iii.description").getAsString();
        assertTrue(i.contains("1.25 倍"), "I must read 1.25x, got: " + i);
        assertTrue(!i.contains("2 倍"), "I must not be written as 2x");
        assertTrue(ii.contains("1.5 倍"), "II must read 1.5x, got: " + ii);
        assertTrue(!ii.contains("2 倍"), "II must not be written as 2x");
        assertTrue(iii.contains("2 倍"), "III must read 2x, got: " + iii);
        assertTrue(!iii.contains("2.5"), "III must not read 2.5x");
    }

    @Test
    void chefJobUsesLangKeysNotInlineName() throws Exception {
        // Translation keys live in the mod's lang files (covered above); the
        // preset must carry no inline names.
        assertNull(preset("jobsplus/jobs/chef.json").get("name"));
        assertNull(preset("jobsplus/jobs/chef.json").get("description"));
    }
}
