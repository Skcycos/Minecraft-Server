package com.tanrunn.tcth.impl.compat.jobsplus;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Conditional compat module for Jobs+ (and its mandatory dependency Arc).
 *
 * <p>Registered as a lazy descriptor ({@code jobsplus} +
 * {@code com.tanrunn.tcth.impl.compat.jobsplus.JobsPlusCompatModule}); the
 * implementation class is only loaded when Jobs+ is installed. The module
 * additionally verifies that Arc is present and disables itself clearly
 * otherwise.
 *
 * <p>On load it registers the {@code tcth:on_dish_cooked} Arc action type and
 * starts the reward settlement module. Rewards stay disabled by default
 * ({@code Config.JOBS_PLUS_REWARDS_ENABLED = false}).
 */
public final class JobsPlusCompatModule implements CompatModule {

    private boolean arcAvailable = false;

    public JobsPlusCompatModule() {
    }

    @Override
    public String modId() {
        return "jobsplus";
    }

    @Override
    public void onModConstruction(IEventBus modEventBus) {
        if (!CompatLoader.isModLoaded("arc")) {
            TCTHIntegration.LOGGER.warn("[TCTH] Jobs+ is installed but Arc is missing; dish reward module disabled");
            return;
        }
        arcAvailable = true;
        // Loads TcthArcRegistrar, registering tcth:on_dish_cooked, the dish
        // action data types and the four condition types into Arc's registries.
        TcthArcRegistrar.DISH_COOKED.getLocation();
        TcthArcRegistrar.verifyRegistrations();
        JobsPlusRewardModule.init(NeoForge.EVENT_BUS);
        TCTHIntegration.LOGGER.info("[TCTH] Jobs+ dish reward module active (rewards disabled by default)");
    }

    boolean isArcAvailableForTesting() {
        return arcAvailable;
    }
}
