package com.tanrunn.tcth.impl.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CookingDebug} — the switch must default to off and be
 * independent from event publishing.
 */
class CookingDebugTest {

    @Test
    void debugIsDisabledByDefault() {
        assertFalse(CookingDebug.isEnabled(), "debug logging must be off by default");
    }

    @Test
    void debugCanBeToggledInMemory() {
        CookingDebug.setEnabled(true);
        assertTrue(CookingDebug.isEnabled());
        CookingDebug.setEnabled(false);
        assertFalse(CookingDebug.isEnabled());
    }
}
