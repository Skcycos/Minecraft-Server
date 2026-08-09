package com.tanrunn.tcth.impl.compat.jobsplus;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
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
        GunnerRewardModule.init(NeoForge.EVENT_BUS);
        // Phase 7C: brewer (Mystic Brewer) Arc action/conditions + reward
        // module. Lives under the jobsplus mod id; only loads when Arc is
        // present (guaranteed here). Rewards disabled by default.
        com.tanrunn.tcth.impl.compat.brewer.arc.BrewerArcRegistrar.ON_BEVERAGE_PREPARED.getLocation();
        com.tanrunn.tcth.impl.compat.brewer.arc.BrewerArcRegistrar.verifyRegistrations();
        com.tanrunn.tcth.impl.compat.brewer.BrewerRewardModule.init(NeoForge.EVENT_BUS);
        // Phase 5B: gunner ability routes. The pure Jobs+ tier-query / study
        // route is registered unconditionally (Jobs+ is present here). The
        // SG-dependent routes (marksmanship damage, battlefield defense, ammo
        // saver) are only registered when Scorched Guns is actually loaded, so
        // Jobs+/Arc without SG never resolves SgDamageEvidence. The ammo-saver
        // mixin config additionally requires jobsplus, keeping every path
        // dependency-explicit (no NoClassDefFoundError capture).
        if (ModList.get().isLoaded("scguns")) {
            com.tanrunn.tcth.impl.compat.jobsplus.powerup.GunnerAbilityModule.init(NeoForge.EVENT_BUS);
        } else {
            TCTHIntegration.LOGGER.info(
                    "[TCTH] Gunner SG-dependent ability routes (marksmanship / defense / ammo) skipped: Scorched Guns not loaded");
        }
        // Tasting anti-farm cooldown lifecycle (logout/stop cleanup). The
        // cooldown itself is committed by the tcth:tasting_effects reward.
        ChefTastingCooldown.instance().registerLifecycle(NeoForge.EVENT_BUS);
        // Phase 7E: brewer (Mystic Brewer) ability routes. The tier query and
        // the Java-driven routes (brewing effects on BeveragePreparedEvent,
        // resistance on LivingDamageEvent) plus the tasting cooldown lifecycle
        // register unconditionally here (Jobs+ is present). The study route is
        // data-driven via Arc (on_job_exp + job_exp_multiplier); its conditions
        // are registered by TcthArcRegistrar above.
        com.tanrunn.tcth.impl.compat.jobsplus.powerup.BrewerAbilityModule.init(NeoForge.EVENT_BUS);
        TCTHIntegration.LOGGER.info("[TCTH] Jobs+ dish reward module active (rewards disabled by default)");
        TCTHIntegration.LOGGER.info("[TCTH] Farmer crop-harvest reward module active (rewards disabled by default)");
        TCTHIntegration.LOGGER.info("[TCTH] Gunner firearm-kill reward module active (rewards disabled by default)");
        TCTHIntegration.LOGGER.info("[TCTH] Chef ability tree active (knife / hearth / tasting / study routes)");
        TCTHIntegration.LOGGER.info("[TCTH] Gunner ability tree active (marksmanship / ammo / defense / study routes)");
        TCTHIntegration.LOGGER.info("[TCTH] Brewer ability tree active (brewing / tasting / resistance / study routes)");
    }

    boolean isArcAvailableForTesting() {
        return arcAvailable;
    }
}
