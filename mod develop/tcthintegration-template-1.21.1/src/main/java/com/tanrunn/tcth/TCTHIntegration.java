package com.tanrunn.tcth;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.debug.CookingDebug;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

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

        // Bootstrap the conditional compat module loader.
        // Modules themselves are registered in later phases (see com.tanrunn.tcth.impl.compat).
        CompatLoader.init(modEventBus);

        // Bootstrap the unified cooking-event dispatcher (central guard).
        DishCookedEventDispatcher.init();

        // Register the cooking-event debug listener (disabled by default).
        CookingDebug.init();
    }
}
