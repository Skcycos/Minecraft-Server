package com.tanrunn.tcth.impl.compat.jobsplus;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown;

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
        // action data types, the four dish condition types, the phase-3D
        // ability-tree condition types and the tcth:tasting_effects reward
        // type into Arc's registries.
        TcthArcRegistrar.DISH_COOKED.getLocation();
        TcthArcRegistrar.verifyRegistrations();
        JobsPlusRewardModule.init(NeoForge.EVENT_BUS);
        FarmerRewardModule.init(NeoForge.EVENT_BUS);
        // Tasting anti-farm cooldown lifecycle (logout/stop cleanup). The
        // cooldown itself is committed by the tcth:tasting_effects reward.
        ChefTastingCooldown.instance().registerLifecycle(NeoForge.EVENT_BUS);
        TCTHIntegration.LOGGER.info("[TCTH] Jobs+ dish reward module active (rewards disabled by default)");
        TCTHIntegration.LOGGER.info("[TCTH] Farmer crop-harvest reward module active (rewards disabled by default)");
        TCTHIntegration.LOGGER.info("[TCTH] Chef ability tree active (knife / hearth / tasting / study routes)");
    }

    boolean isArcAvailableForTesting() {
        return arcAvailable;
    }
}
