package com.tanrunn.tcth.impl.compat.brewer.arc;

import org.jetbrains.annotations.Nullable;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.ActionDataBuilder;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.action.result.ActionResult;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a "beverage prepared" Arc action for a settled beverage event
 * (phase 7C).
 *
 * <p>This is the bridge from TCTH beverage events into the Arc action system:
 * it builds an {@link ActionData} for {@code tcth:on_beverage_prepared} with
 * stable string/number data and fires {@code sendToAction()}. Arc then matches
 * the player's action holders (data-driven) and executes their rewards. This
 * class never calls Jobs+ internals directly.
 *
 * <p>A failure only affects the current event: it is logged with the player,
 * item and tier, and never breaks the server tick.
 */
public final class BeverageActionDispatcher {

    private BeverageActionDispatcher() {
    }

    /**
     * @param player the player to target
     * @param event  the beverage event
     * @param tier   the resolved beverage tier (never null)
     * @return the Arc action result, or {@code null} if sending failed
     */
    @Nullable
    public static ActionResult sendBeverageAction(ServerPlayer player, BeveragePreparedEvent event,
                                                  com.tanrunn.tcth.api.brewing.BeverageTier tier) {
        try {
            ArcServerPlayer arcPlayer = (ArcServerPlayer) player;
            return buildActionData(arcPlayer, event, tier).sendToAction();
        } catch (RuntimeException e) {
            String playerName = player.getGameProfile() != null ? player.getGameProfile().getName() : "?";
            TCTHIntegration.LOGGER.warn("[TCTH] Failed to send beverage action for {} (item {}, tier {}): {}",
                    playerName,
                    event.getResult().getItem(),
                    tier,
                    e.toString());
            return null;
        }
    }

    /**
     * Builds the {@code tcth:on_beverage_prepared} action data. Exposed
     * package-private for tests (does not require a live Arc environment).
     */
    static ActionData buildActionData(com.daqem.arc.api.player.ArcPlayer arcPlayer,
                                      BeveragePreparedEvent event,
                                      com.tanrunn.tcth.api.brewing.BeverageTier tier) {
        ActionDataBuilder builder = new ActionDataBuilder(arcPlayer, BrewerArcRegistrar.ON_BEVERAGE_PREPARED)
                .withData(ActionDataType.ITEM_STACK, event.getResult().copy())
                .withData(ActionDataType.ITEM, event.getResult().getItem())
                .withData(TcthArcRegistrar.RESULT_ITEM_ID,
                        BuiltInRegistries.ITEM.getKey(event.getResult().getItem()).toString())
                .withData(TcthArcRegistrar.COUNT, event.getResult().getCount())
                .withData(TcthArcRegistrar.DEVICE, event.getDevice().name())
                .withData(TcthArcRegistrar.TIER, tier.name())
                .withData(TcthArcRegistrar.AUTOMATED, event.isAutomated());
        if (event.getRecipeId() != null) {
            builder.withData(TcthArcRegistrar.RECIPE_ID, event.getRecipeId().toString());
        }
        return builder.build();
    }
}
