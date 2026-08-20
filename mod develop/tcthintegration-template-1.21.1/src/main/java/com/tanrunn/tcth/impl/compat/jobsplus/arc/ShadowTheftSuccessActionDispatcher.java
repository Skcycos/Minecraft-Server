package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.ActionDataBuilder;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.jobsplus.ShadowSendResult;
import com.tanrunn.tcth.impl.shadow.ShadowLogThrottle;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;

import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a {@code tcth:on_shadow_theft_success} Arc action for a settled
 * shadow-theft SUCCESS event (phase 8E).
 *
 * <p>Mirror of {@link GunKillActionDispatcher} for the shadow thief
 * profession: builds an {@link ActionData} with the stable string/number
 * fields of the attempt — {@code target_kind}, {@code theft_type},
 * {@code target_type} (player targets: absent), {@code item_id} /
 * {@code item_count} (ITEM only), {@code numeric_amount} (HEALTH / HUNGER),
 * {@code effect_id} / {@code effect_duration_ticks} (EFFECT only) and
 * {@code automated} — then fires {@code sendToAction()}. Arc matches the
 * player's data-driven action holders and executes their rewards; this class
 * never touches Jobs+ internals.
 *
 * <p>A failure only affects the current event: it is logged and never breaks
 * the server tick.
 */
public final class ShadowTheftSuccessActionDispatcher {

    private ShadowTheftSuccessActionDispatcher() {
    }

    /**
     * @param player the player to target (must be an {@link ArcServerPlayer})
     * @param event  the shadow theft SUCCESS event
     * @return {@link ShadowSendResult#SUCCESS} if Arc processed the action,
     *         {@link ShadowSendResult#CLEAR_FAILURE} if a pre-send check
     *         failed (null input, wrong player type, data build failure),
     *         {@link ShadowSendResult#UNKNOWN} if {@code sendToAction}
     *         threw (XP may or may not have been granted)
     */
    public static ShadowSendResult sendShadowTheftSuccessAction(ServerPlayer player, ShadowTheftEvent event) {
        // Phase 1: pre-send validation — a failure here is a clear,
        // retryable condition (no Arc API was called).
        if (player == null || event == null) {
            return ShadowSendResult.CLEAR_FAILURE;
        }
        ArcServerPlayer arcPlayer;
        try {
            arcPlayer = (ArcServerPlayer) player;
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft send: player not ArcServerPlayer for {} ({}): {}",
                    playerName(player), event.getEventId(), e.toString());
            return ShadowSendResult.CLEAR_FAILURE;
        }
        ActionData actionData;
        try {
            actionData = buildActionData(arcPlayer, event);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft send: action data build failed for {} ({}): {}",
                    playerName(player), event.getEventId(), e.toString());
            return ShadowSendResult.CLEAR_FAILURE;
        }
        // Phase 2: sendToAction — once called, any exception means the
        // outcome is UNKNOWN (Arc may or may not have processed the XP).
        try {
            actionData.sendToAction();
            return ShadowSendResult.SUCCESS;
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft send: sendToAction failed for {} ({}): {}",
                    playerName(player), event.getEventId(), e.toString());
            return ShadowSendResult.UNKNOWN;
        }
    }

    /** Null-safe player name for logging. */
    private static String playerName(ServerPlayer player) {
        try {
            if (player == null) {
                return "?";
            }
            com.mojang.authlib.GameProfile profile = player.getGameProfile();
            return profile == null ? String.valueOf(player.getUUID()) : profile.getName();
        } catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }

    /** Builds the {@code tcth:on_shadow_theft_success} action data. Nullable
     *  fields are only written when present. */
    public static ActionData buildActionData(ArcServerPlayer player, ShadowTheftEvent event) {
        ShadowTheftReceipt receipt = event.getReceipt();
        ActionDataBuilder builder = new ActionDataBuilder(player, TcthArcRegistrar.SHADOW_THEFT_SUCCESS)
                .withData(TcthArcRegistrar.SHADOW_TARGET_KIND, event.getTargetKind().name())
                .withData(TcthArcRegistrar.SHADOW_THEFT_TYPE, event.getTheftType().name())
                .withData(TcthArcRegistrar.SHADOW_ITEM_COUNT, receipt.itemCount())
                .withData(TcthArcRegistrar.SHADOW_NUMERIC_AMOUNT, receipt.numericAmount())
                .withData(TcthArcRegistrar.SHADOW_EFFECT_DURATION_TICKS, receipt.effectDurationTicks())
                .withData(TcthArcRegistrar.AUTOMATED, event.isAutomated());
        if (event.getTargetType() != null) {
            builder = builder.withData(TcthArcRegistrar.SHADOW_TARGET_TYPE, event.getTargetType().toString());
        }
        if (receipt.itemId() != null) {
            builder = builder.withData(TcthArcRegistrar.SHADOW_ITEM_ID, receipt.itemId().toString());
        }
        if (receipt.effectId() != null) {
            builder = builder.withData(TcthArcRegistrar.SHADOW_EFFECT_ID, receipt.effectId().toString());
        }
        return builder.build();
    }
}
