package com.tanrunn.tcth;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.debug.CookingDebug;
import com.tanrunn.tcth.impl.debug.FarmingDebug;
import com.tanrunn.tcth.impl.detector.farming.CropBreakDetector;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;
import com.tanrunn.tcth.impl.event.GunKillEventDispatcher;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.stats.CookingStatsTracker;
import com.tanrunn.tcth.impl.stats.GunnerStatsTracker;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(TCTHIntegration.MODID)
public class TCTHIntegration {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "tcth";
    // Directly reference a slf4j logger. All TCTH logging must go through this
    // logger and use the "[TCTH] " prefix with an appropriate log level.
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public TCTHIntegration(IEventBus modEventBus, ModContainer modContainer) {
        // Register our mod's ModConfigSpec so that FML can create and load the config file for us.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register the tcth:cooking_signature data component (persistent +
        // network synchronized).
        CookingSignatureComponents.DATA_COMPONENTS.register(modEventBus);

        // Register the conditional Jobs+ module descriptor BEFORE init.
        // The implementation class is only loaded when Jobs+ is installed.
        CompatLoader.register("jobsplus",
                "com.tanrunn.tcth.impl.compat.jobsplus.JobsPlusCompatModule");

        // Field Guide cookbook unlock module (optional; loaded only when the
        // Field Guide mod is installed).
        CompatLoader.register("fieldguide",
                "com.tanrunn.tcth.impl.compat.fieldguide.FieldGuideCompatModule");

        // Scorched Guns firearm-kill compat module (optional; loaded only when
        // Scorched Guns is installed). The implementation class is only loaded
        // when scguns is present.
        CompatLoader.register("scguns",
                "com.tanrunn.tcth.impl.compat.scguns.ScorchedGunsCompatModule");

        // Bootstrap the conditional compat module loader.
        // Modules themselves are registered in later phases (see com.tanrunn.tcth.impl.compat).
        CompatLoader.init(modEventBus);

        // Bootstrap the unified cooking-event dispatcher (central guard).
        DishCookedEventDispatcher.init();

        // Register the cooking-event debug listener (disabled by default).
        CookingDebug.init();

        // Unified farming framework (phase 4A.2): break detector + right-click
        // harvest mixins publish CropHarvestedEvent through the dispatcher.
        CropHarvestedEventDispatcher.init(NeoForge.EVENT_BUS);
        CropBreakDetector.init(NeoForge.EVENT_BUS);
        FarmingDebug.init();

        // Per-player cooking statistics archive (independent of Jobs+/Arc).
        CookingStatsTracker.init(NeoForge.EVENT_BUS);

        // Phase 5A: gunner profession — firearm-kill event dispatcher,
        // statistics tracker and Scorched Guns compat module registration.
        // The Scorched Guns module is a CompatModule; its init() is called
        // from the compat module's onModConstruction hook.
        GunKillEventDispatcher.init(NeoForge.EVENT_BUS);
        GunnerStatsTracker.init(NeoForge.EVENT_BUS);
    }
}
