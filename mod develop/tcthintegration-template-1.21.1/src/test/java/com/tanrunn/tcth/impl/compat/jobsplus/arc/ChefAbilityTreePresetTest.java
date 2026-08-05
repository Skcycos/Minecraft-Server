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
 * Phase 3D: validates the four-route ability tree preset structure — 12 nodes,
 * required levels, parent chains, prices, per-route mutual exclusion and the
 * exact effect numbers in both the Arc actions and the translations.
 */
class ChefAbilityTreePresetTest {

    private static final String PRESET = "docs/presets/tcth-chef/data/tcth/";
    private static final Gson GSON = new Gson();

    /** Route -> {nodeI, nodeII, nodeIII}. */
    private static final Map<String, String[]> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("knife", new String[]{"knife_basic", "knife_adept", "knife_expert"});
        ROUTES.put("hearth", new String[]{"hearth_basic", "hearth_master", "hearth_expert"});
        ROUTES.put("tasting", new String[]{"tasting_basic", "tasting_nourishing", "tasting_feast"});
        ROUTES.put("culinary_experience", new String[]{"culinary_experience_i", "culinary_experience_ii", "culinary_experience_iii"});
    }

    /** required_level per route, in node order. */
    private static final Map<String, int[]> REQUIRED_LEVELS = new LinkedHashMap<>();

    static {
        REQUIRED_LEVELS.put("knife", new int[]{5, 20, 45});
        REQUIRED_LEVELS.put("hearth", new int[]{10, 30, 60});
        REQUIRED_LEVELS.put("tasting", new int[]{15, 35, 55});
        REQUIRED_LEVELS.put("culinary_experience", new int[]{25, 50, 75});
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private JsonObject preset(String relative) throws Exception {
        return GSON.fromJson(Files.readString(Path.of(PRESET, relative), StandardCharsets.UTF_8), JsonObject.class);
    }

    private static List<String> conditions(JsonObject action) {
        List<String> out = new ArrayList<>();
        JsonArray conditions = action.getAsJsonArray("conditions");
        if (conditions != null) {
            for (com.google.gson.JsonElement e : conditions) {
                out.add(e.getAsJsonObject().get("type").getAsString());
            }
        }
        return out;
    }

    private static List<String> excludedPowerups(JsonObject action) {
        List<String> out = new ArrayList<>();
        JsonArray conditions = action.getAsJsonArray("conditions");
        if (conditions != null) {
            for (com.google.gson.JsonElement e : conditions) {
                JsonObject c = e.getAsJsonObject();
                if ("jobsplus:powerup_not_active".equals(c.get("type").getAsString())) {
                    out.add(c.get("powerup").getAsString());
                }
            }
        }
        return out;
    }

    // ---- 树结构 ----

    @Test
    void fourRoutesWithThreeNodesEach() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/chef/" + node + ".json");
                assertNotNull(ui, "powerup " + node + " must exist");
                assertEquals("tcth:chef", ui.get("job").getAsString(), node);
                JsonObject arc = preset("arc/chef/powerup/" + node + ".json");
                assertNotNull(arc, "arc action " + node + " must exist");
                assertEquals("jobsplus:powerup", arc.getAsJsonObject("holder").get("type").getAsString(), node);
                assertEquals("tcth:chef/" + node, arc.getAsJsonObject("holder").get("id").getAsString(), node);
            }
        }
        // Exactly 12 powerup files.
        try (var stream = Files.list(Path.of(PRESET, "jobsplus/powerups/chef"))) {
            assertEquals(12, stream.filter(p -> p.toString().endsWith(".json")).count());
        }
    }

    @Test
    void requiredLevelsAreExact() throws Exception {
        for (Map.Entry<String, int[]> route : REQUIRED_LEVELS.entrySet()) {
            String[] nodes = ROUTES.get(route.getKey());
            int[] levels = route.getValue();
            for (int i = 0; i < 3; i++) {
                JsonObject ui = preset("jobsplus/powerups/chef/" + nodes[i] + ".json");
                assertEquals(levels[i], ui.get("required_level").getAsInt(),
                        route.getKey() + " node " + (i + 1) + " required_level");
            }
        }
    }

    @Test
    void parentChainsAreCorrect() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            String[] nodes = route.getValue();
            JsonObject first = preset("jobsplus/powerups/chef/" + nodes[0] + ".json");
            assertFalse(first.has("parent"), "first node of " + route.getKey() + " must have no parent");
            for (int i = 1; i < 3; i++) {
                JsonObject ui = preset("jobsplus/powerups/chef/" + nodes[i] + ".json");
                assertEquals("tcth:chef/" + nodes[i - 1], ui.get("parent").getAsString(),
                        route.getKey() + " node " + (i + 1) + " must parent " + nodes[i - 1]);
            }
        }
    }

    @Test
    void pricesArePositiveAndReasonable() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/chef/" + node + ".json");
                int price = ui.get("price").getAsInt();
                assertTrue(price >= 5 && price <= 20, "price " + price + " of " + node + " out of reasonable range");
            }
        }
    }

    @Test
    void uiIconsReferenceValidItems() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/chef/" + node + ".json");
                String icon = ui.getAsJsonObject("icon").get("id").getAsString();
                assertTrue(icon.contains(":"), "icon id must be namespaced: " + icon);
                assertFalse(icon.contains(" "), "icon id must not contain spaces: " + icon);
            }
        }
    }

    // ---- 互斥（高等级覆盖低等级） ----

    @Test
    void eachRouteIExcludesIIAndIII() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            String[] nodes = route.getValue();
            JsonObject i = preset("arc/chef/powerup/" + nodes[0] + ".json");
            List<String> excluded = excludedPowerups(i);
            assertTrue(excluded.contains("tcth:chef/" + nodes[1]), route.getKey() + " I must exclude II");
            assertTrue(excluded.contains("tcth:chef/" + nodes[2]), route.getKey() + " I must exclude III");
        }
    }

    @Test
    void eachRouteIIExcludesIIIOnly() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            String[] nodes = route.getValue();
            JsonObject ii = preset("arc/chef/powerup/" + nodes[1] + ".json");
            List<String> excluded = excludedPowerups(ii);
            assertEquals(List.of("tcth:chef/" + nodes[2]), excluded, route.getKey() + " II must exclude III only");
        }
    }

    @Test
    void eachRouteIIINeverExcludesAnything() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            String[] nodes = route.getValue();
            JsonObject iii = preset("arc/chef/powerup/" + nodes[2] + ".json");
            assertTrue(excludedPowerups(iii).isEmpty(), route.getKey() + " III must not exclude higher nodes");
        }
    }

    // ---- 研修 ----

    @Test
    void studyMultipliersAre125_150_200_AndNeverStack() throws Exception {
        String[] nodes = ROUTES.get("culinary_experience");
        double[] expected = {1.25, 1.5, 2.0};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/" + nodes[i] + ".json");
            assertEquals("jobsplus:on_job_exp", arc.get("type").getAsString());
            JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("jobsplus:job_exp_multiplier", reward.get("type").getAsString());
            assertEquals("tcth:chef", reward.get("job").getAsString());
            assertEquals(expected[i], reward.get("multiplier").getAsDouble(), 0.0001, nodes[i]);
            assertEquals(1, arc.getAsJsonArray("rewards").size(), nodes[i] + " must carry exactly one multiplier");
        }
        // Highest multiplier is exactly 2.0.
        assertEquals(2.0, preset("arc/chef/powerup/" + nodes[2] + ".json")
                .getAsJsonArray("rewards").get(0).getAsJsonObject().get("multiplier").getAsDouble(), 0.0001);
        // Master switch present on all three study actions.
        for (String node : nodes) {
            assertTrue(conditions(preset("arc/chef/powerup/" + node + ".json"))
                    .contains("tcth:chef_abilities_enabled"), node + " must be stoppable by the master switch");
        }
    }

    // ---- 刀工 ----

    @Test
    void knifeCancelsAt10_20_35WithKnifeTag() throws Exception {
        String[] nodes = ROUTES.get("knife");
        double[] chances = {10, 20, 35};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/" + nodes[i] + ".json");
            assertEquals("arc:on_hurt_item", arc.get("type").getAsString());
            JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("arc:cancel_action", reward.get("type").getAsString());
            assertEquals(chances[i], reward.get("chance").getAsDouble(), 0.0001, nodes[i]);
            assertTrue(arc.toString().contains("#c:tools/knife"), nodes[i] + " must target #c:tools/knife");
            assertTrue(conditions(arc).contains("tcth:knife_durability_enabled"), nodes[i]);
        }
    }

    // ---- 炉火 ----

    @Test
    void hearthMultipliersAre085_070_050WithFireTagCondition() throws Exception {
        String[] nodes = ROUTES.get("hearth");
        double[] multipliers = {0.85, 0.70, 0.50};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/" + nodes[i] + ".json");
            assertEquals("arc:on_get_hurt", arc.get("type").getAsString());
            JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("arc:damage_multiplier", reward.get("type").getAsString());
            assertEquals(multipliers[i], reward.get("multiplier").getAsDouble(), 0.0001, nodes[i]);
            List<String> conds = conditions(arc);
            assertTrue(conds.contains("tcth:fire_damage"), nodes[i] + " must check fire tag");
            assertTrue(conds.contains("tcth:fire_resistance_enabled"), nodes[i]);
            // Never fully immune: multiplier must stay > 0.
            assertTrue(reward.get("multiplier").getAsDouble() > 0, nodes[i]);
        }
    }

    // ---- 品鉴 ----

    @Test
    void tastingTargetsChefMealsWithCooldownAndSingleFullPackage() throws Exception {
        String[] nodes = ROUTES.get("tasting");
        int[] tiers = {1, 2, 3};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/" + nodes[i] + ".json");
            assertEquals("arc:on_eat", arc.get("type").getAsString());
            assertTrue(arc.toString().contains("#tcth:chef_meals"), nodes[i] + " must target #tcth:chef_meals");
            assertEquals(1, arc.getAsJsonArray("rewards").size(), nodes[i] + " must grant exactly one reward package");
            JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("tcth:tasting_effects", reward.get("type").getAsString());
            assertEquals(tiers[i], reward.get("tier").getAsInt(), nodes[i]);
            List<String> conds = conditions(arc);
            assertTrue(conds.contains("tcth:tasting_cooldown"), nodes[i] + " must carry the cooldown condition");
            assertTrue(conds.contains("tcth:tasting_effects_enabled"), nodes[i]);
        }
    }

    // ---- 基础奖励未变化 ----

    @Test
    void baseDishRewardsAreUnchanged() throws Exception {
        JsonObject common = preset("arc/chef/dish_cooked_common.json");
        JsonObject t2 = preset("arc/chef/dish_cooked_t2.json");
        JsonObject t3 = preset("arc/chef/dish_cooked_t3.json");
        assertEquals(2, common.getAsJsonArray("rewards").get(0).getAsJsonObject().get("max").getAsInt());
        assertEquals(5, t2.getAsJsonArray("rewards").get(0).getAsJsonObject().get("max").getAsInt());
        assertEquals(10, t3.getAsJsonArray("rewards").get(0).getAsJsonObject().get("max").getAsInt());
        JsonObject excellent = preset("arc/chef/dish_cooked_excellent.json");
        assertEquals(4, excellent.getAsJsonArray("rewards").get(0).getAsJsonObject().get("max").getAsInt());
        JsonObject taste = preset("arc/chef/taste_meal.json");
        JsonObject tasteReward = taste.getAsJsonArray("rewards").get(0).getAsJsonObject();
        assertEquals(1, tasteReward.get("min").getAsInt());
        assertEquals(1, tasteReward.get("max").getAsInt());
    }

    // ---- 翻译数值与 JSON 一致 ----

    @Test
    void translationsMatchExactEffectNumbers() throws Exception {
        JsonObject en = GSON.fromJson(Files.readString(Path.of(
                "src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject zh = GSON.fromJson(Files.readString(Path.of(
                "src/main/resources/assets/tcth/lang/zh_cn.json"), StandardCharsets.UTF_8), JsonObject.class);

        // 刀工：10/20/35 与 JSON chance 一致。
        String[] knifeNodes = ROUTES.get("knife");
        double[] knifeChances = {10, 20, 35};
        for (int i = 0; i < 3; i++) {
            String descEn = en.get("jobsplus.powerup.tcth.chef." + knifeNodes[i] + ".description").getAsString();
            String descZh = zh.get("jobsplus.powerup.tcth.chef." + knifeNodes[i] + ".description").getAsString();
            assertTrue(descEn.contains(String.valueOf((int) knifeChances[i])), knifeNodes[i] + " en desc must carry " + knifeChances[i]);
            assertTrue(descZh.contains(String.valueOf((int) knifeChances[i])), knifeNodes[i] + " zh desc must carry " + knifeChances[i]);
        }

        // 炉火：15/30/50 与 JSON 减伤一致。
        String[] hearthNodes = ROUTES.get("hearth");
        double[] reductions = {15, 30, 50};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/chef/powerup/" + hearthNodes[i] + ".json");
            double multiplier = arc.getAsJsonArray("rewards").get(0).getAsJsonObject().get("multiplier").getAsDouble();
            double percent = Math.round((1.0 - multiplier) * 100.0);
            assertEquals(reductions[i], percent, 0.0001, hearthNodes[i] + " reduction");
            String descZh = zh.get("jobsplus.powerup.tcth.chef." + hearthNodes[i] + ".description").getAsString();
            assertTrue(descZh.contains(String.valueOf((int) reductions[i]) + "%"), hearthNodes[i] + " zh desc must carry " + reductions[i] + "%");
        }

        // 品鉴：每个节点的效果包与 duration 一致。
        String[] tastingNodes = ROUTES.get("tasting");
        String[] expectedZh = {
                "生命恢复 I（5 秒）",
                "生命恢复 I（5 秒）与抗性提升 I（8 秒）",
                "生命恢复 I（5 秒）、抗性提升 I（8 秒）与速度 I（15 秒）"
        };
        for (int i = 0; i < 3; i++) {
            String descZh = zh.get("jobsplus.powerup.tcth.chef." + tastingNodes[i] + ".description").getAsString();
            assertTrue(descZh.contains(expectedZh[i]), tastingNodes[i] + " zh desc mismatch: " + descZh);
            assertTrue(descZh.contains("冷却 20 秒"), tastingNodes[i] + " must mention the 20 s cooldown");
        }
    }

    @Test
    void cooldownConfigValueMatchesPresetText() throws Exception {
        // 400 ticks = 20 s, shared by all tasting nodes (documented in README
        // and Config default; the in-memory cooldown is covered by
        // ChefTastingCooldownTest).
        assertTrue(Files.readString(Path.of("src/main/java/com/tanrunn/tcth/Config.java"), StandardCharsets.UTF_8)
                .contains("tastingEffectCooldownTicks\", 400, 1, 72000"));
    }
}
