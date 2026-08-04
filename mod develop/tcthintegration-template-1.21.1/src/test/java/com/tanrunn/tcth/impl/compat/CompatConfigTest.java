package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Verifies the mixin conditional-loading configuration and that the compiled
 * main output (which becomes the released JAR) contains no third-party
 * classes.
 */
class CompatConfigTest {

    private String readResource(String relative) throws IOException {
        return Files.readString(Path.of("src/main/resources", relative), StandardCharsets.UTF_8);
    }

    @Test
    void farmersDelightConfigIsIsolatedAndCorrect() throws IOException {
        String json = readResource("farmersdelight_compat.mixins.json");
        assertTrue(json.contains("\"package\": \"com.tanrunn.tcth.mixin.farmersdelight\""));
        assertTrue(json.contains("CookingPotResultSlotMixin"));
        assertFalse(json.contains("kaleidoscope"), "FD config must not contain KC mixins");
    }

    @Test
    void kaleidoscopeConfigIsIsolatedAndCorrect() throws IOException {
        String json = readResource("kaleidoscope_cookery_compat.mixins.json");
        assertTrue(json.contains("\"package\": \"com.tanrunn.tcth.mixin.kaleidoscope\""));
        assertTrue(json.contains("PotBlockEntityMixin"));
        assertTrue(json.contains("StockpotBlockEntityMixin"));
        assertTrue(json.contains("SteamerBlockEntityMixin"));
        assertFalse(json.contains("farmersdelight"), "KC config must not contain FD mixins");
    }

    @Test
    void mainMixinConfigStaysEmpty() throws IOException {
        String json = readResource("tcth.mixins.json");
        assertTrue(json.contains("\"mixins\": []"), "the main mixin config must remain empty");
    }

    @Test
    void modsTomlDeclaresRequiredModsForConditionalConfigs() throws IOException {
        String toml = Files.readString(
                Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("config=\"farmersdelight_compat.mixins.json\""), "FD config must be declared");
        assertTrue(toml.contains("requiredMods=[\"farmersdelight\"]"), "FD config must require farmersdelight");
        assertTrue(toml.contains("config=\"kaleidoscope_cookery_compat.mixins.json\""), "KC config must be declared");
        assertTrue(toml.contains("requiredMods=[\"kaleidoscope_cookery\"]"), "KC config must require kaleidoscope_cookery");
    }

    @Test
    void compiledMainOutputContainsNoThirdPartyClasses() throws IOException {
        Path mainClasses = Path.of("build/classes/java/main");
        assertTrue(Files.isDirectory(mainClasses), "main classes must exist (run compileJava first)");
        List<String> forbidden = List.of(
                "vectorwing/", "com/github/ysbbbbbb", "com/daqem/",
                "io/ejekta/", "cn/breezeth/", "io/github/lightman314/");
        try (Stream<Path> walk = Files.walk(mainClasses)) {
            List<String> offenders = walk
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> p.toString().substring(mainClasses.toString().length() + 1))
                    .filter(p -> forbidden.stream().anyMatch(p::startsWith))
                    .toList();
            assertTrue(offenders.isEmpty(), "main output must not contain third-party classes: " + offenders);
        }
    }
}
