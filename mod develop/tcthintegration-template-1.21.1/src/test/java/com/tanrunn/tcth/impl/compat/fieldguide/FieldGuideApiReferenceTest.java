package com.tanrunn.tcth.impl.compat.fieldguide;

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
 * Verifies the conditional-compat boundary:
 * <ul>
 *   <li>(21) {@code com.evandev.fieldguide} references appear ONLY inside
 *       {@code impl.compat.fieldguide} in the source tree;</li>
 *   <li>(22) the compiled main output (which becomes the released JAR)
 *       contains no Field Guide classes;</li>
 *   <li>the mods.toml declares Field Guide as an optional dependency.</li>
 * </ul>
 */
class FieldGuideApiReferenceTest {

    @Test
    void fieldGuideReferencesOnlyInCompatPackage() throws IOException {
        Path src = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.contains("com.evandev.fieldguide")) {
                    String rel = src.relativize(p).toString().replace('\\', '/');
                    if (!rel.startsWith("com/tanrunn/tcth/impl/compat/fieldguide/")) {
                        offenders.add(rel);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Field Guide API references must be confined to impl.compat.fieldguide: " + offenders);
    }

    @Test
    void compiledMainOutputContainsNoFieldGuideClasses() throws IOException {
        Path mainClasses = Path.of("build/classes/java/main");
        assertTrue(Files.isDirectory(mainClasses), "main classes must exist (run compileJava first)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainClasses)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = mainClasses.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("com/evandev/fieldguide/")) {
                    offenders.add(rel);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the TCTH output must never contain Field Guide classes: " + offenders);
    }

    @Test
    void modsTomlDeclaresFieldGuideOptional() throws IOException {
        String toml = Files.readString(
                Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("modId=\"fieldguide\""), "fieldguide dependency must be declared");
        assertTrue(toml.contains("type=\"optional\""), "fieldguide dependency must be optional");
    }
}
