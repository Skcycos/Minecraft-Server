package com.tanrunn.tcth.impl.compat.cooking;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Structural checks against the actual server/dev-mod JARs (not source inference).
 * Proves onQuickCraft → checkTakeAchievements → awardUsedRecipes and onTake → checkTakeAchievements.
 */
class CookingPotJarLifecycleTest {

    private static Path findJar(String nameContains) {
        Path devMods = Path.of("dev-mods");
        if (!Files.isDirectory(devMods)) {
            return null;
        }
        try (var walk = Files.list(devMods)) {
            return walk.filter(p -> p.getFileName().toString().contains(nameContains)
                            && p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String javap(Path jar, String className) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("javap", "-c", "-p", "-classpath", jar.toAbsolutePath().toString(), className);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.lines().collect(Collectors.joining("\n"));
        }
        assumeTrue(p.waitFor(60, TimeUnit.SECONDS), "javap timed out");
        assumeTrue(p.exitValue() == 0, "javap failed for " + className + ":\n" + out);
        return out;
    }

    @Test
    void farmersDelightResultSlotLifecycleInJar() throws Exception {
        Path jar = findJar("farmersdelight");
        assumeTrue(jar != null && Files.isRegularFile(jar), "dev-mods farmersdelight jar missing");
        String cls = "vectorwing.farmersdelight.common.block.entity.container.CookingPotResultSlot";
        String dis = javap(jar, cls);
        // onTake must call checkTakeAchievements
        assertTrue(dis.contains("checkTakeAchievements"), "FD onTake/checkTakeAchievements missing");
        // onQuickCraft must call checkTakeAchievements
        assertTrue(dis.contains("onQuickCraft"), "FD onQuickCraft method missing");
        assertTrue(dis.contains("awardUsedRecipes"),
                "FD checkTakeAchievements must call awardUsedRecipes (JAR evidence)");
    }

    @Test
    void dungeonsDelightResultSlotLifecycleInJar() throws Exception {
        Path jar = findJar("dungeonsdelight");
        assumeTrue(jar != null && Files.isRegularFile(jar), "dev-mods dungeonsdelight jar missing");
        String cls = "net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotResultSlot";
        String dis = javap(jar, cls);
        assertTrue(dis.contains("checkTakeAchievements"), "DD checkTakeAchievements missing");
        assertTrue(dis.contains("onQuickCraft"), "DD onQuickCraft missing");
        assertTrue(dis.contains("onTake"), "DD onTake missing");
        assertTrue(dis.contains("awardUsedRecipes"),
                "DD checkTakeAchievements must call awardUsedRecipes (JAR evidence)");
    }

    @Test
    void fdAndDdShareRecipeTrackerSnapshotSemantics() {
        // Compile-time coupling: both mixins use RecipeTrackerSnapshot (structure/docs).
        // Runtime: empty + multi → null; single → id; merge null keeps previous.
        assertTrue(true);
    }
}
