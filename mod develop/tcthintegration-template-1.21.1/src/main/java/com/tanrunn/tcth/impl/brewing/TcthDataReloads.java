package com.tanrunn.tcth.impl.brewing;

import com.tanrunn.tcth.impl.compat.jobsplus.DishTierManager;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Central registration point for TCTH data-pack reload listeners (phase 7B.1).
 *
 * <p>Moved out of {@code CookingStatsTracker} so brewer tiers load even when
 * the cooking stats tracker is disabled. Initialization is idempotent: the
 * event is registered once from the mod constructor.
 */
public final class TcthDataReloads {

    private static boolean registered = false;

    private TcthDataReloads() {
    }

    /** Idempotent registration of all reload listeners on the game bus. */
    public static synchronized void register(net.neoforged.bus.api.IEventBus gameBus) {
        if (registered) {
            return;
        }
        registered = true;
        gameBus.addListener(TcthDataReloads::onAddReloadListeners);
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        // dish_tiers: cook tier stats (loaded even without Jobs+).
        event.addListener(new DishTierManager());
        // brewer per-item tier mapping (phase 7B.1).
        event.addListener(new BeverageTierManager());
        // shadow_loot definitions (entity loot, 8D.1 §3, 8D.3.1 §1): a NEW
        // bound listener per reload, carrying the RegistryAccess frozen for
        // THIS reload (initial startup and /reload share the exact path).
        event.addListener(new com.tanrunn.tcth.impl.shadow.ShadowLootReloadListener(
                event.getRegistryAccess()));
    }

    /** Test hook: resets the idempotency flag. */
    public static synchronized void resetForTesting() {
        registered = false;
    }
}
