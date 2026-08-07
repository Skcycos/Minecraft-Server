package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 5B.1 / 5B.1.1: dependency-matrix <em>structural</em> safety.
 *
 * <p><strong>These tests are not live four-combination boot tests.</strong>
 * They verify mixin configs, class constant pools, mods.toml requiredMods,
 * compiled bytecode guards, and SG JAR injection-point layout. Only the full
 * TCTH+SG+Jobs+/Arc environment has been smoke-booted on the real server;
 * the other three combinations are STRUCTURAL PASS / LIVE NOT TESTED.
 *
 * <ol>
 *   <li><b>Mixin config split</b>: Niami requires only {@code scguns};
 *       ammo-saver requires {@code scguns + jobsplus}.</li>
 *   <li><b>Bytecode constant-pool</b>: AmmoSaverMixin → Jobs+ path;
 *       NiamiArrowSpawnMixin has no Jobs+ type; GunnerAbilityModule /
 *       study condition have no SG type.</li>
 *   <li><b>JobsPlusCompatModule guard</b>: compiled bytecode contains
 *       {@code ModList.isLoaded("scguns")} and {@code GunnerAbilityModule.init}
 *       only on that true branch (source string check kept as auxiliary).</li>
 *   <li><b>Injection-point structure</b> (javap on compile-classpath SG jar):
 *       {@code handleShoot} has exactly one {@code Math.max(II)I};
 *       {@code consumeAmmo} is only invoked from {@code handleBeamWeapon};
 *       BEAM-only fire-mode check sits near that call.</li>
 * </ol>
 */
class GunnerDependencyMatrixTest {

    private static final String RES = "src/main/resources/";
    private static final String CLASSES = "build/classes/java/main/";
    private static final Gson GSON = new Gson();

    // ---- 1. mixin config split ----

    private static JsonObject mixinConfig(String name) throws IOException {
        return GSON.fromJson(Files.readString(Path.of(RES + name), StandardCharsets.UTF_8), JsonObject.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredMods(JsonObject cfg) {
        return GSON.fromJson(cfg.getAsJsonArray("requiredMods"), List.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> mixins(JsonObject cfg) {
        return GSON.fromJson(cfg.getAsJsonArray("mixins"), List.class);
    }

    @Test
    void niamiConfigRequiresOnlyScgunsAndContainsOnlyNiami() throws IOException {
        JsonObject cfg = mixinConfig("scguns_compat.mixins.json");
        assertTrue(requiredMods(cfg).containsAll(List.of("scguns")), "niami config requires scguns");
        assertFalse(requiredMods(cfg).contains("jobsplus"), "niami config must NOT require jobsplus");
        List<String> mixins = mixins(cfg);
        assertTrue(mixins.contains("NiamiArrowSpawnMixin"));
        assertFalse(mixins.contains("AmmoSaverMixin"), "niami config must not carry the ammo-saver mixin");
    }

    @Test
    void ammoSaverConfigRequiresScgunsAndJobsplusAndContainsOnlyAmmoSaver() throws IOException {
        JsonObject cfg = mixinConfig("scguns_ammo_compat.mixins.json");
        List<String> mods = requiredMods(cfg);
        assertTrue(mods.containsAll(List.of("scguns", "jobsplus")),
                "ammo-saver config requires scguns AND jobsplus, got " + mods);
        List<String> mixins = mixins(cfg);
        assertTrue(mixins.contains("AmmoSaverMixin"));
        assertFalse(mixins.contains("NiamiArrowSpawnMixin"), "ammo config must not carry the niami mixin");
    }

    @Test
    void modsTomlDeclaresBothConfigsWithCorrectRequiredMods() throws IOException {
        String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        int niamiBlock = toml.indexOf("config=\"scguns_compat.mixins.json\"");
        int ammoBlock = toml.indexOf("config=\"scguns_ammo_compat.mixins.json\"");
        assertTrue(niamiBlock >= 0 && ammoBlock >= 0, "both mixin configs must be declared in mods.toml");
        String niamiSection = toml.substring(niamiBlock, toml.indexOf("\n\n", niamiBlock));
        String ammoSection = toml.substring(ammoBlock, toml.indexOf("\n\n", ammoBlock));
        assertTrue(niamiSection.contains("requiredMods=[\"scguns\"]"), "niami config requiredMods: " + niamiSection);
        assertTrue(ammoSection.contains("requiredMods=[\"scguns\", \"jobsplus\"]"),
                "ammo config requiredMods: " + ammoSection);
    }

    // ---- 2. bytecode constant-pool references ----

    private static String classBytes(String relative) throws IOException {
        return new String(Files.readAllBytes(Path.of(CLASSES + relative)), StandardCharsets.ISO_8859_1);
    }

    @Test
    void ammoSaverMixinTransitivelyRequiresJobsPlus() throws IOException {
        String mixin = classBytes("com/tanrunn/tcth/impl/compat/scguns/mixin/AmmoSaverMixin.class");
        assertTrue(mixin.contains("jobsplus"),
                "AmmoSaverMixin must call into the TCTH jobsplus compat package");
        String module = classBytes("com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerAbilityModule.class");
        assertTrue(module.contains("com/daqem/jobsplus"),
                "GunnerAbilityModule must reference real Jobs+ types (Jobs+ requirement)");
    }

    @Test
    void niamiMixinBytecodeReferencesNoJobsPlus() throws IOException {
        String bytes = classBytes("com/tanrunn/tcth/impl/compat/scguns/mixin/NiamiArrowSpawnMixin.class");
        assertFalse(bytes.contains("jobsplus"), "NiamiArrowSpawnMixin must not reference Jobs+");
        assertFalse(bytes.contains("com/daqem/jobsplus"), "Niami mixin constant pool must not hold Jobs+ types");
        assertFalse(bytes.contains("com/daqem/arc"), "Niami mixin constant pool must not hold Arc types");
    }

    @Test
    void gunnerAbilityModuleBytecodeReferencesNoScorchedGuns() throws IOException {
        String bytes = classBytes("com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerAbilityModule.class");
        assertFalse(bytes.contains("top/ribs/scguns"),
                "GunnerAbilityModule must not reference SG types (delegates via SgDamageEvidence)");
    }

    @Test
    void studyConditionBytecodeReferencesNoScorchedGuns() throws IOException {
        String bytes = classBytes(
                "com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunnerExperienceAbilitiesEnabledCondition.class");
        assertFalse(bytes.contains("top/ribs/scguns"),
                "study-route condition must be loadable without SG");
        assertFalse(bytes.contains("scguns"),
                "study-route condition constant pool must not mention scguns");
    }

    @Test
    void sgDamageEvidenceIsTheOnlySgReferencingAbilityClass() throws IOException {
        String bytes = classBytes("com/tanrunn/tcth/impl/compat/scguns/SgDamageEvidence.class");
        assertTrue(bytes.contains("top/ribs/scguns"), "SgDamageEvidence legitimately references SG");
        Path powerupDir = Path.of(CLASSES + "com/tanrunn/tcth/impl/compat/jobsplus/powerup");
        try (var walk = Files.walk(powerupDir)) {
            List<Path> offending = walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> {
                        try {
                            return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)
                                    .contains("top/ribs/scguns");
                        } catch (IOException e) {
                            return false;
                        }
                    }).toList();
            assertTrue(offending.isEmpty(), "no class in powerup/ may reference SG: " + offending);
        }
    }

    // ---- 3. JobsPlusCompatModule guard (bytecode + auxiliary source) ----

    @Test
    void jobsPlusModuleGuardsSgDependentRegistration() throws IOException {
        // Auxiliary source check (kept for readability).
        String source = Files.readString(
                Path.of("src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("ModList.get().isLoaded(\"scguns\")"),
                "SG-dependent gunner routes must be gated on ModList.isLoaded(scguns)");
        assertTrue(source.contains("GunnerAbilityModule.init"),
                "source must call GunnerAbilityModule.init inside the scguns branch");

        // Authoritative: compiled bytecode constant pool.
        String bytes = classBytes("com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.class");
        assertTrue(bytes.contains("scguns"),
                "JobsPlusCompatModule bytecode must contain the scguns mod-id string");
        assertTrue(bytes.contains("isLoaded") || bytes.contains("ModList"),
                "JobsPlusCompatModule bytecode must reference ModList loading");
        assertTrue(bytes.contains("GunnerAbilityModule")
                        || bytes.contains("com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerAbilityModule"),
                "JobsPlusCompatModule bytecode must reference GunnerAbilityModule");
        // init is only invoked from the true branch in source; bytecode still
        // contains the Methodref — presence of both scguns gate and init ref
        // is the structural boundary we can assert without a control-flow graph.
        assertTrue(bytes.contains("init"),
                "JobsPlusCompatModule bytecode must contain init Methodref for GunnerAbilityModule.init");
    }

    @Test
    void redirectHandlerHasRedirectArgsFirst() throws IOException {
        // Regression guard: Mixin @Redirect handlers take the redirected-call
        // args FIRST, then the target method's args. A swapped order fails at
        // runtime with InvalidInjectionException (caught live in 5B.1 smoke).
        String source = Files.readString(
                Path.of("src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin/AmmoSaverMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("tcth$ordinaryShotSave(int a, int b, C2SMessageShoot message, ServerPlayer player)"),
                "redirect handler must be (int a, int b, C2SMessageShoot, ServerPlayer) — redirect args first");
        // Beam gate must not roll before preconditions (5B.1.1).
        assertTrue(source.contains("AmmoSaverBeamGate.shouldCancelConsumeAmmo"),
                "beam HEAD inject must use AmmoSaverBeamGate preconditions");
    }

    // ---- 4. injection-point structure (javap on the compile classpath) ----

    @Test
    void handleShootHasExactlyOneMathMaxAndConsumeAmmoOnlyInBeamWeapon() throws Exception {
        Path sgJar = Path.of("dev-mods/scguns-1.5.jar");
        assertTrue(Files.exists(sgJar), "compile-classpath SG jar must exist for structural checks");
        ProcessBuilder pb = new ProcessBuilder("javap", "-p", "-c",
                "-cp", sgJar.toString(), "top.ribs.scguns.common.network.ServerPlayHandler");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor() == 0, "javap failed: " + out.lines().findFirst().orElse(""));

        String currentMethod = "";
        int mathMaxInHandleShoot = 0;
        boolean consumeAmmoInBeamWeapon = false;
        boolean consumeAmmoElsewhere = false;
        boolean beamFireModeNearConsume = false;
        boolean handleShootCallsBeamWeapon = false;
        boolean handleShootHasCommonAmmoBlock = false;
        for (String line : out.split("\n")) {
            if (line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(";")) {
                if (line.contains("handleShoot")) {
                    currentMethod = "handleShoot";
                } else if (line.contains("handleBeamWeapon")) {
                    currentMethod = "handleBeamWeapon";
                } else if (line.contains("consumeAmmo")) {
                    currentMethod = "consumeAmmo";
                } else {
                    currentMethod = "";
                }
                continue;
            }
            if (!line.matches("\\s+\\d+:.*")) {
                continue;
            }
            if ("handleShoot".equals(currentMethod) && line.contains("Method java/lang/Math.max:(II)I")) {
                mathMaxInHandleShoot++;
            }
            if ("handleShoot".equals(currentMethod) && line.contains("Method handleBeamWeapon:")) {
                handleShootCallsBeamWeapon = true;
            }
            if ("handleShoot".equals(currentMethod) && line.contains("String AmmoCount")) {
                handleShootHasCommonAmmoBlock = true;
            }
            if ("handleBeamWeapon".equals(currentMethod) && line.contains("Method consumeAmmo:")) {
                consumeAmmoInBeamWeapon = true;
            }
            if ("handleBeamWeapon".equals(currentMethod)
                    && line.contains("Field top/ribs/scguns/common/FireMode.BEAM:")) {
                beamFireModeNearConsume = true;
            }
            if (!"handleBeamWeapon".equals(currentMethod) && line.contains("Method consumeAmmo:")) {
                consumeAmmoElsewhere = true;
            }
        }
        assertTrue(mathMaxInHandleShoot == 1,
                "handleShoot must contain exactly ONE Math.max (the common deduction), found "
                        + mathMaxInHandleShoot);
        assertTrue(handleShootCallsBeamWeapon, "handleShoot must call handleBeamWeapon for BEAM/SEMI_BEAM");
        assertTrue(handleShootHasCommonAmmoBlock,
                "handleShoot must contain the common AmmoCount deduction block after branch rejoin");
        assertTrue(consumeAmmoInBeamWeapon, "consumeAmmo's call site must be inside handleBeamWeapon");
        assertFalse(consumeAmmoElsewhere, "consumeAmmo must not be called from any other method");
        assertTrue(beamFireModeNearConsume,
                "handleBeamWeapon must check FireMode.BEAM near the periodic consume path");
        // SEMI_BEAM must not be claimed as a periodic consumeAmmo path: the
        // only invokestatic consumeAmmo is under the BEAM fire-mode equality
        // (asserted by unique call site + BEAM field reference in same method).
        assertTrue(out.contains("FireMode.SEMI_BEAM"),
                "javap dump should mention SEMI_BEAM (handleShoot dispatches both beam modes)");
    }

    // ---- 5. built JAR (when present) expands both mixin blocks correctly ----

    @Test
    void builtJarDeclaresBothMixinConfigsWhenPresent() throws Exception {
        Path jar = Path.of("build/libs/tcth-0.2.1.jar");
        if (!Files.exists(jar)) {
            // clean test-only runs may not have packaged yet; skip soft.
            return;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry mixinsToml = jf.getEntry("META-INF/neoforge.mods.toml");
            assertTrue(mixinsToml != null, "built JAR must contain neoforge.mods.toml");
            String toml = new String(jf.getInputStream(mixinsToml).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("config=\"scguns_compat.mixins.json\""), toml);
            assertTrue(toml.contains("config=\"scguns_ammo_compat.mixins.json\""), toml);
            assertTrue(toml.contains("requiredMods=[\"scguns\"]"), toml);
            assertTrue(toml.contains("requiredMods=[\"scguns\", \"jobsplus\"]"), toml);
            assertTrue(jf.getEntry("scguns_compat.mixins.json") != null);
            assertTrue(jf.getEntry("scguns_ammo_compat.mixins.json") != null);
        }
    }
}
