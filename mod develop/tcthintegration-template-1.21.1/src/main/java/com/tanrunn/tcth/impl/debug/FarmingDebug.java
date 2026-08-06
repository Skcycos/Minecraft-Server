package com.tanrunn.tcth.impl.debug;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;

import net.neoforged.neoforge.common.NeoForge;

/**
 * Lightweight, in-memory server-side debugging for farming harvest events.
 *
 * <p>Disabled by default and never writes to config files. When enabled via
 * {@code /tcth debug farming on}, every {@link CropHarvestedEvent} is logged
 * with its event id, player, crop id, harvest method, fully-grown flag,
 * automated flag, dimension and position. The debug listener only observes
 * events — it never participates in reward settlement.
 */
public final class FarmingDebug {

    private static volatile boolean enabled = false;

    private FarmingDebug() {
    }

    /**
     * Registers the debug listener on the game bus. Called once from the mod
     * constructor.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(FarmingDebug::onCropHarvested);
        TCTHIntegration.LOGGER.debug("[TCTH] Farming debug listener registered (disabled by default)");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    static void onCropHarvested(CropHarvestedEvent event) {
        if (!enabled) {
            return;
        }
        TCTHIntegration.LOGGER.info(
                "[TCTH][debug] farming event id={} player={} cropId={} method={} fullyGrown={} automated={} dimension={} pos={}",
                event.getEventId(),
                event.getPlayer() != null ? event.getPlayer().getGameProfile().getName() : "null",
                event.getCropId(),
                event.getMethod(),
                event.isFullyGrown(),
                event.isAutomated(),
                event.getLevel().dimension().location(),
                event.getPosition());
    }
}
