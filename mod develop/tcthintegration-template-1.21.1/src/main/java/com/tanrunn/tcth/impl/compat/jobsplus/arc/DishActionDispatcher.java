package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import org.jetbrains.annotations.Nullable;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.ActionDataBuilder;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.action.result.ActionResult;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a "dish cooked" Arc action for a settled dish event.
 *
 * <p>This is the bridge from TCTH events into the Arc action system: it builds
 * an {@link ActionData} for {@code tcth:on_dish_cooked} with stable
 * string/number data and fires {@code sendToAction()}. Arc then matches the
 * player's action holders (data-driven) and executes their rewards
 * (e.g. {@code jobsplus:job_exp}) — this class never calls Jobs+ internals and
 * never constructs {@code JobExpReward} directly, so other professions or
 * server packs can also listen to {@code tcth:on_dish_cooked}.
 *
 * <p>A failure only affects the current event: it is logged with the player,
 * item and tier, and never breaks the server tick.
 */
public final class DishActionDispatcher {

    private DishActionDispatcher() {
    }

    /**
     * @param player the player to target
     * @param event  the dish event
     * @param tier   the resolved dish tier (never null)
     * @return the Arc action result, or {@code null} if sending failed
     */
    @Nullable
    public static ActionResult sendDishAction(ServerPlayer player, DishCookedEvent event, DishTier tier) {
        try {
            ArcServerPlayer arcPlayer = (ArcServerPlayer) player;
            return buildActionData(arcPlayer, event, tier).sendToAction();
        } catch (RuntimeException e) {
            String playerName = player.getGameProfile() != null ? player.getGameProfile().getName() : "?";
            TCTHIntegration.LOGGER.warn("[TCTH] Failed to send dish action for {} (item {}, tier {}): {}",
                    playerName,
                    event.getResult().getItem(),
                    tier,
                    e.toString());
            return null;
        }
    }

    /**
     * Builds the {@code tcth:on_dish_cooked} action data. Exposed
     * package-private for tests (does not require a live Arc/mixin
     * environment).
     */
    static ActionData buildActionData(com.daqem.arc.api.player.ArcPlayer arcPlayer,
                                      DishCookedEvent event, DishTier tier) {
        // Arc's native arc:items condition reads ActionDataType.ITEM /
        // ITEM_STACK, so both are provided (defensive copy of the stack).
        ActionDataBuilder builder = new ActionDataBuilder(arcPlayer, TcthArcRegistrar.DISH_COOKED)
                .withData(ActionDataType.ITEM_STACK, event.getResult().copy())
                .withData(ActionDataType.ITEM, event.getResult().getItem())
                .withData(TcthArcRegistrar.RESULT_ITEM_ID,
                        BuiltInRegistries.ITEM.getKey(event.getResult().getItem()).toString())
                .withData(TcthArcRegistrar.COUNT, event.getResult().getCount())
                .withData(TcthArcRegistrar.DEVICE, event.getDevice().name())
                .withData(TcthArcRegistrar.QUALITY, event.getQuality().name())
                .withData(TcthArcRegistrar.TIER, tier.name())
                .withData(TcthArcRegistrar.AUTOMATED, event.isAutomated());
        if (event.getRecipeId() != null) {
            builder.withData(TcthArcRegistrar.RECIPE_ID, event.getRecipeId().toString());
        }
        return builder.build();
    }
}
