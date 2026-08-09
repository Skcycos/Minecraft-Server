package com.tanrunn.tcth.impl.debug;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;

import net.neoforged.neoforge.common.NeoForge;

/**
 * Lightweight, in-memory server-side debugging for beverage events (phase 7B).
 *
 * <p>Disabled by default and never writes to config files. When enabled via
 * {@code /tcth debug brewing on}, every {@link BeveragePreparedEvent} is
 * logged with its event id, device, result, count, player, recipe id, tier,
 * automated flag and position. The debug listener only observes events — it
 * never participates in reward settlement.
 */
public final class BrewingDebug {

    private static volatile boolean enabled = false;

    private BrewingDebug() {
    }

    /** Registers the debug listener on the game bus (called once at mod init). */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(BrewingDebug::onBeveragePrepared);
        TCTHIntegration.LOGGER.debug("[TCTH] Brewing debug listener registered (disabled by default)");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Enables or disables beverage event debug logging (in-memory only). */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private static void onBeveragePrepared(BeveragePreparedEvent event) {
        if (!enabled) {
            return;
        }
        TCTHIntegration.LOGGER.info(
                "[TCTH][debug] beverage event id={} device={} result={} count={} player={} recipeId={} tier={} automated={} pos={}",
                event.getEventId(),
                event.getDevice(),
                event.getResult().getItem(),
                event.getResult().getCount(),
                event.getPlayer() != null ? event.getPlayer().getGameProfile().getName() : "null",
                event.getRecipeId(),
                event.getTier(),
                event.isAutomated(),
                event.getPosition());
    }
}
