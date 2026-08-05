package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Verifies the {@code fieldguide} lazy descriptor:
 * <ul>
 *   <li>when Field Guide is missing, the implementation class is never
 *       resolved (counted via the injectable resolver);</li>
 *   <li>the descriptor target is a valid {@link CompatModule};</li>
 *   <li>module init is idempotent.</li>
 * </ul>
 */
class FieldGuideCompatDescriptorTest {

    private static final String IMPL =
            "com.tanrunn.tcth.impl.compat.fieldguide.FieldGuideCompatModule";

    private IEventBus bus;

    @BeforeEach
    void setUp() {
        CompatLoader.resetForTesting();
        bus = BusBuilder.builder().build();
    }

    @AfterEach
    void tearDown() {
        CompatLoader.resetForTesting();
    }

    @Test
    void fieldGuideMissingNeverResolvesImplementationClass() {
        CountingResolver resolver = new CountingResolver();
        CompatLoader.setModPresenceForTesting(modId -> false);
        CompatLoader.setClassResolverForTesting(resolver);

        assertTrue(CompatLoader.register("fieldguide", IMPL));
        CompatLoader.init(bus);

        assertEquals(0, resolver.resolveCalls,
                "the Field Guide implementation class must never be resolved without Field Guide");
        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
    }

    @Test
    void fieldGuidePresentLoadsModuleExactlyOnce() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        assertTrue(CompatLoader.register("fieldguide", IMPL));
        CompatLoader.init(bus);
        CompatLoader.init(bus); // idempotent

        assertEquals(1, CompatLoader.loadedModulesForTesting().size(),
                "repeated init must not load the module twice");
        assertEquals("fieldguide", CompatLoader.loadedModulesForTesting().get(0).modId());
    }

    @Test
    void descriptorTargetIsInstantiableAndReportsModId() throws Exception {
        Class<?> clazz = Class.forName(IMPL);
        CompatModule module = (CompatModule) clazz.getDeclaredConstructor().newInstance();
        assertEquals("fieldguide", module.modId());
    }

    /** Counts resolve calls so tests can prove classes are not resolved. */
    static class CountingResolver implements CompatLoader.ClassResolver {

        int resolveCalls = 0;

        @Override
        public Class<?> resolve(String className) throws ClassNotFoundException {
            resolveCalls++;
            return Class.forName(className);
        }
    }
}
