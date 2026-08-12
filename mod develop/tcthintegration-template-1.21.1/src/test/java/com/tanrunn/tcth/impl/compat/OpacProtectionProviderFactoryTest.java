package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.shadow.protection.OpacProtectionProviderFactory;

/**
 * Unit tests for {@link OpacProtectionProviderFactory} (phase 8C.0).
 *
 * <p>Lives in {@code impl.compat} so the package-private CompatLoader test
 * hooks are reachable. Proves the string-isolated factory yields no provider
 * when Open Parties and Claims is absent (the composite then denies) and a
 * provider when it is present.
 */
class OpacProtectionProviderFactoryTest {

    @AfterEach
    void tearDown() {
        CompatLoader.resetForTesting();
    }

    @Test
    void factoryReturnsNullWhenModAbsent() {
        CompatLoader.setModPresenceForTesting(id -> false);
        assertNull(OpacProtectionProviderFactory.create(),
                "absent OPAC must yield no provider (deny upstream)");
    }

    @Test
    void factoryCreatesProviderWhenModPresent() {
        CompatLoader.setModPresenceForTesting(id -> true);
        assertNotNull(OpacProtectionProviderFactory.create(),
                "with OPAC present the provider must exist");
    }
}
