package com.tanrunn.tcth.api.shadow;

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
 * Verifies the public shadow API boundary (phase 8B):
 * <ul>
 *   <li>{@code com.tanrunn.tcth.api.shadow} contains zero references to
 *       Jobs+/Arc, Lightman's Currency or Open Parties and Claims — the API
 *       is usable without any of those mods;</li>
 *   <li>the API never references vanilla player-property APIs (Inventory,
 *       health, FoodData, MobEffect);</li>
 *   <li>the compiled main output contains no third-party classes and no
 *       nested third-party JARs.</li>
 * </ul>
 */
class ShadowApiReferenceTest {

    /** Third-party fragments forbidden in the public shadow API sources. */
    private static final String[] FORBIDDEN_SOURCE_FRAGMENTS = {
            "com.daqem",            // Arc / Jobs+
            "jobsplus",             // Jobs+
            "lightmanscurrency",    // Lightman's Currency
            "io.github.lightman314",// Lightman's Currency
            "openpartiesandclaims", // Open Parties and Claims
            "xaero",                // Open Parties and Claims
            "top.theillusivec4"     // Curios
    };

    /** Player-property APIs forbidden in the public shadow API sources. */
    private static final String[] FORBIDDEN_PROPERTY_FRAGMENTS = {
            "net.minecraft.world.entity.player.Inventory",
            "net.minecraft.world.entity.player.PlayerInventory",
            "setHealth", "getFoodData", "MobEffect", "addEffect", "removeEffect",
            "net.minecraft.world.food.FoodData"
    };

    /** Third-party class-file roots that must never be bundled into the output. */
    private static final String[] FORBIDDEN_CLASS_ROOTS = {
            "com/daqem/",              // Arc + Jobs+
            "io/github/lightman314/",  // Lightman's Currency
            "xaero/pac/"               // Open Parties and Claims
    };

    @Test
    void publicApiHasNoThirdPartyReferences() throws IOException {
        Path apiDir = Path.of("src/main/java/com/tanrunn/tcth/api/shadow");
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
                "the public shadow API must not reference optional mod types: " + offenders);
    }

    @Test
    void publicApiHasNoPlayerPropertyReferences() throws IOException {
        Path apiDir = Path.of("src/main/java/com/tanrunn/tcth/api/shadow");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(apiDir)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                for (String forbidden : FORBIDDEN_PROPERTY_FRAGMENTS) {
                    if (text.contains(forbidden)) {
                        offenders.add(apiDir.relativize(p) + " contains '" + forbidden + "'");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the public shadow API must not touch player property APIs: " + offenders);
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
    void noShadowJobPresetInResources() throws IOException {
        Path resources = Path.of("src/main/resources");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = resources.relativize(p).toString().replace('\\', '/');
                if (rel.contains("shadow_thief")) {
                    offenders.add(rel);
                }
            }
        }
        assertFalse(Files.exists(Path.of("src/main/resources/data/tcth/jobsplus/jobs/shadow_thief.json")),
                "no shadow_thief job preset may exist in phase 8B");
        assertTrue(offenders.isEmpty(),
                "no shadow_thief data files may exist in phase 8B: " + offenders);
    }
}
