package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 4A.2: farming mixins are registered in the right conditional configs
 * and the configs are gated by {@code requiredMods} in
 * {@code neoforge.mods.toml}, so optional mods' classes are never touched when
 * the mod is absent.
 */
class FarmingMixinConfigTest {

    private static final Gson GSON = new Gson();

    private static JsonObject mixins(String name) throws Exception {
        return GSON.fromJson(Files.readString(Path.of("src/main/resources/" + name), StandardCharsets.UTF_8),
                JsonObject.class);
    }

    private static String modsToml() throws Exception {
        return Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
    }

    @Test
    void vanillaSweetBerryMixinRegisteredInMainConfig() throws Exception {
        JsonObject main = mixins("tcth.mixins.json");
        assertTrue(main.getAsJsonArray("mixins").toString().contains("SweetBerryBushBlockMixin"),
                "vanilla sweet-berry mixin must be in the main (always-loaded) config");
    }

    @Test
    void fdTomatoMixinRegisteredAndGatedByRequiredMods() throws Exception {
        JsonObject fd = mixins("farmersdelight_compat.mixins.json");
        assertTrue(fd.getAsJsonArray("mixins").toString().contains("TomatoBlockMixin"));
        String toml = modsToml();
        int configIdx = toml.indexOf("farmersdelight_compat.mixins.json");
        assertTrue(configIdx >= 0, "FD mixin config must be declared");
        assertTrue(toml.substring(configIdx, toml.length()).contains("requiredMods=[\"farmersdelight\"]"),
                "FD config must be gated by requiredMods=[farmersdelight]");
    }

    @Test
    void kcCropMixinsRegisteredAndGatedByRequiredMods() throws Exception {
        JsonObject kc = mixins("kaleidoscope_cookery_compat.mixins.json");
        String mixins = kc.getAsJsonArray("mixins").toString();
        assertTrue(mixins.contains("KcBaseCropBlockMixin"), "base crop mixin (covers Rice) must be registered");
        assertTrue(mixins.contains("KcChiliCropBlockMixin"), "chili override mixin must be registered");
        String toml = modsToml();
        int configIdx = toml.indexOf("kaleidoscope_cookery_compat.mixins.json");
        assertTrue(configIdx >= 0, "KC mixin config must be declared");
        assertTrue(toml.substring(configIdx, toml.length()).contains("requiredMods=[\"kaleidoscope_cookery\"]"),
                "KC config must be gated by requiredMods=[kaleidoscope_cookery]");
    }

    @Test
    void releaseJarMustNotShipThirdPartyClasses() throws Exception {
        // 发布 JAR 检查在构建后由静态验证脚本执行；此处验证源码中不存在
        // 第三方类的拷贝（mixin 只引用类型，不内嵌类文件）。
        Path resources = Path.of("src/main/resources");
        try (var walk = Files.walk(resources)) {
            assertTrue(walk.filter(Files::isRegularFile)
                    .noneMatch(p -> p.toString().contains("daqem")
                            || p.toString().contains("vectorwing")
                            || p.toString().contains("ysbbbbbb")
                            || p.toString().contains("teamabnormals")
                            || p.toString().contains("soytutta")
                            || p.toString().contains("yirmiri")),
                    "main resources must not contain third-party classes");
        }
    }

    @Test
    void phase4a4NeapolitanAndMndMixinConfigsGated() throws Exception {
        JsonObject nea = mixins("neapolitan_farming_compat.mixins.json");
        assertTrue(nea.getAsJsonArray("mixins").toString().contains("StrawberryBushBlockMixin"));
        assertTrue(nea.getAsJsonArray("mixins").toString().contains("MintBlockMixin"));
        JsonObject mnd = mixins("mynethersdelight_farming_compat.mixins.json");
        assertTrue(mnd.getAsJsonArray("mixins").toString().contains("PowderyCaneBlockMixin"));
        assertTrue(mnd.getAsJsonArray("mixins").toString().contains("PowderyCannonBlockMixin"));
        JsonObject fd = mixins("farmersdelight_compat.mixins.json");
        assertTrue(fd.getAsJsonArray("mixins").toString().contains("MushroomColonyBlockMixin"));
        String toml = modsToml();
        assertTrue(toml.contains("requiredMods=[\"neapolitan\"]"));
        assertTrue(toml.contains("requiredMods=[\"mynethersdelight\"]"));
    }

    @Test
    void phase4a41OptionalCropVersionRangesAreBounded() throws Exception {
        String toml = modsToml();
        assertTrue(toml.contains("versionRange=\"[6.0.1,6.1.0)\""));
        assertTrue(toml.contains("versionRange=\"[1.5.0,1.6.0)\""));
        assertTrue(toml.contains("versionRange=\"[1.10.4,1.11.0)\""));
    }
}
