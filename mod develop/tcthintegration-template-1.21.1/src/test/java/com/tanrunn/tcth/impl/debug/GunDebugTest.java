package com.tanrunn.tcth.impl.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GunDebug} (phase 5A.2 review): the gunner debug
 * switch is in-memory only, disabled by default, and togglable at runtime —
 * so a running mob farm can never bloat the log unless an operator opts in.
 */
class GunDebugTest {

    @Test
    void disabledByDefault() {
        assertFalse(GunDebug.isEnabled());
    }

    @Test
    void canBeToggled() {
        GunDebug.setEnabled(true);
        assertTrue(GunDebug.isEnabled());
        GunDebug.setEnabled(false);
        assertFalse(GunDebug.isEnabled());
    }
}
