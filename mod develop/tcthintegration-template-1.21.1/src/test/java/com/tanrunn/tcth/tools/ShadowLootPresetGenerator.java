package com.tanrunn.tcth.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * Deterministic generator for the first shadow-loot data preset (8D.2 §7,
 * hardened 8D.2.1 / finalized 8D.2.2).
 *
 * <p>Fail-fast (8D.2.2):
 * <ul>
 *   <li>decisions are strictly {@code APPROVED} / {@code REJECTED} — any
 *       other value, case variant or stray content throws;</li>
 *   <li>every NON-EMPTY row must be fully legal: exactly 10 columns, a
 *       present and valid entityId; blank lines are ignored;</li>
 *   <li>entityId uniqueness spans the WHOLE table — a REJECTED and an
 *       APPROVED row for the same entity also fail; REJECTED rows may leave
 *       the candidate item / count empty, but a filled item must be a valid
 *       ResourceLocation;</li>
 *   <li>ResourceLocations are validated with the authoritative
 *       {@link ResourceLocation#tryParse}, must be explicitly
 *       {@code namespace:path}, strictly lowercase, and must not contain
 *       {@code ..} or absolute paths; legal nested paths ({@code /}) are
 *       supported safely (no escape out of the managed root).</li>
 * </ul>
 *
 * <p>CSV parsing supports the standard {@code ""} quote escape; an unclosed
 * quote, a quote in an illegal position or a wrong column count fails fast.
 */
public final class ShadowLootPresetGenerator {

    /** Data-pack format for Minecraft 1.21.1. */
    public static final int PACK_FORMAT = 48;

    private static final int COL_ENTITY = 0;
    private static final int COL_ITEM = 1;
    private static final int COL_COUNT = 2;
    private static final int COL_DECISION = 8;
    private static final int EXPECTED_COLUMNS = 10;

    private static final String EXPECTED_HEADER =
            "entityId,候选物品,数量,来源证据,是否死亡掉落,可再生性,刷取风险,经济影响,最终决定,排除原因";

    private ShadowLootPresetGenerator() {
    }

    public record GeneratedFile(Path relativePath, String content) {
    }

    /**
     * @param csvPath   the audited CSV (strictly validated)
     * @param outputDir the preset output directory
     * @return the generated files (relative path + content)
     */
    public static List<GeneratedFile> generate(Path csvPath, Path outputDir) throws IOException {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).trim().equals(EXPECTED_HEADER)) {
            throw new IllegalArgumentException("CSV header mismatch: " + csvPath);
        }

        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile(Path.of("pack.mcmeta"), packMcmeta()));
        int approvedCount = 0;
        Set<String> allEntities = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue; // blank lines are ignored
            }
            int row = i + 1;
            List<String> columns = parseCsvLine(line, row);
            if (columns.size() != EXPECTED_COLUMNS) {
                throw new IllegalArgumentException(
                        "CSV row " + row + " has " + columns.size() + " columns, expected "
                                + EXPECTED_COLUMNS);
            }
            String entity = columns.get(COL_ENTITY).trim();
            if (entity.isEmpty()) {
                throw new IllegalArgumentException("CSV row " + row + " has an empty entityId");
            }
            ResourceLocation entityRl = validateResourceLocation(entity, "entity", row);
            if (!allEntities.add(entity)) {
                throw new IllegalArgumentException(
                        "duplicate entityId across the whole table (row " + row + "): " + entity);
            }
            // 8D.2.2 §1: the decision is NOT trimmed — stray whitespace or
            // any other content fails fast.
            String decision = columns.get(COL_DECISION);
            if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
                throw new IllegalArgumentException(
                        "invalid decision at row " + row + ": '" + decision
                                + "' — only APPROVED/REJECTED are allowed");
            }
            String item = columns.get(COL_ITEM).trim();
            String countText = columns.get(COL_COUNT).trim();
            if ("APPROVED".equals(decision)) {
                if (item.isEmpty() || countText.isEmpty()) {
                    throw new IllegalArgumentException(
                            "APPROVED row " + row + " is missing the candidate item or count");
                }
                if (!"1".equals(countText)) {
                    throw new IllegalArgumentException(
                            "APPROVED row " + row + " has count " + countText
                                    + " — the first preset is fixed at count=1");
                }
                validateResourceLocation(item, "item", row);
                files.add(new GeneratedFile(Path.of("data", "tcth", "shadow_loot",
                        entityRl.getNamespace(), entityRl.getPath() + ".json"),
                        lootJson(entity, item)));
                approvedCount++;
            } else {
                // REJECTED: candidate item / count may be empty; a filled
                // item must still be a valid ResourceLocation.
                if (!item.isEmpty()) {
                    validateResourceLocation(item, "item", row);
                }
            }
        }
        int jsonFiles = (int) files.stream()
                .filter(f -> f.relativePath().toString().endsWith(".json")).count();
        if (jsonFiles != approvedCount) {
            throw new IllegalStateException(
                    "generated " + jsonFiles + " JSON files for " + approvedCount
                            + " APPROVED rows");
        }

        // Write the managed files deterministically.
        Files.createDirectories(outputDir);
        for (GeneratedFile file : files) {
            Path target = outputDir.resolve(file.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
        // Stale cleanup: ONLY inside data/tcth/shadow_loot/ (8D.2.1 §4).
        Path shadowLoot = outputDir.resolve("data/tcth/shadow_loot");
        if (Files.isDirectory(shadowLoot)) {
            Set<Path> managed = new HashSet<>();
            for (GeneratedFile file : files) {
                if (file.relativePath().toString().startsWith("data/tcth/shadow_loot")) {
                    managed.add(outputDir.resolve(file.relativePath()).toAbsolutePath().normalize());
                }
            }
            try (var walk = Files.walk(shadowLoot)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    if (!managed.contains(p.toAbsolutePath().normalize())) {
                        Files.delete(p);
                    }
                }
            }
        }
        return files;
    }

    /**
     * Standard CSV line parsing with {@code ""} quote escapes and an explicit
     * AFTER_QUOTE state (8D.2.3 §1): once a quoted field closes, only a
     * comma or the end of the line is legal — {@code "value"x},
     * {@code "value" } and a second quote right after closing are all
     * rejected. An unclosed quote or a quote in an illegal position fails
     * fast too.
     */
    private static List<String> parseCsvLine(String line, int row) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int state = 0; // 0 = normal, 1 = inside quotes, 2 = after closing quote
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            switch (state) {
                case 0 -> {
                    if (c == '"') {
                        if (current.length() > 0) {
                            throw new IllegalArgumentException(
                                    "quote in an illegal position at row " + row);
                        }
                        state = 1;
                    } else if (c == ',') {
                        columns.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                case 1 -> {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            current.append('"'); // standard "" escape
                            i++;
                        } else {
                            state = 2;
                        }
                    } else {
                        current.append(c);
                    }
                }
                case 2 -> {
                    // AFTER_QUOTE: only a comma or end of line is allowed.
                    if (c == ',') {
                        columns.add(current.toString());
                        current.setLength(0);
                        state = 0;
                    } else {
                        throw new IllegalArgumentException(
                                "character after a closing quote at row " + row
                                        + " (only a comma or end of line is legal)");
                    }
                }
                default -> throw new IllegalStateException("unreachable");
            }
        }
        if (state == 1) {
            throw new IllegalArgumentException("unclosed quote at row " + row);
        }
        columns.add(current.toString());
        return columns;
    }

    /** Authoritative ResourceLocation syntax + safety checks (8D.2.2 §3). */
    private static ResourceLocation validateResourceLocation(String value, String kind, int row) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(kind + " id is empty at row " + row);
        }
        if (!value.contains(":")) {
            throw new IllegalArgumentException(
                    "explicit namespace:path required for " + kind + " at row " + row + ": " + value);
        }
        if (value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException(
                    "path traversal in " + kind + " id at row " + row + ": " + value);
        }
        boolean lower = value.chars().noneMatch(Character::isUpperCase);
        if (!lower) {
            throw new IllegalArgumentException(
                    kind + " id must be strictly lowercase at row " + row + ": " + value);
        }
        ResourceLocation rl = ResourceLocation.tryParse(value);
        if (rl == null) {
            throw new IllegalArgumentException(
                    "invalid " + kind + " id at row " + row + ": " + value);
        }
        return rl;
    }

    private static String packMcmeta() {
        return """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "8D.2.2 first shadow-loot preset (APPROVED low-risk entities only)"
                  }
                }
                """.formatted(PACK_FORMAT);
    }

    /** Strict schema: one pool, one entry, fixed count 1 (8D.2 §6). */
    private static String lootJson(String entity, String item) {
        return """
                {
                  "pools": [
                    { "weight": 100, "entries": [
                      { "id": "%s", "weight": 100, "min_count": 1, "max_count": 1 }
                    ] }
                  ]
                }
                """.formatted(item);
    }
}
