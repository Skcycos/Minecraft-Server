package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import org.jetbrains.annotations.Nullable;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.ActionDataBuilder;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.action.result.ActionResult;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;

import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a {@code tcth:on_gun_kill} Arc action for a settled gun-kill event.
 *
 * <p>Mirror of {@link DishActionDispatcher} for the gunner profession: builds
 * an {@link ActionData} with the Arc-native {@code ITEM_STACK}, {@code ITEM}
 * data plus the TCTH {@code weapon_id}, {@code target_id}, {@code target_tier},
 * {@code distance} and {@code automated} fields, then fires
 * {@code sendToAction()}. Arc matches the player's data-driven action holders
 * and executes their rewards; this class never touches Jobs+ internals.
 *
 * <p>A failure only affects the current event: it is logged and never breaks
 * the server tick.
 */
public final class GunKillActionDispatcher {

    private GunKillActionDispatcher() {
    }

    /**
     * @param player the player to target (must be an {@link ArcServerPlayer})
     * @param event  the gun-kill event
     * @return the Arc action result, or {@code null} if sending failed
     */
    @Nullable
    public static ActionResult sendGunKillAction(ServerPlayer player, GunKillEvent event) {
        try {
            ArcServerPlayer arcPlayer = (ArcServerPlayer) player;
            return buildActionData(arcPlayer, event).sendToAction();
        } catch (Exception e) {
            TCTHIntegration.LOGGER.error("[TCTH] Failed to send gun-kill action for {} ({}): {}",
                    player.getGameProfile().getName(), event.getWeaponId(), e.toString());
            return null;
        }
    }

    /** Builds the {@code tcth:on_gun_kill} action data. */
    public static ActionData buildActionData(ArcServerPlayer player, GunKillEvent event) {
        // Arc's native arc:items condition reads ActionDataType.ITEM /
        // ITEM_STACK, so both are provided (defensive copy of the stack).
        ActionDataBuilder builder = new ActionDataBuilder(player, TcthArcRegistrar.GUN_KILL)
                .withData(ActionDataType.ITEM_STACK, event.getWeapon().copy())
                .withData(ActionDataType.ITEM, event.getWeapon().getItem())
                .withData(TcthArcRegistrar.WEAPON_ID, event.getWeaponId().toString())
                .withData(TcthArcRegistrar.TARGET_ID, event.getTargetId().toString())
                .withData(TcthArcRegistrar.TARGET_TIER, event.getTargetTier().name())
                .withData(TcthArcRegistrar.GUN_KILL_DISTANCE, event.getDistance())
                .withData(TcthArcRegistrar.AUTOMATED, event.isAutomated());
        return builder.build();
    }
}
