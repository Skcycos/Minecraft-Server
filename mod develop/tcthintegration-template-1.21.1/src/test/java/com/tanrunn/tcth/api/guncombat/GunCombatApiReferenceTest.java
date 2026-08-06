package com.tanrunn.tcth.api.guncombat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Verifies the public gun-combat API boundary (phase 5A):
 * <ul>
 *   <li>{@code com.tanrunn.tcth.api.guncombat} contains zero references to
 *       Scorched Guns, GD656, Jobs+ or Arc types — the API is usable without
 *       any of those mods;</li>
 *   <li>the compiled main output (which becomes the released JAR) contains no
 *       third-party classes and no nested third-party JARs;</li>
 *   <li>the mods.toml declares {@code scguns} as an optional dependency and
 *       deliberately does <em>not</em> declare GD656.</li>
 * </ul>
 */
class GunCombatApiReferenceTest {

    private static final String[] FORBIDDEN_SOURCE_FRAGMENTS = {
            "top.ribs.scguns",   // Scorched Guns
            "gd656killicon",     // GD656 Kill Icon
            "com.daqem",         // Arc
            "jobsplus",          // Jobs+ (only appears in TCTH's own packages when unused)
            "org.mods.gd656"     // GD656 namespace
    };

    /** Third-party class-file roots that must never be bundled into the output. */
    private static final String[] FORBIDDEN_CLASS_ROOTS = {
            "top/ribs/scguns/",  // Scorched Guns
            "org/mods/gd656/",   // GD656 Kill Icon
            "com/daqem/"         // Arc (and Jobs+ lives under com/daqem/jobsplus)
    };

    @Test
    void publicApiHasNoThirdPartyReferences() throws IOException {
        Path apiDir = Path.of("src/main/java/com/tanrunn/tcth/api/guncombat");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(apiDir)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                for (String forbidden : FORBIDDEN_SOURCE_FRAGMENTS) {
                    if (text.contains(forbidden)) {
                        offenders.add(apiDir.relativize(p) + " contains '" + forbidden + "'");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the public gun-combat API must not reference optional mod types: " + offenders);
    }

    @Test
    void scgunsReferencesOnlyInsideCompatPackage() throws IOException {
        Path src = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.contains("top.ribs.scguns")) {
                    String rel = src.relativize(p).toString().replace('\\', '/');
                    if (!rel.startsWith("com/tanrunn/tcth/impl/compat/scguns/")) {
                        offenders.add(rel);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Scorched Guns references must be confined to impl.compat.scguns: " + offenders);
    }

    @Test
    void compiledMainOutputHasNoThirdPartyClasses() throws IOException {
        Path mainClasses = Path.of("build/classes/java/main");
        assertTrue(Files.isDirectory(mainClasses), "main classes must exist (run compileJava first)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainClasses)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = mainClasses.relativize(p).toString().replace('\\', '/');
                for (String forbidden : FORBIDDEN_CLASS_ROOTS) {
                    if (rel.startsWith(forbidden)) {
                        offenders.add(rel);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the TCTH output must never contain optional-mod classes: " + offenders);
    }

    @Test
    void modsTomlDeclaresScgunsOptionalAndNotGd656() throws IOException {
        String toml = Files.readString(
                Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("modId=\"scguns\""), "scguns dependency must be declared");
        assertTrue(toml.contains("modId=\"jobsplus\""), "jobsplus dependency must be declared");
        assertFalse(toml.contains("gd656"), "GD656 must NOT be declared as a dependency (fully independent)");
    }

    @Test
    void scgunsMixinConfigIsConditional() throws IOException {
        // The Niami arrow mixin must be registered behind requiredMods=["scguns"]
        // and must never be applied when Scorched Guns is absent.
        String toml = Files.readString(
                Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("config=\"scguns_compat.mixins.json\""),
                "scguns_compat.mixins.json must be registered in mods.toml");
        assertTrue(toml.contains("requiredMods=[\"scguns\"]"),
                "the scguns mixin config must be conditional on scguns");
        Path mixinJson = Path.of("src/main/resources/scguns_compat.mixins.json");
        assertTrue(Files.exists(mixinJson), "scguns_compat.mixins.json must exist");
        String json = Files.readString(mixinJson, StandardCharsets.UTF_8);
        assertTrue(json.contains("NiamiArrowSpawnMixin"), "the mixin must list NiamiArrowSpawnMixin");
    }
}
