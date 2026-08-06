package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the lazy-loading contract used by {@link CompatLoader}: every
 * registered compat module implementation class must expose a public no-arg
 * constructor. A private/package-private constructor compiles and passes
 * individual unit tests but fails at server startup (IllegalAccessException in
 * {@code Class.getDeclaredConstructor().newInstance()}) — exactly what the
 * phase 5A smoke test caught for {@code ScorchedGunsCompatModule}.
 */
class CompatModuleConstructibilityTest {

    @Test
    void allCompatModulesHavePublicNoArgConstructor() {
        for (String className : List.of(
                "com.tanrunn.tcth.impl.compat.jobsplus.JobsPlusCompatModule",
                "com.tanrunn.tcth.impl.compat.fieldguide.FieldGuideCompatModule",
                "com.tanrunn.tcth.impl.compat.scguns.ScorchedGunsCompatModule")) {
            assertDoesNotThrow(() -> Class.forName(className).getConstructor(),
                    className + " must expose a public no-arg constructor for CompatLoader");
        }
    }
}
