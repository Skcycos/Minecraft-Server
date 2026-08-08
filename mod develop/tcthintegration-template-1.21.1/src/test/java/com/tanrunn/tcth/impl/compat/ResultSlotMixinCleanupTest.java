package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Phase 6B.2.3 — structural regression guards for the result-slot mixins:
 * a suppressed Shift-click take must skip only the publish, while the
 * {@code finally} cleanup of {@code recipeIdSnapshot} /
 * {@code previousSignature} runs unconditionally, so the next normal click
 * can never inherit a stale recipe id.
 *
 * <p>These assert the concrete source structure the mixin compiles to (the
 * suppression guard is inside the try, the finally clears both fields). This
 * is a compile-time regression guard; live behavior is covered by
 * {@code RecipeTrackerSnapshotTest} and {@code ShiftTakeTransactionTest}.
 */
class ResultSlotMixinCleanupTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void fdResultSlotFinallyClearsBothSnapshotsUnconditionally() throws Exception {
        String src = read("src/main/java/com/tanrunn/tcth/mixin/farmersdelight/CookingPotResultSlotMixin.java");

        // Suppression guard must be INSIDE the try block (before finally runs).
        int tryIdx = src.indexOf("try {", src.indexOf("tcth$onDishTaken"));
        int suppressedIdx = src.indexOf("isShiftTakeSuppressed(player)", tryIdx);
        int finallyIdx = src.indexOf("finally {", suppressedIdx);
        assertTrue(tryIdx >= 0 && suppressedIdx >= 0 && finallyIdx >= 0,
                "onTake RETURN must have try, suppression guard, finally in order");
        assertTrue(suppressedIdx > tryIdx && suppressedIdx < finallyIdx,
                "suppression guard must be inside the try (before finally)");
        assertTrue(finallyIdx > suppressedIdx,
                "finally must come after the suppression guard");

        // Both snapshots cleared in finally.
        int snapshotNull = src.indexOf("this.tcth$recipeIdSnapshot = null;", finallyIdx);
        int sigNull = src.indexOf("this.tcth$previousSignature = null;", finallyIdx);
        assertTrue(snapshotNull > finallyIdx, "recipeIdSnapshot must be cleared in finally");
        assertTrue(sigNull > finallyIdx, "previousSignature must be cleared in finally");
    }

    @Test
    void ddResultSlotFinallyClearsBothSnapshotsUnconditionally() throws Exception {
        String src = read("src/main/java/com/tanrunn/tcth/mixin/dungeonsdelight/MonsterPotResultSlotMixin.java");

        int tryIdx = src.indexOf("try {", src.indexOf("tcth$onDishTaken"));
        int suppressedIdx = src.indexOf("isShiftTakeSuppressed(player)", tryIdx);
        int finallyIdx = src.indexOf("finally {", suppressedIdx);
        assertTrue(tryIdx >= 0 && suppressedIdx >= 0 && finallyIdx >= 0,
                "onTake RETURN must have try, suppression guard, finally in order");
        assertTrue(suppressedIdx > tryIdx && suppressedIdx < finallyIdx,
                "suppression guard must be inside the try (before finally)");
        assertTrue(finallyIdx > suppressedIdx,
                "finally must come after the suppression guard");

        int snapshotNull = src.indexOf("this.tcth$recipeIdSnapshot = null;", finallyIdx);
        int sigNull = src.indexOf("this.tcth$previousSignature = null;", finallyIdx);
        assertTrue(snapshotNull > finallyIdx, "recipeIdSnapshot must be cleared in finally");
        assertTrue(sigNull > finallyIdx, "previousSignature must be cleared in finally");
    }

    @Test
    void fdResultSlotSuppressionSkipsOnlyPublishNotCleanup() throws Exception {
        String src = read("src/main/java/com/tanrunn/tcth/mixin/farmersdelight/CookingPotResultSlotMixin.java");
        // The suppression guard returns, but the finally still clears: assert
        // the guard is a plain return (not System.exit / no cleanup before it)
        // and the finally block exists after it.
        assertTrue(src.contains("if (isShiftTakeSuppressed(player)) {\n                return;"),
                "suppression must skip publish via early return");
        assertTrue(src.contains("finally {"), "finally must exist");
        // The cleanup must not be reachable before the try.
        assertFalse(src.contains("this.tcth$recipeIdSnapshot = null;\n            return;"),
                "cleanup must not be skipped by an early return before try");
    }

    @Test
    void ddResultSlotSuppressionSkipsOnlyPublishNotCleanup() throws Exception {
        String src = read("src/main/java/com/tanrunn/tcth/mixin/dungeonsdelight/MonsterPotResultSlotMixin.java");
        assertTrue(src.contains("if (isShiftTakeSuppressed(player)) {\n                return;"),
                "suppression must skip publish via early return");
        assertTrue(src.contains("finally {"), "finally must exist");
        assertFalse(src.contains("this.tcth$recipeIdSnapshot = null;\n            return;"),
                "cleanup must not be skipped by an early return before try");
    }
}
