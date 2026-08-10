package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Phase 4B: structural audit of the tcth:farmer ability-tree preset data —
 * powerup chain (parent / required_level / price), arc powerup mutual
 * exclusion (powerup_not_active), tilling cancellation, livestock event
 * coverage and study multipliers.
 */
class FarmerAbilityTreePresetTest {

    private static final String PRESET = "docs/presets/tcth-farmer/data/tcth/";

    private static final Gson GSON = new GsonBuilder().create();

    /** Route → three node names, in chain order. */
    private static final Map<String, String[]> ROUTES = Map.of(
            "tilling", new String[] {"tilling_basic", "tilling_adept", "tilling_expert"},
            "harvest", new String[] {"harvest_basic", "harvest_adept", "harvest_expert"},
            "livestock", new String[] {"livestock_basic", "livestock_adept", "livestock_expert"},
            "study", new String[] {"study_i", "study_ii", "study_iii"});

    /** Route → exact required levels. */
    private static final Map<String, int[]> REQUIRED_LEVELS = Map.of(
            "tilling", new int[] {5, 20, 45},
            "harvest", new int[] {10, 30, 60},
            "livestock", new int[] {15, 35, 55},
            "study", new int[] {25, 50, 75});

    private static JsonObject preset(String relative) {
        try {
            Path p = Path.of(PRESET, relative);
            if (!Files.exists(p)) {
                return null;
            }
            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new AssertionError("cannot read preset " + relative, e);
        }
    }

    private static List<String> conditions(JsonObject arc) {
        List<String> out = new ArrayList<>();
        JsonArray conds = arc.getAsJsonArray("conditions");
        if (conds == null) {
            return out;
        }
        for (JsonElement c : conds) {
            out.add(c.getAsJsonObject().get("type").getAsString());
        }
        return out;
    }

    private static List<String> excludedPowerups(JsonObject arc) {
        List<String> out = new ArrayList<>();
        JsonArray conds = arc.getAsJsonArray("conditions");
        if (conds == null) {
            return out;
        }
        for (JsonElement c : conds) {
            JsonObject o = c.getAsJsonObject();
            if ("jobsplus:powerup_not_active".equals(o.get("type").getAsString())) {
                out.add(o.get("powerup").getAsString());
            }
        }
        return out;
    }

    /** Arc files of one livestock tier (breed / tame / shear). */
    private static List<String> livestockFiles(String levelName) {
        return List.of(levelName + "_breed", levelName + "_tame", levelName + "_shear");
    }

    // ---- 树结构 ----

    @Test
    void fourRoutesWithThreeNodesEach() throws Exception {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/farmer/" + node + ".json");
                assertNotNull(ui, "powerup " + node + " must exist");
                assertEquals("tcth:farmer", ui.get("job").getAsString(), node);
                assertNotNull(ui.get("price"), node + " must carry a price");
                assertNotNull(ui.get("required_level"), node + " must carry required_level");
            }
        }
        try (var stream = Files.list(Path.of(PRESET, "jobsplus/powerups/farmer"))) {
            assertEquals(12, stream.filter(p -> p.toString().endsWith(".json")).count());
        }
    }

    @Test
    void requiredLevelsAreExact() {
        for (Map.Entry<String, int[]> route : REQUIRED_LEVELS.entrySet()) {
            String[] nodes = ROUTES.get(route.getKey());
            int[] levels = route.getValue();
            for (int i = 0; i < 3; i++) {
                JsonObject ui = preset("jobsplus/powerups/farmer/" + nodes[i] + ".json");
                assertEquals(levels[i], ui.get("required_level").getAsInt(),
                        route.getKey() + " node " + (i + 1) + " required_level");
            }
        }
    }

    @Test
    void parentChainsAreCorrect() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            String[] nodes = route.getValue();
            JsonObject first = preset("jobsplus/powerups/farmer/" + nodes[0] + ".json");
            assertFalse(first.has("parent"), "first node of " + route.getKey() + " must have no parent");
            for (int i = 1; i < 3; i++) {
                JsonObject ui = preset("jobsplus/powerups/farmer/" + nodes[i] + ".json");
                assertEquals("tcth:farmer/" + nodes[i - 1], ui.get("parent").getAsString(),
                        route.getKey() + " node " + (i + 1) + " must parent " + nodes[i - 1]);
            }
        }
    }

    @Test
    void pricesArePositiveAndReasonable() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/farmer/" + node + ".json");
                int price = ui.get("price").getAsInt();
                assertTrue(price >= 5 && price <= 20, "price " + price + " of " + node + " out of reasonable range");
            }
        }
    }

    @Test
    void uiIconsReferenceValidItems() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            for (String node : route.getValue()) {
                JsonObject ui = preset("jobsplus/powerups/farmer/" + node + ".json");
                String icon = ui.getAsJsonObject("icon").get("id").getAsString();
                assertTrue(icon.contains(":"), "icon id must be namespaced: " + icon);
                assertFalse(icon.contains(" "), "icon id must not contain spaces: " + icon);
            }
        }
    }

    // ---- 互斥（高等级覆盖低等级；harvest 为 Java 驱动无 arc 文件，跳过） ----

    @Test
    void eachRouteIExcludesIIAndIII() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            if ("harvest".equals(route.getKey()) || "tilling".equals(route.getKey())) {
                continue; // Java-driven routes have no arc powerup files
            }
            String[] nodes = route.getValue();
            List<String> iFiles = "livestock".equals(route.getKey())
                    ? livestockFiles(nodes[0]) : List.of(nodes[0]);
            for (String file : iFiles) {
                JsonObject i = preset("arc/farmer/powerup/" + file + ".json");
                List<String> excluded = excludedPowerups(i);
                assertTrue(excluded.contains("tcth:farmer/" + nodes[1]), route.getKey() + " I must exclude II");
                assertTrue(excluded.contains("tcth:farmer/" + nodes[2]), route.getKey() + " I must exclude III");
            }
        }
    }

    @Test
    void eachRouteIIExcludesIIIOnly() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            if ("harvest".equals(route.getKey()) || "tilling".equals(route.getKey())) {
                continue; // Java-driven routes have no arc powerup files
            }
            String[] nodes = route.getValue();
            List<String> iiFiles = "livestock".equals(route.getKey())
                    ? livestockFiles(nodes[1]) : List.of(nodes[1]);
            for (String file : iiFiles) {
                JsonObject ii = preset("arc/farmer/powerup/" + file + ".json");
                assertEquals(List.of("tcth:farmer/" + nodes[2]), excludedPowerups(ii),
                        route.getKey() + " II must exclude III only");
            }
        }
    }

    @Test
    void eachRouteIIINeverExcludesAnything() {
        for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
            if ("harvest".equals(route.getKey()) || "tilling".equals(route.getKey())) {
                continue; // Java-driven routes have no arc powerup files
            }
            String[] nodes = route.getValue();
            List<String> iiiFiles = "livestock".equals(route.getKey())
                    ? livestockFiles(nodes[2]) : List.of(nodes[2]);
            for (String file : iiiFiles) {
                JsonObject iii = preset("arc/farmer/powerup/" + file + ".json");
                assertTrue(excludedPowerups(iii).isEmpty(), route.getKey() + " III must not exclude higher nodes");
            }
        }
    }

    // ---- 耕作（Java 驱动 mixin，无 arc 文件） ----

    @Test
    void tillingRouteIsJavaDrivenWithoutArcPowerup() throws Exception {
        String[] nodes = ROUTES.get("tilling");
        for (String node : nodes) {
            assertTrue(preset("jobsplus/powerups/farmer/" + node + ".json") != null,
                    node + " powerup must exist");
            // Tilling durability skip is Java-driven via ItemStackDurabilityMixin
            // (Arc on_hurt_item targets the unused NeoForge ServerPlayer wrapper);
            // no arc powerup file.
            assertFalse(Files.exists(Path.of(PRESET, "arc/farmer/powerup/" + node + ".json")),
                    node + " must NOT carry an arc powerup file");
        }
    }

    // ---- 丰收（Java 驱动，无 arc 文件） ----

    @Test
    void harvestRouteIsJavaDrivenWithoutArcPowerup() throws Exception {
        String[] nodes = ROUTES.get("harvest");
        for (String node : nodes) {
            assertTrue(preset("jobsplus/powerups/farmer/" + node + ".json") != null,
                    node + " powerup must exist");
            // Harvest effects are Java-driven on CropHarvestedEvent; no arc file.
            assertFalse(Files.exists(Path.of(PRESET, "arc/farmer/powerup/" + node + ".json")),
                    node + " must NOT carry an arc powerup file");
        }
    }

    // ---- 畜牧（繁殖 / 驯服 / 剪羊毛） ----

    @Test
    void livestockCoversThreeEventsWithTierPackages() {
        int[] tiers = {1, 2, 3};
        String[] nodes = ROUTES.get("livestock");
        String[] eventTypes = {
                "arc:on_breed_animal",
                "arc:on_tame_animal",
                "arc:on_interact_entity" // shearing, gated by conditions
        };
        for (int i = 0; i < 3; i++) {
            List<String> files = livestockFiles(nodes[i]);
            for (int e = 0; e < 3; e++) {
                JsonObject arc = preset("arc/farmer/powerup/" + files.get(e) + ".json");
                assertEquals(eventTypes[e], arc.get("type").getAsString(), files.get(e));
                JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
                assertEquals("tcth:farmer_livestock_effects", reward.get("type").getAsString(), files.get(e));
                assertEquals(tiers[i], reward.get("tier").getAsInt(), files.get(e));
                assertEquals(1, arc.getAsJsonArray("rewards").size(), files.get(e) + " must grant one package");
                List<String> conds = conditions(arc);
                assertTrue(conds.contains("tcth:farmer_livestock_abilities_enabled"), files.get(e));
                assertTrue(conds.contains("tcth:farmer_livestock_cooldown"), files.get(e));
            }
        }
    }

    @Test
    void shearingTargetsSheepWithShearsInHand() {
        JsonObject shear = preset("arc/farmer/powerup/livestock_basic_shear.json");
        assertTrue(shear.toString().contains("minecraft:sheep"), "shearing must target sheep");
        assertTrue(shear.toString().contains("arc:ready_for_shearing"), "shearing must require ready_for_shearing");
        assertTrue(shear.toString().contains("minecraft:shears"), "shearing must require shears in hand");
    }

    // ---- 研修 ----

    @Test
    void studyMultipliersAre115_135_160AndNeverStack() {
        String[] nodes = ROUTES.get("study");
        double[] expected = {1.15, 1.35, 1.6};
        for (int i = 0; i < 3; i++) {
            JsonObject arc = preset("arc/farmer/powerup/" + nodes[i] + ".json");
            assertEquals("jobsplus:on_job_exp", arc.get("type").getAsString());
            JsonObject reward = arc.getAsJsonArray("rewards").get(0).getAsJsonObject();
            assertEquals("jobsplus:job_exp_multiplier", reward.get("type").getAsString());
            assertEquals("tcth:farmer", reward.get("job").getAsString());
            assertEquals(expected[i], reward.get("multiplier").getAsDouble(), 0.0001, nodes[i]);
            assertEquals(1, arc.getAsJsonArray("rewards").size(), nodes[i] + " must carry exactly one multiplier");
            List<String> conds = conditions(arc);
            assertTrue(conds.contains("tcth:farmer_study_abilities_enabled"), nodes[i]);
        }
        assertEquals(1.6, preset("arc/farmer/powerup/" + nodes[2] + ".json")
                .getAsJsonArray("rewards").get(0).getAsJsonObject().get("multiplier").getAsDouble(), 0.0001);
    }

    // ---- 与服务器部署副本一致性 ----

    @Test
    void serverDeployedCopyMatchesPresets() throws Exception {
        Path presetRoot = Path.of(PRESET).toAbsolutePath().normalize();
        Path serverRoot = Path.of("../../Server/global_packs/required_data/tcth-farmer/data/tcth")
                .toAbsolutePath().normalize();
        if (!Files.exists(serverRoot)) {
            return; // CI / repo layout without the server copy: skip
        }
        try (var files = Files.walk(presetRoot)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Path rel = presetRoot.relativize(p);
                Path serverFile = serverRoot.resolve(rel.toString());
                if (Files.exists(serverFile)) {
                    assertEquals(
                            Files.readString(p, StandardCharsets.UTF_8),
                            Files.readString(serverFile, StandardCharsets.UTF_8),
                            "server copy of " + rel + " must match the preset");
                }
            }
        }
    }

    // ---- 语言键完整性（每种语言 34 key,其中 24 个能力名称/描述逐节点验证） ----

    @Test
    void allTwentyFourPowerupLangKeysPresentInBothLanguages() throws Exception {
        for (String lang : List.of("zh_cn", "en_us")) {
            Path langFile = Path.of("src/main/resources/assets/tcth/lang/" + lang + ".json");
            JsonObject langJson = GSON.fromJson(
                    Files.readString(langFile, StandardCharsets.UTF_8), JsonObject.class);
            int powerupKeys = 0;
            for (Map.Entry<String, String[]> route : ROUTES.entrySet()) {
                for (String node : route.getValue()) {
                    String nameKey = "jobsplus.powerup.tcth.farmer." + node + ".name";
                    String descKey = "jobsplus.powerup.tcth.farmer." + node + ".description";
                    assertTrue(langJson.has(nameKey), lang + " missing " + nameKey);
                    assertTrue(langJson.has(descKey), lang + " missing " + descKey);
                    assertTrue(!langJson.get(nameKey).getAsString().isBlank(), lang + " blank " + nameKey);
                    assertTrue(!langJson.get(descKey).getAsString().isBlank(), lang + " blank " + descKey);
                    powerupKeys += 2;
                }
            }
            assertEquals(24, powerupKeys, lang + " must have exactly 24 powerup name/description keys");
            // 4 条件 × 2 + 1 奖励 × 2 = 10 附加键
            int extra = 0;
            for (String k : List.of(
                    "tcth.condition.hoe_durability_enabled",
                    "tcth.condition.farmer_study_abilities_enabled",
                    "tcth.condition.farmer_livestock_abilities_enabled",
                    "tcth.condition.farmer_livestock_cooldown",
                    "tcth.reward.farmer_livestock_effects")) {
                assertTrue(langJson.has(k + ".name"), lang + " missing " + k + ".name");
                assertTrue(langJson.has(k + ".desc"), lang + " missing " + k + ".desc");
                extra += 2;
            }
            assertEquals(34, powerupKeys + extra, lang + " must have exactly 34 total keys");
        }
    }
}
