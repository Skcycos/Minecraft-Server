package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 6B: Dungeon's Delight monster-pot mixins are gated by requiredMods.
 */
class DungeonsDelightMixinConfigTest {

    private static final Gson GSON = new Gson();

    @Test
    void ddMixinConfigRegistersMonsterPotAndIsOptional() throws Exception {
        JsonObject cfg = GSON.fromJson(
                Files.readString(Path.of("src/main/resources/dungeonsdelight_compat.mixins.json"), StandardCharsets.UTF_8),
                JsonObject.class);
        assertFalse(cfg.get("required").getAsBoolean(), "optional mod config must not be required=true");
        String mixins = cfg.getAsJsonArray("mixins").toString();
        assertTrue(mixins.contains("MonsterPotResultSlotMixin"));
        assertTrue(mixins.contains("MonsterPotBlockEntityAccessor"));
    }

    @Test
    void ddMixinConfigGatedInModsToml() throws Exception {
        String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        int idx = toml.indexOf("dungeonsdelight_compat.mixins.json");
        assertTrue(idx >= 0, "DD mixin config must be declared");
        assertTrue(toml.substring(idx).contains("requiredMods=[\"dungeonsdelight\"]"),
                "DD config must be gated by requiredMods=[dungeonsdelight]");
    }

    @Test
    void mainResourcesDoNotEmbedYirmiriClasses() throws Exception {
        Path resources = Path.of("src/main/resources");
        try (var walk = Files.walk(resources)) {
            assertTrue(walk.filter(Files::isRegularFile)
                    .noneMatch(p -> p.toString().contains("yirmiri")
                            || p.toString().contains("dungeonsdelight/common")),
                    "main resources must not embed DD classes");
        }
    }
}
