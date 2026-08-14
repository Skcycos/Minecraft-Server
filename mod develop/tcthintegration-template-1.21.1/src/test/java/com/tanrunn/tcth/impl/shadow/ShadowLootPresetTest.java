package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;
import com.tanrunn.tcth.tools.ShadowLootPresetGenerator;
import com.tanrunn.tcth.tools.ShadowLootPresetGenerator.GeneratedFile;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * Preset/economic-audit gate tests for the first shadow-loot data preset
 * (8D.2 §7): deterministic generation, stale cleanup, strict schema, real
 * registries, hard-exclusion / L3 = 0, count = 1, and no shadow_loot JSON in
 * the main resources.
 */
class ShadowLootPresetTest {

    private static final Path CSV = Path.of("docs/影窃者生物掉落经济审计表.csv");
    private static final Path PRESET = Path.of("docs/presets/tcth-shadow-entity-loot");

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(data));
    }

    private static Map<Path, String> snapshot(Path dir) throws Exception {
        Map<Path, String> map = new HashMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                map.put(dir.relativize(p), sha256(Files.readAllBytes(p)));
            }
        }
        return map;
    }

    @Test
    void generatorIsDeterministic() throws Exception {
        Path a = Files.createTempDirectory("preset-a");
        Path b = Files.createTempDirectory("preset-b");
        ShadowLootPresetGenerator.generate(CSV, a);
        ShadowLootPresetGenerator.generate(CSV, b);
        assertEquals(snapshot(a), snapshot(b), "two runs must produce byte-identical presets");
    }

    @Test
    void generatorCleansStaleFilesOnlyInsideShadowLoot() throws Exception {
        // 8D.2.1 §4: cleanup is confined to data/tcth/shadow_loot/ — a stale
        // root file is NOT managed and must be left alone.
        Path dir = Files.createTempDirectory("preset-stale");
        Files.writeString(dir.resolve("stale.json"), "{\"stale\": true}");
        Files.createDirectories(dir.resolve("data/tcth/shadow_loot/minecraft"));
        Files.writeString(dir.resolve("data/tcth/shadow_loot/minecraft/removed.json"),
                "{\"pools\": []}");
        ShadowLootPresetGenerator.generate(CSV, dir);
        assertTrue(Files.exists(dir.resolve("stale.json")),
                "files outside data/tcth/shadow_loot/ must NOT be touched");
        assertFalse(Files.exists(dir.resolve("data/tcth/shadow_loot/minecraft/removed.json")),
                "stale files inside shadow_loot must be cleaned");
    }

    @Test
    void checkedInPresetMatchesTheGeneratorOutput() throws Exception {
        Path generated = Files.createTempDirectory("preset-check");
        ShadowLootPresetGenerator.generate(CSV, generated);
        assertEquals(snapshot(PRESET), snapshot(generated),
                "the checked-in preset must be exactly the generator output");
    }

    @Test
    void allPresetFilesPassTheProductionSchema() throws Exception {
        assertTrue(Files.isDirectory(PRESET));
        try (var walk = Files.walk(PRESET)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.endsWith("pack.mcmeta")).toList()) {
                Path rel = PRESET.relativize(p);
                // data/tcth/shadow_loot/<ns>/<entity>.json
                String entity = rel.subpath(4, 5).toString().replace(".json", "");
                String namespace = rel.subpath(3, 4).toString();
                JsonObject json = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                ShadowLootDefinition definition = ShadowLootDefinition.parse(
                        ResourceLocation.fromNamespaceAndPath(namespace, entity), json);
                assertNotNull(definition, "preset file must pass the production schema: " + p);
                assertEquals(1, definition.pools().size());
                assertEquals(1, definition.pools().get(0).entries().size());
            }
        }
    }

    @Test
    void presetEntitiesAndItemsExistInTheVanillaBootstrapRegistries() throws Exception {
        // 8D.2.1 §5: BuiltInRegistries proves existence in the vanilla
        // bootstrap registries only — NOT the full server mod registry.
        Registry<EntityType<?>> entities = BuiltInRegistries.ENTITY_TYPE;
        Registry<Item> items = BuiltInRegistries.ITEM;
        try (var walk = Files.walk(PRESET)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.endsWith("pack.mcmeta")).toList()) {
                Path rel = PRESET.relativize(p);
                ResourceLocation entity = ResourceLocation.fromNamespaceAndPath(
                        rel.subpath(3, 4).toString(), rel.subpath(4, 5).toString().replace(".json", ""));
                assertTrue(entities.containsKey(entity),
                        "entity not in the vanilla bootstrap registry: " + entity
                                + " (note: the first preset is all-minecraft namespaced)");
                JsonObject json = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                JsonObject entry = json.getAsJsonArray("pools").get(0).getAsJsonObject()
                        .getAsJsonArray("entries").get(0).getAsJsonObject();
                ResourceLocation item = ResourceLocation.parse(entry.get("id").getAsString());
                assertTrue(items.containsKey(item), "unknown item in preset: " + item);
                assertFalse(item.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "air")));
            }
        }
    }

    @Test
    void noHardExcludedOrL3EntitiesInThePreset() throws Exception {
        List<ResourceLocation> banned = new java.util.ArrayList<>(ShadowLootLoader.HARD_EXCLUDED);
        banned.addAll(List.of(
                ResourceLocation.fromNamespaceAndPath("minecraft", "zombie"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "skeleton"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "spider"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "creeper"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "enderman")));
        try (var walk = Files.walk(PRESET)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.endsWith("pack.mcmeta")).toList()) {
                Path rel = PRESET.relativize(p);
                ResourceLocation entity = ResourceLocation.fromNamespaceAndPath(
                        rel.subpath(3, 4).toString(), rel.subpath(4, 5).toString().replace(".json", ""));
                assertFalse(banned.contains(entity),
                        "hard-excluded or L3 entities must never appear: " + entity);
            }
        }
    }

    @Test
    void everyPresetEntryHasCountOne() throws Exception {
        try (var walk = Files.walk(PRESET)) {
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.endsWith("pack.mcmeta")).toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                JsonObject entry = json.getAsJsonArray("pools").get(0).getAsJsonObject()
                        .getAsJsonArray("entries").get(0).getAsJsonObject();
                assertEquals(1, entry.get("min_count").getAsInt());
                assertEquals(1, entry.get("max_count").getAsInt());
            }
        }
    }


    // ---- 8D.2.1: pack_format, fail-fast, cleanup boundary ----

    @Test
    void packFormatIsExactly48() throws Exception {
        // 8D.2.1 §1: the checked-in preset AND the generator output must both
        // declare pack_format 48 — not merely be mutually consistent.
        JsonObject checkedIn = JsonParser.parseString(
                Files.readString(PRESET.resolve("pack.mcmeta"))).getAsJsonObject();
        assertEquals(48, checkedIn.getAsJsonObject("pack").get("pack_format").getAsInt(),
                "the checked-in pack.mcmeta must be pack_format 48");
        Path generated = Files.createTempDirectory("preset-format");
        ShadowLootPresetGenerator.generate(CSV, generated);
        JsonObject generatedMeta = JsonParser.parseString(
                Files.readString(generated.resolve("pack.mcmeta"))).getAsJsonObject();
        assertEquals(48, generatedMeta.getAsJsonObject("pack").get("pack_format").getAsInt(),
                "the generator must emit pack_format 48");
    }

    @Test
    void generatorRejectsMalformedApprovedRows() throws Exception {
        Path bad = Files.createTempDirectory("bad-csv");
        Path csv = bad.resolve("audit.csv");
        List<String> lines = Files.readAllLines(CSV);
        List<String> malformed = new java.util.ArrayList<>(lines);
        malformed.add("minecraft:cow,minecraft:leather,2,evidence,no,renewable,low,low,APPROVED,");
        Files.writeString(csv, String.join("\n", malformed));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv,
                        Files.createTempDirectory("out")),
                "an APPROVED row with count != 1 must fail fast, never be skipped");
    }

    @Test
    void generatorRejectsDuplicateEntityIds() throws Exception {
        Path bad = Files.createTempDirectory("dup-csv");
        Path csv = bad.resolve("audit.csv");
        List<String> lines = Files.readAllLines(CSV);
        List<String> duplicated = new java.util.ArrayList<>(lines);
        duplicated.add("minecraft:chicken,minecraft:egg,1,evidence,no,renewable,low,low,APPROVED,");
        Files.writeString(csv, String.join("\n", duplicated));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv,
                        Files.createTempDirectory("out")),
                "duplicate entityIds must fail fast");
    }

    @Test
    void generatorRejectsPathTraversal() throws Exception {
        Path bad = Files.createTempDirectory("traversal-csv");
        Path csv = bad.resolve("audit.csv");
        List<String> lines = Files.readAllLines(CSV);
        List<String> evil = new java.util.ArrayList<>(lines);
        evil.add("minecraft:..\\..\\evil,minecraft:egg,1,evidence,no,renewable,low,low,APPROVED,");
        Files.writeString(csv, String.join("\n", evil));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv,
                        Files.createTempDirectory("out")),
                "path traversal ids must fail fast");
    }

    @Test
    void generatorPreservesNonShadowLootFiles() throws Exception {
        // 8D.2.1 §4: READMEs, other data trees (e.g. a future
        // data/tcth/jobsplus/) and pack.mcmeta rewrites must survive;
        // ONLY stale files inside data/tcth/shadow_loot/ are removed.
        Path dir = Files.createTempDirectory("preset-boundary");
        ShadowLootPresetGenerator.generate(CSV, dir);
        Files.writeString(dir.resolve("README.md"), "# preset readme");
        Files.createDirectories(dir.resolve("data/tcth/jobsplus"));
        Files.writeString(dir.resolve("data/tcth/jobsplus/powerup.json"), "{\"keep\": true}");
        Files.writeString(dir.resolve("data/tcth/shadow_loot/minecraft/stale.json"), "{\"pools\": []}");
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"old\": true}");

        ShadowLootPresetGenerator.generate(CSV, dir);

        assertTrue(Files.exists(dir.resolve("README.md")), "README must be preserved");
        assertTrue(Files.exists(dir.resolve("data/tcth/jobsplus/powerup.json")),
                "non-shadow_loot data trees must be preserved");
        assertFalse(Files.exists(dir.resolve("data/tcth/shadow_loot/minecraft/stale.json")),
                "stale files inside shadow_loot must be removed");
        assertTrue(Files.readString(dir.resolve("pack.mcmeta")).contains("\"pack_format\": 48"),
                "pack.mcmeta must be rewritten with pack_format 48");
    }



    // ---- 8D.2.2: strict decisions, whole-table uniqueness, CSV quotes ----

    private static void assertCsvRejected(String extraRow) throws Exception {
        Path dir = Files.createTempDirectory("8d22");
        Path csv = dir.resolve("audit.csv");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(CSV));
        lines.add(extraRow);
        Files.writeString(csv, String.join("\n", lines));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv, dir.resolve("out")),
                "the malformed CSV must fail fast: " + extraRow);
    }

    @Test
    void generatorRejectsInvalidDecisionValues() throws Exception {
        assertCsvRejected(
                "minecraft:zombie,minecraft:rotten_flesh,1,evidence,no,renewable,low,low,approved,");
        assertCsvRejected(
                "minecraft:zombie,minecraft:rotten_flesh,1,evidence,no,renewable,low,low,MAYBE,");
        assertCsvRejected(
                "minecraft:zombie,minecraft:rotten_flesh,1,evidence,no,renewable,low,low, APPROVED ,");
    }

    @Test
    void generatorRejectsRejectedAndApprovedForTheSameEntity() throws Exception {
        // REJECTED and APPROVED rows for the same entityId must fail — the
        // whole-table uniqueness spans decisions (8D.2.2 §2).
        Path dir = Files.createTempDirectory("8d22");
        Path csv = dir.resolve("audit.csv");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(CSV));
        // chicken is already APPROVED; add a REJECTED row for it.
        lines.add("minecraft:chicken,,,evidence,no,renewable,low,low,REJECTED,duplicate");
        Files.writeString(csv, String.join("\n", lines));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv, dir.resolve("out")),
                "a REJECTED row for an already-APPROVED entity must fail");
    }

    @Test
    void generatorRejectsInvalidResourceLocations() throws Exception {
        assertCsvRejected(
                "minecraft:..\\evil,minecraft:egg,1,evidence,no,renewable,low,low,APPROVED,");
        assertCsvRejected(
                "not-a-resource-location,minecraft:egg,1,evidence,no,renewable,low,low,APPROVED,");
        assertCsvRejected(
                "minecraft:Sheep,minecraft:egg,1,evidence,no,renewable,low,low,APPROVED,");
        assertCsvRejected(
                "minecraft:cow,minecraft:..\\evil,1,evidence,no,renewable,low,low,APPROVED,");
    }

    @Test
    void generatorRejectsMalformedCsvQuotes() throws Exception {
        Path dir = Files.createTempDirectory("8d22");
        Path csv = dir.resolve("audit.csv");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(CSV));
        // unclosed quote in the evidence column
        lines.add("minecraft:cow,minecraft:leather,1,\"unclosed,evidence,no,renewable,low,low,APPROVED,");
        Files.writeString(csv, String.join("\n", lines));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowLootPresetGenerator.generate(csv, dir.resolve("out")),
                "an unclosed quote must fail fast");
    }

    @Test
    void rejectedRowsMayLeaveItemAndCountEmpty() throws Exception {
        // A REJECTED row with empty candidate item/count is legal.
        Path dir = Files.createTempDirectory("8d22");
        Path csv = dir.resolve("audit.csv");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(CSV));
        lines.add("minecraft:mooshroom,,,evidence,no,renewable,low,low,REJECTED,no candidate");
        Files.writeString(csv, String.join("\n", lines));
        ShadowLootPresetGenerator.generate(csv, dir.resolve("out"));
    }



    // ---- 8D.2.3: AFTER_QUOTE state ----

    @Test
    void csvParserRejectsCharactersAfterClosingQuote() throws Exception {
        // "value"x — characters right after the closing quote.
        assertCsvRejected(
                "minecraft:mooshroom,,,evidence,no,renewable,low,low,REJECTED,\"x\"y".replace(
                        "REJECTED,\"x\"y", "REJECTED,\"x\"y"));
        // Use a deliberate trailing-char payload inside a cell.
        assertCsvRejected(
                "minecraft:mooshroom,,,\"evidence\"extra,no,renewable,low,low,REJECTED,");
        // "value" + space after the closing quote.
        assertCsvRejected(
                "minecraft:mooshroom,,,\"evidence\" ,no,renewable,low,low,REJECTED,");
        // a second quote right after the closing quote.
        assertCsvRejected(
                "minecraft:mooshroom,,,\"evidence\"\"extra,no,renewable,low,low,REJECTED,");
    }

    @Test
    void csvParserAcceptsStandardQuoteEscapes() throws Exception {
        // A field containing a literal quote via the "" escape, and a quoted
        // field ending cleanly at the comma / end of line, must both parse.
        Path dir = Files.createTempDirectory("8d23-ok");
        Path csv = dir.resolve("audit.csv");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(CSV));
        // The whole field is quoted; the inner "" is the standard escape for
        // a literal quote.
        lines.add("minecraft:mooshroom,,,\"evidence with \"\"quote\"\" inside\",no,renewable,low,low,REJECTED,no candidate");
        Files.writeString(csv, String.join("\n", lines));
        ShadowLootPresetGenerator.generate(csv, dir.resolve("out"));
    }


    @Test
    void mainResourcesContainNoShadowLootJson() throws Exception {
        Path resources = Path.of("src/main/resources");
        if (Files.isDirectory(resources)) {
            try (var walk = Files.walk(resources)) {
                long lootFiles = walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().contains("shadow_loot")).count();
                assertEquals(0, lootFiles, "no shadow_loot JSON may ship in the main resources");
            }
        }
    }
}
