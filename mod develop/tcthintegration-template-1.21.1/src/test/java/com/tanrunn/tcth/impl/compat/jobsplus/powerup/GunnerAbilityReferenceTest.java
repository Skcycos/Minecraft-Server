package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

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
 * Phase 5B.1 package/layout boundaries. Real dependency-safety evidence
 * (bytecode constant pools, config requiredMods, javap structure) lives in
 * {@link GunnerDependencyMatrixTest}; this class only guards file layout:
 * every SG mixin class lives in the scguns mixin package and is declared in
 * exactly one conditional config.
 */
class GunnerAbilityReferenceTest {

    @Test
    void sgEvidenceClassLivesInsideScgunsPackage() throws IOException {
        String path = "src/main/java/com/tanrunn/tcth/impl/compat/scguns/SgDamageEvidence.java";
        assertTrue(Files.exists(Path.of(path)), "SgDamageEvidence must exist");
        String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("package com.tanrunn.tcth.impl.compat.scguns;"),
                "SgDamageEvidence must live in the scguns compat package");
    }

    @Test
    void eachSgMixinDeclaredInItsOwnConditionalConfig() throws IOException {
        String niami = Files.readString(Path.of("src/main/resources/scguns_compat.mixins.json"), StandardCharsets.UTF_8);
        String ammo = Files.readString(Path.of("src/main/resources/scguns_ammo_compat.mixins.json"), StandardCharsets.UTF_8);
        assertTrue(niami.contains("NiamiArrowSpawnMixin") && !niami.contains("AmmoSaverMixin"),
                "niami config: Niami only");
        assertTrue(ammo.contains("AmmoSaverMixin") && !ammo.contains("NiamiArrowSpawnMixin"),
                "ammo config: AmmoSaver only");
        // Every mixin class in the package must be referenced by one config.
        Path mixinDir = Path.of("src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin");
        try (Stream<Path> walk = Files.walk(mixinDir)) {
            for (Path p : walk.filter(p -> p.toString().endsWith("Mixin.java")).toList()) {
                String simple = p.getFileName().toString().replace(".java", "");
                assertTrue(niami.contains(simple) || ammo.contains(simple),
                        "mixin " + simple + " must be declared in a conditional config");
            }
        }
    }

    @Test
    void onlySgMixinsResideInTheMixinPackage() throws IOException {
        Path mixinDir = Path.of("src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mixinDir)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (text.contains("top.ribs.scguns") && !p.getFileName().toString().endsWith("Mixin.java")) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "unexpected SG-referencing class in mixin package: " + offenders);
    }
}
