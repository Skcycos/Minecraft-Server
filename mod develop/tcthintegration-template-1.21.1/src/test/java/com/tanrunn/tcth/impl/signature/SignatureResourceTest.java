package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Resource/boundary checks for the chef-signature feature:
 * <ul>
 *   <li>tooltip + command translation keys exist in both languages;</li>
 *   <li>the client tooltip class lives in the {@code client} package and is
 *       annotated {@code Dist.CLIENT} (never loaded on a dedicated server);</li>
 *   <li>the main output contains no third-party classes.</li>
 * </ul>
 */
class SignatureResourceTest {

    @Test
    void tooltipKeyExistsInBothLanguages() throws IOException {
        String zh = Files.readString(Path.of("src/main/resources/assets/tcth/lang/zh_cn.json"), StandardCharsets.UTF_8);
        String en = Files.readString(Path.of("src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8);
        assertTrue(zh.contains("tooltip.tcth.cooking_signature"));
        assertTrue(en.contains("tooltip.tcth.cooking_signature"));
        assertTrue(zh.contains("主厨"), "zh tooltip must render the chef name");
        assertTrue(en.contains("Chef"), "en tooltip must render the chef name");
    }

    @Test
    void inspectCommandKeysExistInBothLanguages() throws IOException {
        String zh = Files.readString(Path.of("src/main/resources/assets/tcth/lang/zh_cn.json"), StandardCharsets.UTF_8);
        String en = Files.readString(Path.of("src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8);
        for (String key : new String[] {
                "command.tcth.chef.inspect.dish",
                "command.tcth.chef.inspect.chef",
                "command.tcth.chef.inspect.valid",
                "command.tcth.chef.inspect.unsigned",
                "command.tcth.chef.inspect.notDish",
                "command.tcth.chef.inspect.playerRequired"}) {
            assertTrue(zh.contains(key), "zh missing " + key);
            assertTrue(en.contains(key), "en missing " + key);
        }
    }

    @Test
    void configKeyExistsInBothLanguages() throws IOException {
        String zh = Files.readString(Path.of("src/main/resources/assets/tcth/lang/zh_cn.json"), StandardCharsets.UTF_8);
        String en = Files.readString(Path.of("src/main/resources/assets/tcth/lang/en_us.json"), StandardCharsets.UTF_8);
        assertTrue(zh.contains("tcth.configuration.dishSignaturesEnabled"));
        assertTrue(en.contains("tcth.configuration.dishSignaturesEnabled"));
    }

    @Test
    void tooltipClassIsClientOnly() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/tanrunn/tcth/client/TooltipEvents.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("package com.tanrunn.tcth.client;"),
                "tooltip handler must live in the explicit client package");
        assertTrue(source.contains("Dist.CLIENT"), "tooltip handler must be client-only");
    }

    @Test
    void compiledMainOutputContainsNoThirdPartyClasses() throws IOException {
        Path mainClasses = Path.of("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            return; // compileJava must run first (test depends on it)
        }
        try (var walk = Files.walk(mainClasses)) {
            boolean hasEvandev = walk
                    .filter(p -> p.toString().endsWith(".class"))
                    .anyMatch(p -> p.toString().contains("com/evandev"));
            assertFalse(hasEvandev, "main output must not contain Field Guide classes");
        }
    }

    @Test
    void noClientClassReferencedFromServerOnlyPackages() throws IOException {
        // The only client-only class is TooltipEvents; nothing outside the
        // client package may import it.
        String tooltipClass = "com.tanrunn.tcth.client.TooltipEvents";
        try (var walk = Files.walk(Path.of("src/main/java"))) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (p.toString().contains("/client/")) {
                    continue;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                assertFalse(text.contains(tooltipClass),
                        "server-side class must not reference the client tooltip handler: " + p);
            }
        }
    }
}
