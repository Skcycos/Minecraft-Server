package com.tanrunn.tcth.impl.shadow;

import java.util.UUID;
import java.util.function.Supplier;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.impl.debug.ShadowDebug;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Public interaction entry for the shadow thief framework (8C.0 + 8C.2).
 *
 * <p>Feeds the attempt coordinator and consumes its result (8C.2 §3):
 * <ul>
 *   <li>{@link ShadowTheftOutcome#FRAMEWORK_DISABLED} and
 *       {@link ShadowTheftOutcome#INVALID_CONTEXT} (master / real-transfer
 *       gates off, invalid context) — the original interaction is NOT
 *       cancelled and no feedback is sent;</li>
 *   <li>every attempt-stage outcome (PROTECTED, COOLDOWN, DUPLICATE,
 *       NO_CANDIDATE, FAILED_ROLL, TRANSFER_FAILED, AUDIT_FAILED,
 *       ROLLED_BACK, RECOVERY_REQUIRED, SUCCESS) — the interaction is
 *       cancelled so the vanilla right-click never continues, with exactly
 *       one translatable feedback per event.</li>
 * </ul>
 *
 * <p>Feedback rules (8C.2 §4): SUCCESS shows the thief the exact gains and
 * the victim the exact losses without the thief's identity; FAILED_ROLL
 * exposes the thief's name to the victim and nearby players and gives the
 * thief a short glow + slowness; NO_CANDIDATE only says "nothing to steal";
 * technical outcomes never leak stack traces or internal reasons to players.
 */
public final class PlayerInteractHandler {

    /** Low-range exposure radius in blocks (8C.2 §4). */
    static final double EXPOSURE_RANGE_BLOCKS = 12.0d;
    /** Exposure effect duration in ticks (5 s). */
    static final int EXPOSURE_EFFECT_TICKS = 100;

    private static Supplier<ShadowAttemptCoordinator> coordinatorSupplier =
            ShadowAttemptCoordinator::defaults;
    private static boolean initialized = false;

    private PlayerInteractHandler() {
    }

    /** Idempotent registration of the entity-interact listener. */
    public static void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] PlayerInteractHandler.init called more than once; ignoring");
            return;
        }
        initialized = true;
        bus.addListener(EventPriority.LOW, PlayerInteractHandler::onEntityInteract);
        TCTHIntegration.LOGGER.debug("[TCTH] PlayerInteractHandler initialized");
    }

    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        try {
            attempt(event);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft interaction handling failed: {}", e.toString());
        }
    }

    private static void attempt(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide() != LogicalSide.SERVER) {
            return;
        }
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer thief)) {
            return;
        }
        if (thief instanceof FakePlayer) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!thief.isShiftKeyDown() && !thief.isDiscrete()) {
            return;
        }
        if (!thief.getMainHandItem().isEmpty() || !thief.getOffhandItem().isEmpty()) {
            return;
        }
        if (!(event.getTarget() instanceof ServerPlayer victim)) {
            return;
        }
        if (victim == thief || victim instanceof FakePlayer) {
            return;
        }
        if (!victim.isAlive() || victim.isDeadOrDying()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || level.isClientSide()) {
            return;
        }
        if (victim.level() != level) {
            return;
        }
        if (!thief.canInteractWithEntity(victim.getBoundingBox(), thief.entityInteractionRange())) {
            return;
        }
        boolean hasLineOfSight;
        try {
            hasLineOfSight = thief.hasLineOfSight(victim);
        } catch (RuntimeException | LinkageError e) {
            hasLineOfSight = false;
        }
        ShadowAttemptContext context = new ShadowAttemptContext(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, victim.getUUID(), null,
                level, victim.blockPosition().immutable(), level.getGameTime(), false,
                thief.distanceTo(victim), hasLineOfSight);
        ShadowAttemptCoordinator.Result result = coordinatorSupplier.get().attempt(context);
        if (ShadowDebug.isEnabled()) {
            TCTHIntegration.LOGGER.info("[TCTH][SHADOW] event={} outcome={} reason={}",
                    result.eventId(), result.outcome(),
                    result.failureReason() != null ? result.failureReason() : "-");
        }
        consume(event, context, result);
    }

    /**
     * Consumes the coordinator result: cancels the interaction for every
     * attempt-stage outcome and sends exactly one translatable feedback.
     */
    private static void consume(PlayerInteractEvent.EntityInteract event, ShadowAttemptContext context,
                                ShadowAttemptCoordinator.Result result) {
        ShadowTheftOutcome outcome = result.outcome();
        if (outcome == ShadowTheftOutcome.FRAMEWORK_DISABLED
                || outcome == ShadowTheftOutcome.INVALID_CONTEXT) {
            return; // gates off or invalid context: no cancel, no feedback
        }
        // The attempt formally entered the theft flow: cancel the vanilla
        // interaction so it never continues (8C.2 §3).
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        feedback(context, result);
    }

    private static void feedback(ShadowAttemptContext context, ShadowAttemptCoordinator.Result result) {
        ServerPlayer thief = context.thief();
        ServerPlayer victim = resolveVictim(context);
        switch (result.outcome()) {
            case SUCCESS -> {
                if (victim != null) {
                    victim.sendSystemMessage(successMessageForVictim(result.theftType(), result.receipt()));
                }
                thief.sendSystemMessage(successMessageForThief(result.theftType(), result.receipt()));
            }
            case FAILED_ROLL -> {
                if (victim != null) {
                    victim.sendSystemMessage(Component.translatable(
                            "tcth.shadow.feedback.fail.victim", thief.getDisplayName()));
                }
                thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.fail.self"));
                exposeThief(thief);
                exposeNearby(thief, victim, context.level());
            }
            case NO_CANDIDATE ->
                    thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.no_candidate"));
            case PROTECTED ->
                    thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.protected"));
            case COOLDOWN ->
                    thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.cooldown"));
            case TRANSFER_FAILED ->
                    thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.transfer_failed"));
            case AUDIT_FAILED, ROLLED_BACK, RECOVERY_REQUIRED ->
                    // Technical outcomes: a generic line only — never stack
                    // traces or internal reasons for ordinary players.
                    thief.sendSystemMessage(Component.translatable("tcth.shadow.feedback.technical_error"));
            case DUPLICATE -> {
                // The duplicate interaction is cancelled by consume() but is
                // SILENT: no second message for a repeated attempt.
            }
            case FRAMEWORK_DISABLED, INVALID_CONTEXT -> {
                // unreachable (handled before cancelling)
            }
        }
    }

    /** The SUCCESS feedback branches strictly by the drawn theft type
     *  (8C.2.1 §3) — never by guessing from receipt fields. */
    private static Component successMessageForThief(ShadowTheftType type, ShadowTheftReceipt receipt) {
        if (type == null || receipt.isEmpty()) {
            return Component.translatable("tcth.shadow.feedback.technical_error");
        }
        return switch (type) {
            case ITEM -> Component.translatable("tcth.shadow.feedback.success.self.item",
                    receipt.itemCount(), itemName(receipt.itemId()));
            case HEALTH -> Component.translatable("tcth.shadow.feedback.success.self.health",
                    formatAmount(receipt.numericAmount()));
            case HUNGER -> Component.translatable("tcth.shadow.feedback.success.self.hunger",
                    formatAmount(receipt.numericAmount()));
            case EFFECT -> Component.translatable("tcth.shadow.feedback.success.self.effect",
                    receipt.effectDurationTicks() / 20, effectName(receipt.effectId()));
            case COIN -> Component.translatable("tcth.shadow.feedback.technical_error");
        };
    }

    private static Component successMessageForVictim(ShadowTheftType type, ShadowTheftReceipt receipt) {
        if (type == null || receipt.isEmpty()) {
            return Component.translatable("tcth.shadow.feedback.technical_error");
        }
        return switch (type) {
            case ITEM -> Component.translatable("tcth.shadow.feedback.success.victim.item",
                    receipt.itemCount(), itemName(receipt.itemId()));
            case HEALTH -> Component.translatable("tcth.shadow.feedback.success.victim.health",
                    formatAmount(receipt.numericAmount()));
            case HUNGER -> Component.translatable("tcth.shadow.feedback.success.victim.hunger",
                    formatAmount(receipt.numericAmount()));
            case EFFECT -> Component.translatable("tcth.shadow.feedback.success.victim.effect",
                    receipt.effectDurationTicks() / 20, effectName(receipt.effectId()));
            case COIN -> Component.translatable("tcth.shadow.feedback.technical_error");
        };
    }

    /** Locale.ROOT formatting: never locale-dependent separators (8C.2.1 §3). */
    private static String formatAmount(double amount) {
        return String.format(java.util.Locale.ROOT, "%.1f", amount);
    }

    /** The loser (thief) of a failed roll gets a short glow + slowness. */
    private static void exposeThief(ServerPlayer thief) {
        try {
            thief.addEffect(new MobEffectInstance(MobEffects.GLOWING, EXPOSURE_EFFECT_TICKS, 0,
                    false, true, true));
            thief.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, EXPOSURE_EFFECT_TICKS, 0,
                    false, true, true));
        } catch (RuntimeException | LinkageError e) {
            // cosmetic only; never break the tick
        }
    }

    /** Nearby players receive a low-range exposure notice; the victim is
     *  excluded — they already received the direct fail message once
     *  (8C.2.1 §4). */
    private static void exposeNearby(ServerPlayer thief, @org.jetbrains.annotations.Nullable ServerPlayer victim,
                                     ServerLevel level) {
        double rangeSq = EXPOSURE_RANGE_BLOCKS * EXPOSURE_RANGE_BLOCKS;
        for (ServerPlayer other : level.players()) {
            if (other == thief || other == victim) {
                continue;
            }
            if (thief.distanceToSqr(other) <= rangeSq) {
                other.sendSystemMessage(Component.translatable(
                        "tcth.shadow.feedback.expose.nearby", thief.getDisplayName()));
            }
        }
    }

    private static Component itemName(net.minecraft.resources.ResourceLocation itemId) {
        return Component.literal(String.valueOf(itemId));
    }

    private static Component effectName(net.minecraft.resources.ResourceLocation effectId) {
        return Component.translatable(effectId.toLanguageKey("effect"));
    }

    @org.jetbrains.annotations.Nullable
    private static ServerPlayer resolveVictim(ShadowAttemptContext context) {
        Entity entity = context.level().getEntity(context.targetId());
        if (entity instanceof ServerPlayer player) {
            return player;
        }
        net.minecraft.world.entity.player.Player player = context.level().getPlayerByUUID(context.targetId());
        return player instanceof ServerPlayer sp ? sp : null;
    }

    // ---- test hooks (not part of the public API) ----

    static void setCoordinatorSupplierForTesting(Supplier<ShadowAttemptCoordinator> supplier) {
        coordinatorSupplier = supplier;
    }

    static void resetForTesting() {
        initialized = false;
        coordinatorSupplier = ShadowAttemptCoordinator::defaults;
    }
}
