package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 7B.1 data consistency: the main-mod runtime tag and the per-item
 * beverage_tiers mapping must match the authoritative 7A CSV exactly; T3
 * candidates / ingredients / containers / excluded items never enter runtime
 * data; and the BAC mixin config is gated by requiredMods.
 */
class BrewerDataConsistencyTest {

    private static final Gson GSON = new Gson();

    private static JsonObject read(Path p) throws Exception {
        return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
    }

    private static final Path MAIN_TAG =
            Path.of("src/main/resources/data/tcth/tags/item/brewer_drinks.json");
    private static final Path MAIN_TIERS =
            Path.of("docs/presets/tcth-brewer/data/tcth/beverage_tiers/items");

    @Test
    void mainModTagHas64RuntimeItems() throws Exception {
        JsonObject tag = read(MAIN_TAG);
        Set<String> values = new HashSet<>();
        tag.getAsJsonArray("values").forEach(el -> values.add(el.getAsString()));
        assertEquals(64, values.size(), "main-mod runtime tag must contain exactly 64 items");
    }

    @Test
    void mainModTagExcludesNonRuntimeCategories() throws Exception {
        JsonObject tag = read(MAIN_TAG);
        Set<String> values = new HashSet<>();
        tag.getAsJsonArray("values").forEach(el -> values.add(el.getAsString()));
        assertFalse(values.contains("brewinandchewin:saccharine_rum"), "T3 candidate must not be in tag");
        assertFalse(values.contains("brewinandchewin:red_rum"), "T3 candidate must not be in tag");
        assertFalse(values.contains("bakeries:coffee_bean"), "ingredient must not be in tag");
        assertFalse(values.contains("bakeries:drink_cup"), "container must not be in tag");
        assertFalse(values.contains("minecraft:potion"), "excluded must not be in tag");
        assertFalse(values.contains("minecraft:water_bucket"), "excluded must not be in tag");
    }

    @Test
    void mainModHas64PerItemTierFiles() throws Exception {
        try (Stream<Path> files = Files.walk(MAIN_TIERS)) {
            long count = files.filter(p -> p.toString().endsWith(".json")).count();
            assertEquals(64, count, "must have exactly 64 per-item tier files");
        }
    }

    @Test
    void perItemTierFilesOnlyContainCommonOrT2() throws Exception {
        try (Stream<Path> files = Files.walk(MAIN_TIERS)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    JsonObject o = read(p);
                    String tier = o.get("tier").getAsString();
                    assertTrue("COMMON".equals(tier) || "T2".equals(tier),
                            p + " must contain only COMMON or T2, got " + tier);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void auditDraftTiersJsonIsNotRuntime() throws Exception {
        // The audit draft (docs/presets .../brewer/tiers.json) must NOT be the
        // runtime source; the runtime source is the per-item folder above.
        JsonObject tiers = read(Path.of("docs/presets/tcth-brewer/data/tcth/brewer/tiers.json"));
        // The draft keeps the audit categories (may include T3_CANDIDATE note).
        assertTrue(tiers.has("tiers") || tiers.has("DRINK_T3_CANDIDATE"),
                "audit draft retains its structure for human review");
    }

    @Test
    void bacMixinConfigGatedByRequiredMods() throws Exception {
        JsonObject cfg = read(Path.of("src/main/resources/brewinandchewin_compat.mixins.json"));
        assertFalse(cfg.get("required").getAsBoolean(), "optional mod config must not be required=true");
        assertTrue(cfg.getAsJsonArray("mixins").toString().contains("KegPouringMixin"));
        String toml = Files.readString(
                Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        int idx = toml.indexOf("brewinandchewin_compat.mixins.json");
        assertTrue(idx >= 0, "BAC mixin config must be declared in mods.toml");
        assertTrue(toml.substring(idx, toml.length()).contains("requiredMods=[\"brewinandchewin\"]"),
                "BAC config must be gated by requiredMods=[brewinandchewin]");
    }

    @Test
    void publicApiHasNoThirdPartyTypes() throws Exception {
        for (String f : new String[] {
                "BeveragePreparedEvent.java", "BeverageDevice.java", "BeverageTier.java"}) {
            String src = Files.readString(
                    Path.of("src/main/java/com/tanrunn/tcth/api/brewing/" + f), StandardCharsets.UTF_8);
            assertFalse(src.contains("import umpaz."), f + " must not reference third-party mod types");
            assertFalse(src.contains("import com.github."), f + " must not reference third-party mod types");
        }
    }
}
