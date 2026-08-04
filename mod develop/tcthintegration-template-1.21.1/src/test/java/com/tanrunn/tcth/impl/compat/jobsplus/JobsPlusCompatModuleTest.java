package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Unit tests for {@link JobsPlusCompatModule}.
 */
class JobsPlusCompatModuleTest {

    private final IEventBus bus = BusBuilder.builder().build();

    @Test
    void moduleDisablesItselfWhenArcIsMissing() {
        // In a bare JUnit environment no mods are loaded, so CompatLoader's
        // isModLoaded("arc") is false and the module must disable itself.
        JobsPlusCompatModule module = new JobsPlusCompatModule();
        module.onModConstruction(bus);
        assertFalse(module.isArcAvailableForTesting(),
                "module must disable cleanly when Arc is missing");
    }
}
