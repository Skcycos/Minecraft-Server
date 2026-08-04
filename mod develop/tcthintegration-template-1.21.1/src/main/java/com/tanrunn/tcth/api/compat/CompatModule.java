package com.tanrunn.tcth.api.compat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * A conditional compat module.
 *
 * <p>Each supported third-party mod gets its own {@link CompatModule}
 * implementation living under {@code com.tanrunn.tcth.impl.compat.<modid>}.
 * Modules are registered with the compat loader as <em>lazy descriptors</em>:
 * the implementation class is only loaded and instantiated after the target
 * mod has been confirmed present, so the JVM never touches it when the optional
 * dependency is missing.
 *
 * <p><b>Stability:</b> see the API stability statement in
 * {@code com.tanrunn.tcth.api} (pre-release, 0.x — the API may change without
 * notice until 1.0.0).
 *
 * <p>Modules must not perform business logic from this lifecycle; check the
 * framework master switch (or the module's own config toggle) at every business
 * entry point.
 */
public interface CompatModule {
    /**
     * The mod id of the third-party mod this module integrates with.
     */
    String modId();

    /**
     * Called during mod construction, after the module has been successfully
     * instantiated.
     *
     * @param modEventBus the mod event bus
     */
    default void onModConstruction(IEventBus modEventBus) {
    }

    /**
     * Called during {@link FMLCommonSetupEvent}.
     *
     * @param event the common setup event
     */
    default void onCommonSetup(FMLCommonSetupEvent event) {
    }
}
