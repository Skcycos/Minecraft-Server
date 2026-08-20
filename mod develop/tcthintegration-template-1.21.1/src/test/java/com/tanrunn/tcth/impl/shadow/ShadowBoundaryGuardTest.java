package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Boundary guard for the shadow framework production code (8B §10, 8C.0).
 *
 * <p>Proves the shadow framework source (api.shadow + impl.shadow) contains:
 * <ul>
 *   <li>no real asset mutation: no {@code setHealth}, no {@code addEffect}/
 *       {@code removeEffect}, no Lightman's Currency reference, no Jobs+/Arc;</li>
 *   <li>{@code PlayerInteractEvent} references confined to the interaction
 *       handler (8C.0);</li>
 *   <li>Open Parties and Claims references confined to the string-isolated
 *       OPAC provider / factory;</li>
 *   <li>read-only vanilla property APIs (Inventory / FoodData / MobEffect)
 *       confined to the read-only candidate provider and the tag keys;</li>
 *   <li>no shadow_thief job preset data, no deployment artifacts.</li>
 * </ul>
 */
class ShadowBoundaryGuardTest {

    /** Fragment → file names (suffixes) where the fragment may appear.
     *  Fragments without an entry are forbidden everywhere. */
    private static final Map<String, List<String>> ALLOWED_FILES = new HashMap<>();

    static {
        ALLOWED_FILES.put("PlayerInteractEvent", List.of("PlayerInteractHandler.java"));
        ALLOWED_FILES.put("xaero.pac", List.of("OpacProtectionProvider.java", "OpacProtectionProviderFactory.java"));
        ALLOWED_FILES.put("openpartiesandclaims",
                List.of("OpacProtectionProvider.java", "OpacProtectionProviderFactory.java"));
        // Asset mutations (the real engine) are confined to the engine file;
        // read-only probes and tag keys are the only other holders.
        ALLOWED_FILES.put("MobEffect", List.of("PlayerReadonlyCandidateProvider.java", "ShadowTags.java",
                "PlayerAssetTransferExecutor.java", "EffectPlan.java", "ShadowFeasibility.java",
                "PlayerInteractHandler.java", "ShadowEscapeEffects.java", "ShadowAbilityValues.java"));
        ALLOWED_FILES.put("net.minecraft.world.entity.player.Inventory",
                List.of("PlayerReadonlyCandidateProvider.java", "PlayerAssetTransferExecutor.java",
                        "SlotItemTransaction.java"));
        ALLOWED_FILES.put("getFoodData", List.of("PlayerReadonlyCandidateProvider.java",
                "PlayerAssetTransferExecutor.java", "ShadowFeasibility.java"));
        ALLOWED_FILES.put("getActiveEffects", List.of("PlayerReadonlyCandidateProvider.java",
                "PlayerAssetTransferExecutor.java", "ShadowFeasibility.java"));
        ALLOWED_FILES.put("setHealth", List.of("PlayerAssetTransferExecutor.java"));
        ALLOWED_FILES.put("setFoodLevel", List.of("PlayerAssetTransferExecutor.java"));
        ALLOWED_FILES.put("setSaturation", List.of("PlayerAssetTransferExecutor.java"));
        ALLOWED_FILES.put("removeEffect", List.of("PlayerAssetTransferExecutor.java",
                "ShadowEscapeEffects.java"));
        ALLOWED_FILES.put("forceAddEffect", List.of("PlayerAssetTransferExecutor.java"));
        ALLOWED_FILES.put("addEffect", List.of("PlayerAssetTransferExecutor.java",
                "PlayerInteractHandler.java", "ShadowEscapeEffects.java"));
        // 8E.1 §3: the escape-route ownership lifecycle listens to the
        // server-authoritative MobEffectEvent chain — confined to the escape
        // module alone.
        ALLOWED_FILES.put("MobEffectEvent", List.of("ShadowEscapeEffects.java"));
        ALLOWED_FILES.put("heal(", List.of("PlayerAssetTransferExecutor.java"));
        ALLOWED_FILES.put("setItem", List.of("PlayerAssetTransferExecutor.java",
                "SlotItemTransaction.java"));
        ALLOWED_FILES.put("removeItem", List.of("PlayerAssetTransferExecutor.java"));
    }

    private static final String[] FORBIDDEN_EVERYWHERE = {
            "lightmanscurrency",        // Lightman's Currency
            "io.github.lightman314",    // Lightman's Currency
            "com.daqem",                // Arc / Jobs+
            "jobsplus"                  // Jobs+
    };

    private static final String[] FRAGMENTS_WITH_ALLOWLIST = {
            "PlayerInteractEvent",
            "xaero.pac",
            "openpartiesandclaims",
            "MobEffect",
            "net.minecraft.world.entity.player.Inventory",
            "getFoodData",
            "getActiveEffects",
            "setHealth",
            "setFoodLevel",
            "setSaturation",
            "removeEffect",
            "forceAddEffect",
            "addEffect",
            "MobEffectEvent",
            "heal(",
            "setItem",
            "removeItem"
    };

    private List<String[]> shadowSourcesWithNames() throws IOException {
        List<String[]> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/tanrunn/tcth/api/shadow"))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.add(new String[] { p.getFileName().toString(), read(p) }));
        }
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/tanrunn/tcth/impl/shadow"))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.add(new String[] { p.getFileName().toString(), read(p) }));
        }
        return sources;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void forbiddenFragmentsAreAbsentEverywhere() throws IOException {
        List<String[]> sources = shadowSourcesWithNames();
        assertFalse(sources.isEmpty(), "shadow sources must exist");
        List<String> offenders = new ArrayList<>();
        for (String[] entry : sources) {
            for (String forbidden : FORBIDDEN_EVERYWHERE) {
                if (entry[1].contains(forbidden)) {
                    offenders.add(entry[0] + " contains '" + forbidden + "'");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "shadow code must not reference transfer targets or optional mods: " + offenders);
    }

    @Test
    void allowlistedFragmentsStayInTheirFiles() throws IOException {
        List<String[]> sources = shadowSourcesWithNames();
        List<String> offenders = new ArrayList<>();
        for (String[] entry : sources) {
            for (String fragment : FRAGMENTS_WITH_ALLOWLIST) {
                if (!entry[1].contains(fragment)) {
                    continue;
                }
                List<String> allowed = ALLOWED_FILES.get(fragment);
                if (allowed == null || !allowed.contains(entry[0])) {
                    offenders.add(entry[0] + " contains '" + fragment + "' outside its allowed files");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "allowlisted fragments must stay confined to their files: " + offenders);
    }

    @Test
    void noShadowJobPresetInAnyResourceTree() throws IOException {
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
        assertTrue(offenders.isEmpty(),
                "no shadow_thief datapack preset may ship: " + offenders);
    }

    @Test
    void noDeploymentArtifactsInRepository() throws IOException {
        assertFalse(Files.exists(Path.of("../Server/global_packs/required_data/tcth-shadow_thief")),
                "no server datapack preset folder may exist yet");
        assertFalse(Files.exists(Path.of("../Server/mods/tcth-shadow-thief.jar")),
                "no deployed shadow-thief jar may exist yet");
    }

    @Test
    void apiPackageStaysMinimal() throws IOException {
        List<String> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/tanrunn/tcth/api/shadow"))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.add(read(p)));
        }
        for (String source : sources) {
            assertFalse(source.contains("com.tanrunn.tcth.impl"),
                    "the public API must not reference internal packages");
        }
        assertTrue(sources.size() >= 5, "the API must contain the five planned types");
    }
}
