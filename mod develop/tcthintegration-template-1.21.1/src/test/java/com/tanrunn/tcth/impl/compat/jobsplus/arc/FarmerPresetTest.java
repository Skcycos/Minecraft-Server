package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Phase 4A: validates the {@code tcth:farmer} preset in
 * {@code docs/presets/tcth-farmer/} — job definition, base XP actions,
 * mature-only harvest, no plant XP, no coin, no Bountiful changes, full
 * zh/en translations, no leftover {@code jobsplus:farmer} references, chef
 * preset untouched, and the server-side default-jobs switch.
 *
 * <p>边界声明：这些断言只证明预设 JSON 与服务器配置的静态事实。Arc 原生
 * {@code on_harvest_crop} 仅对 {@code Block instanceof CropBlock} 的方块调用，
 * {@code arc:crop_fully_grown} 条件的存在不等同于“全部作物成熟检测成功”；
 * FakePlayer（{@code ServerPlayer} 子类）是否被 Arc 排除、模组特殊作物是否
 * 覆盖，均不在本类断言范围内（见 docs/phase-4a.1-farmer-audit.md）。
 */
class FarmerPresetTest {

    private static final String PRESET = "docs/presets/tcth-farmer/data/tcth/";
    private static final String CHEF_PRESET = "docs/presets/tcth-chef/data/tcth/";
    private static final String LANG = "src/main/resources/assets/tcth/lang/";
    private static final Gson GSON = new Gson();

    private static JsonObject readJson(String relative) throws Exception {
        return GSON.fromJson(Files.readString(Path.of(relative), StandardCharsets.UTF_8), JsonObject.class);
    }

    private static JsonObject preset(String relative) throws Exception {
        return readJson(PRESET + relative);
    }

    private static String raw(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    private static List<JsonObject> farmerActions() throws Exception {
        List<JsonObject> actions = new ArrayList<>();
        try (var stream = Files.list(Path.of(PRESET, "arc/farmer"))) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                actions.add(GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class));
            }
        }
        return actions;
    }

    // ---- 职业定义 ----

    @Test
    void farmerJobDefinitionExistsAndIsNotDefault() throws Exception {
        JsonObject job = preset("jobsplus/jobs/farmer.json");
        assertNotNull(job, "tcth:farmer job definition must exist");
        assertFalse(job.get("is_default").getAsBoolean(), "tcth:farmer must not be a default job");
        assertEquals(100, job.get("max_level").getAsInt());
        assertTrue(job.has("price"));
        assertTrue(job.has("color"));
        assertTrue(job.has("icon"), "farmer job must define a themed icon");
        assertTrue(job.has("background"), "farmer job must define a themed background");
        // Jobs+ Serializer does not read inline name/description; translations live in the mod lang files.
        assertNull(job.get("name"), "job JSON must not embed inline name");
        assertNull(job.get("description"), "job JSON must not embed inline description");
    }

    @Test
    void noSecondJobsPlusFarmerDefinition() throws Exception {
        for (String presetRoot : new String[]{PRESET, CHEF_PRESET}) {
            Path data = Path.of(presetRoot);
            if (!Files.isDirectory(data)) {
                continue;
            }
            try (var walk = Files.walk(data)) {
                var offenders = walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().contains(Path.of("data", "jobsplus").toString()))
                        .toList();
                assertTrue(offenders.isEmpty(),
                        "no preset may ship a second jobsplus-namespaced definition: " + offenders);
            }
        }
    }

    @Test
    void presetShallNotContainJobsPlusFarmerHolderOrPowerupReferences() throws Exception {
        try (var walk = Files.walk(Path.of(PRESET))) {
            var files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList();
            assertFalse(files.isEmpty());
            for (Path f : files) {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertFalse(content.contains("jobsplus:farmer"),
                        "preset must not reference jobsplus:farmer: " + f);
                assertFalse(content.contains("jobsplus:farmer/"),
                        "preset must not reference jobsplus:farmer/* powerups: " + f);
            }
        }
    }

    // ---- 基础经验 Action ----

    @Test
    void allNewActionHoldersAreTcthFarmer() throws Exception {
        List<JsonObject> actions = farmerActions();
        assertEquals(4, actions.size(), "phase 4A ships exactly 4 base XP actions");
        for (JsonObject action : actions) {
            assertEquals("jobsplus:job", action.getAsJsonObject("holder").get("type").getAsString());
            assertEquals("tcth:farmer", action.getAsJsonObject("holder").get("id").getAsString(),
                    "every new action holder must be tcth:farmer");
            assertNull(action.get("name"), "Arc action must not embed name");
            assertNull(action.get("description"), "Arc action must not embed description");
        }
    }

    @Test
    void harvestUsesUnifiedActionAndNoArcHarvestRemains() throws Exception {
        // 阶段 4A.2：删除 arc:on_harvest_crop，改由 tcth:on_crop_harvested 统一结算。
        assertFalse(Files.exists(Path.of(PRESET, "arc/farmer/harvest_crop.json")),
                "arc:on_harvest_crop reward action must be removed");
        JsonObject harvest = preset("arc/farmer/crop_harvested.json");
        assertEquals("tcth:on_crop_harvested", harvest.get("type").getAsString(),
                "harvest must use the unified tcth:on_crop_harvested action");
        // 不得同时保留 arc:on_harvest_crop 与 tcth:on_crop_harvested（否则双倍经验）。
        try (var walk = Files.walk(Path.of(PRESET))) {
            for (var f : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                assertFalse(Files.readString(f).contains("arc:on_harvest_crop"),
                        "preset must not reference arc:on_harvest_crop: " + f);
            }
        }
        // 条件：automated=false（自动化/假人收割不发经验）。
        JsonArray conditions = harvest.getAsJsonArray("conditions");
        assertNotNull(conditions, "crop_harvested must carry an automation condition");
        boolean automatedFilter = false;
        for (com.google.gson.JsonElement e : conditions) {
            JsonObject cond = e.getAsJsonObject();
            if ("tcth:automated".equals(cond.get("type").getAsString())
                    && !cond.get("value").getAsBoolean()) {
                automatedFilter = true;
            }
        }
        assertTrue(automatedFilter, "crop_harvested must require tcth:automated=false");
    }

    @Test
    void farmingBlockTagsArePresent() throws Exception {
        for (String tag : new String[]{"farmer_harvestables",
                "farmer_vertical_crops", "farmer_excluded"}) {
            assertTrue(Files.exists(Path.of(PRESET, "tags/block/" + tag + ".json")),
                    "block tag tcth:" + tag + " must exist in the preset");
        }
    }

    @Test
    void plantingGrantsNoExperience() throws Exception {
        assertFalse(Files.exists(Path.of(PRESET, "arc/farmer/plant_crop.json")),
                "planting must not have a reward action (0 XP)");
        for (JsonObject action : farmerActions()) {
            assertFalse("arc:on_plant_crop".equals(action.get("type").getAsString()),
                    "no action may grant XP for planting");
        }
    }

    @Test
    void baseXpValuesAreExact() throws Exception {
        assertEquals(1, preset("arc/farmer/crop_harvested.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("min").getAsInt());
        assertEquals(2, preset("arc/farmer/crop_harvested.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("max").getAsInt());

        assertEquals(3, preset("arc/farmer/breed_animal.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("min").getAsInt());
        assertEquals(5, preset("arc/farmer/breed_animal.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("max").getAsInt());

        assertEquals(8, preset("arc/farmer/tame_animal.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("min").getAsInt());
        assertEquals(12, preset("arc/farmer/tame_animal.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("max").getAsInt());

        assertEquals(1, preset("arc/farmer/shear_sheep.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("min").getAsInt());
        assertEquals(2, preset("arc/farmer/shear_sheep.json").getAsJsonArray("rewards")
                .get(0).getAsJsonObject().get("max").getAsInt());
    }

    @Test
    void noCoinRewardsAndNoDropMultiplier() throws Exception {
        for (JsonObject action : farmerActions()) {
            JsonArray rewards = action.getAsJsonArray("rewards");
            assertNotNull(rewards);
            for (com.google.gson.JsonElement e : rewards) {
                String type = e.getAsJsonObject().get("type").getAsString();
                assertEquals("jobsplus:job_exp", type,
                        "all farmer rewards must be XP only (no coins, no drop multipliers)");
            }
        }
    }

    @Test
    void shearSheepRequiresSheepFullyShearableAndShears() throws Exception {
        JsonObject shear = preset("arc/farmer/shear_sheep.json");
        assertEquals("arc:on_interact_entity", shear.get("type").getAsString());
        String conditions = shear.getAsJsonArray("conditions").toString();
        assertTrue(conditions.contains("minecraft:sheep"), "shear must target sheep only");
        assertTrue(conditions.contains("arc:ready_for_shearing"),
                "shear must exclude non-shearable sheep (failed shears grant nothing)");
        assertTrue(conditions.contains("minecraft:shears"), "shear must require shears in hand");
    }

    @Test
    void noBountifulModification() throws Exception {
        try (var walk = Files.walk(Path.of(PRESET))) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                assertFalse(f.toString().contains("bountiful"),
                        "preset must not ship Bountiful data: " + f);
                assertFalse(Files.readString(f, StandardCharsets.UTF_8).toLowerCase().contains("bountiful"),
                        "preset must not reference Bountiful: " + f);
            }
        }
    }

    // ---- 翻译（模组语言文件，非预设 data） ----

    @Test
    void farmerTranslationsCompleteInBothLanguages() throws Exception {
        JsonObject en = readJson(LANG + "en_us.json");
        JsonObject zh = readJson(LANG + "zh_cn.json");
        for (JsonObject lang : new JsonObject[]{en, zh}) {
            assertTrue(lang.has("jobsplus.job.tcth.farmer.name"));
            assertTrue(lang.has("jobsplus.job.tcth.farmer.description"));
            assertFalse(lang.get("jobsplus.job.tcth.farmer.name").getAsString().isBlank());
            assertFalse(lang.get("jobsplus.job.tcth.farmer.description").getAsString().isBlank());
        }
        assertEquals("Farmer", en.get("jobsplus.job.tcth.farmer.name").getAsString());
        assertEquals("农夫", zh.get("jobsplus.job.tcth.farmer.name").getAsString());
    }

    @Test
    void presetHasNoLangDirectory() {
        assertFalse(Files.exists(Path.of(PRESET, "lang")),
                "preset data must not ship client language resources");
    }

    // ---- tcth:chef 不受影响 ----

    @Test
    void chefPresetUnchangedAndStillEnabled() throws Exception {
        JsonObject chef = readJson(CHEF_PRESET + "jobsplus/jobs/chef.json");
        assertNotNull(chef, "tcth:chef job definition must still exist");
        assertFalse(chef.get("is_default").getAsBoolean());
        assertEquals(100, chef.get("max_level").getAsInt());
        // 部署侧（测试服数据包目录 tcth-chef 存在且未被停用）见 FarmerServerDeploymentTest；
        // 本类只做纯源码/预设断言，不依赖 ../../Server/。
    }

    // ---- 发布 JAR ----

    @Test
    void releasedJarDoesNotShipPresetData() throws Exception {
        Path resources = Path.of("src/main/resources");
        for (String presetNamespaceDir : new String[]{"jobsplus", "arc", "dish_tiers", "fieldguide"}) {
            assertFalse(Files.exists(resources.resolve("data/tcth/" + presetNamespaceDir)),
                    "TCTH main JAR must not ship preset data under src/main/resources/data/tcth/"
                            + presetNamespaceDir);
        }
        // 构建产物（若存在）也不得包含预设数据。
        Path jar = Path.of("build/libs/tcth-0.2.0.jar");
        if (Files.exists(jar)) {
            Process p = new ProcessBuilder("unzip", "-l", jar.toString()).redirectErrorStream(true).start();
            String listing = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(listing.contains("docs/presets"), "release JAR must not embed docs/presets");
            assertFalse(listing.contains("data/tcth/jobsplus"), "release JAR must not embed preset jobs data");
            assertFalse(listing.contains("data/tcth/arc"), "release JAR must not embed preset arc actions");
            assertFalse(listing.contains("data/tcth/dish_tiers"), "release JAR must not embed preset dish tiers");
        }
    }
}
