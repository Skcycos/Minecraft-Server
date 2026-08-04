package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.compat.CompatModule;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Unit tests for {@link CompatLoader} lazy descriptor loading.
 *
 * <p>The {@code ModList}-based presence check and the {@code Class.forName}
 * resolution are both replaced with injectable hooks so the loader behaviour
 * is testable without a running NeoForge/FML environment, and so tests can
 * prove that implementation classes are never resolved when the target mod is
 * missing.
 */
class CompatLoaderTest {

    private static final String FAKE_MOD_ID = "fake_mod";
    private static final String FAKE_IMPL = FakeModule.class.getName();
    private static final String MISSING_IMPL = "com.tanrunn.tcth.impl.compat.NonExistentModule";

    private IEventBus bus;

    @BeforeEach
    void setUp() {
        CompatLoader.resetForTesting();
        FakeModule.resetCounters();
        bus = BusBuilder.builder().build();
    }

    @AfterEach
    void tearDown() {
        CompatLoader.resetForTesting();
        FakeModule.resetCounters();
    }

    // ---- original behaviour (must keep passing) ----

    @Test
    void missingModDoesNotLoadImplementationClass() {
        CompatLoader.setModPresenceForTesting(modId -> false);

        assertTrue(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL));
        CompatLoader.init(bus);

        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
        assertEquals(0, FakeModule.constructionCount,
                "implementation class must not be instantiated when the target mod is missing");
        assertEquals(0, FakeModule.constructionHookCount);
    }

    @Test
    void duplicateRegistrationIsRejected() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        assertTrue(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL));
        assertFalse(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL),
                "second registration with the same mod id must be rejected");

        CompatLoader.init(bus);
        assertEquals(1, CompatLoader.loadedModulesForTesting().size());
        assertEquals(1, FakeModule.constructionCount);
    }

    @Test
    void initIsIdempotent() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL);
        CompatLoader.init(bus);
        CompatLoader.init(bus);

        assertEquals(1, CompatLoader.loadedModulesForTesting().size(),
                "modules must not be loaded twice when init is called repeatedly");
        assertEquals(1, FakeModule.constructionCount);
    }

    @Test
    void failingModuleDoesNotAffectOtherModules() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        assertTrue(CompatLoader.register("mod_a", MISSING_IMPL));
        assertTrue(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL));
        CompatLoader.init(bus);

        List<CompatModule> loaded = CompatLoader.loadedModulesForTesting();
        assertEquals(1, loaded.size(), "failed module must not prevent the healthy one from loading");
        assertEquals(FAKE_MOD_ID, loaded.get(0).modId());
        assertEquals(1, FakeModule.constructionCount);
    }

    @Test
    void nonCompatModuleClassIsRejected() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        // java.lang.String does not implement CompatModule.
        assertTrue(CompatLoader.register("mod_x", String.class.getName()));
        CompatLoader.init(bus);

        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
    }

    @Test
    void onModConstructionRunsAfterSuccessfulConstruction() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL);
        CompatLoader.init(bus);

        assertEquals(1, FakeModule.constructionCount);
        assertEquals(1, FakeModule.constructionHookCount,
                "onModConstruction must be invoked once for each loaded module");
    }

    // ---- phase 0.2 hardening ----

    @Test
    void constructionFailurePreventsRegistrationButKeepsOthers() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        assertTrue(CompatLoader.register("mod_construction_throws", ConstructionThrowingModule.class.getName()));
        assertTrue(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL));
        CompatLoader.init(bus);

        List<CompatModule> loaded = CompatLoader.loadedModulesForTesting();
        assertEquals(1, loaded.size(),
                "module whose onModConstruction throws must not appear in the loaded list");
        assertEquals(FAKE_MOD_ID, loaded.get(0).modId());
        assertEquals(1, FakeModule.constructionCount);
        assertEquals(1, FakeModule.constructionHookCount);
    }

    @Test
    void commonSetupFailureDoesNotBlockOtherModules() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        assertTrue(CompatLoader.register("mod_setup_throws", CommonSetupThrowingModule.class.getName()));
        assertTrue(CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL));
        CompatLoader.init(bus);
        assertEquals(2, CompatLoader.loadedModulesForTesting().size());

        CompatLoader.onCommonSetup(null);

        assertEquals(1, FakeModule.commonSetupCount,
                "a throwing module must not prevent later modules from receiving common setup");
    }

    @Test
    void commonSetupForwardedExactlyOncePerEvent() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL);
        CompatLoader.init(bus);

        CompatLoader.onCommonSetup(null);

        assertEquals(1, FakeModule.commonSetupCount,
                "common setup must be forwarded exactly once per event");
    }

    @Test
    void wrongModIdModuleIsRejected() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        // Registered under "mod_wrong" but the implementation reports a
        // different modId.
        assertTrue(CompatLoader.register("mod_wrong", WrongModIdModule.class.getName()));
        CompatLoader.init(bus);

        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
    }

    @Test
    void registerAfterInitIsRejected() {
        CompatLoader.setModPresenceForTesting(modId -> true);

        CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL);
        CompatLoader.init(bus);

        assertFalse(CompatLoader.register("late_mod", FAKE_IMPL),
                "registration must be rejected after init");
        assertEquals(1, CompatLoader.loadedModulesForTesting().size());
    }

    @Test
    void missingModNeverResolvesImplementationClass() {
        CountingResolver resolver = new CountingResolver();
        CompatLoader.setModPresenceForTesting(modId -> false);
        CompatLoader.setClassResolverForTesting(resolver);

        CompatLoader.register(FAKE_MOD_ID, FAKE_IMPL);
        CompatLoader.init(bus);

        assertEquals(0, resolver.resolveCalls,
                "class resolver must never run when the target mod is missing");
        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
    }

    @Test
    void jobsPlusModuleNotResolvedWhenJobsPlusMissing() {
        CountingResolver resolver = new CountingResolver();
        CompatLoader.setModPresenceForTesting(modId -> false);
        CompatLoader.setClassResolverForTesting(resolver);

        CompatLoader.register("jobsplus",
                "com.tanrunn.tcth.impl.compat.jobsplus.JobsPlusCompatModule");
        CompatLoader.init(bus);

        assertEquals(0, resolver.resolveCalls,
                "the Jobs+ implementation class must never be resolved without Jobs+");
        assertTrue(CompatLoader.loadedModulesForTesting().isEmpty());
    }

    @Test
    void jobsPlusModuleClassIsInstantiableAndReportsModId() throws Exception {
        // Verifies the descriptor target is a valid CompatModule without
        // invoking onModConstruction (which needs a live Arc/FML environment).
        Class<?> clazz = Class.forName("com.tanrunn.tcth.impl.compat.jobsplus.JobsPlusCompatModule");
        CompatModule module = (CompatModule) clazz.getDeclaredConstructor().newInstance();
        assertEquals("jobsplus", module.modId());
    }

    // ---- test doubles ----

    /** Minimal healthy module; static counters let tests observe lifecycle calls. */
    public static class FakeModule implements CompatModule {

        static int constructionCount = 0;
        static int constructionHookCount = 0;
        static int commonSetupCount = 0;

        public FakeModule() {
            constructionCount++;
        }

        @Override
        public String modId() {
            return FAKE_MOD_ID;
        }

        @Override
        public void onModConstruction(IEventBus modEventBus) {
            constructionHookCount++;
        }

        @Override
        public void onCommonSetup(FMLCommonSetupEvent event) {
            commonSetupCount++;
        }

        static void resetCounters() {
            constructionCount = 0;
            constructionHookCount = 0;
            commonSetupCount = 0;
        }
    }

    /** Fails in onModConstruction; must not reach the loaded list. */
    public static class ConstructionThrowingModule implements CompatModule {

        public ConstructionThrowingModule() {
        }

        @Override
        public String modId() {
            return "mod_construction_throws";
        }

        @Override
        public void onModConstruction(IEventBus modEventBus) {
            throw new IllegalStateException("boom in construction hook");
        }
    }

    /** Fails in onCommonSetup; must not block later modules. */
    public static class CommonSetupThrowingModule implements CompatModule {

        public CommonSetupThrowingModule() {
        }

        @Override
        public String modId() {
            return "mod_setup_throws";
        }

        @Override
        public void onCommonSetup(FMLCommonSetupEvent event) {
            throw new IllegalStateException("boom in common setup");
        }
    }

    /** Reports a modId that does not match its registration descriptor. */
    public static class WrongModIdModule implements CompatModule {

        public WrongModIdModule() {
        }

        @Override
        public String modId() {
            return "mismatched_mod";
        }
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
