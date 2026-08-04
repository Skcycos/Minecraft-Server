package com.tanrunn.tcth.impl.debug;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;

import net.neoforged.neoforge.common.NeoForge;

/**
 * Lightweight, in-memory server-side debugging for cooking events.
 *
 * <p>Disabled by default and never writes to config files. When enabled via
 * {@code /tcth debug cooking on}, every {@link DishCookedEvent} is logged with
 * its event id, device, result, count, player, recipe id, quality, automated
 * flag and position. The debug listener only observes events — it never
 * participates in reward settlement.
 */
public final class CookingDebug {

    private static volatile boolean enabled = false;

    private CookingDebug() {
    }

    /**
     * Registers the debug listener on the game bus. Called once from the mod
     * constructor.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(CookingDebug::onDishCooked);
        TCTHIntegration.LOGGER.debug("[TCTH] Cooking debug listener registered (disabled by default)");
    }

    /**
     * @return whether cooking event debug logging is currently enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables cooking event debug logging (in-memory only).
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private static void onDishCooked(DishCookedEvent event) {
        if (!enabled) {
            return;
        }
        TCTHIntegration.LOGGER.info(
                "[TCTH][debug] dish event id={} device={} result={} count={} player={} recipeId={} quality={} automated={} pos={}",
                event.getEventId(),
                event.getDevice(),
                event.getResult().getItem(),
                event.getResult().getCount(),
                event.getPlayer() != null ? event.getPlayer().getGameProfile().getName() : "null",
                event.getRecipeId(),
                event.getQuality(),
                event.isAutomated(),
                event.getPosition());
    }
}
