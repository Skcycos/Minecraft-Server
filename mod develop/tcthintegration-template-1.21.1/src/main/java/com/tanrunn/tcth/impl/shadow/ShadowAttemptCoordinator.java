package com.tanrunn.tcth.impl.shadow;

import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.debug.ShadowDebug;
import com.tanrunn.tcth.impl.shadow.protection.OpacProtectionProviderFactory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The shadow theft attempt state machine (8B.1 revision).
 *
 * <p>Strict order:
 * <pre>
 *  1. master gates (Config.ENABLED + integration + playerTheft + real
 *     transfers; every read fails closed to FALSE)
 *                              → FRAMEWORK_DISABLED        (no record / event)
 *  2. context validation       → INVALID_CONTEXT           (no record / event)
 *  3. real player / FakePlayer → INVALID_CONTEXT           (no record / event)
 *  4. audit gate (enabled + store available, null-safe)   (no record / event)
 *                              → AUDIT_FAILED              (before ANY provider /
 *                                                            random / executor call)
 *  5. UTC day capture — AFTER every functional gate and context check; a
 *     failing date supplier prunes ITEM only, never blocks other types
 *  6. idempotency (in-memory eventId FIRST, then durable eventId, then
 *     thief+target+serverTick) → RECOVERY_REQUIRED once per JVM session if a
 *                                PENDING record exists (in-memory keys settled
 *                                so repeats are DUPLICATEs with zero events)
 *                              → DUPLICATE                 (no record / event)
 *  7. protection service       → PROTECTED                 (audited attempt)
 *  8. cooldowns                → COOLDOWN                  (audited attempt)
 *  9. candidate pool (provider + COIN block + daily-ITEM pruning)
 * 10. empty pool               → NO_CANDIDATE              (audited attempt)
 * 11. type draw, exactly once
 * 12. success chance (LOS-aware facts)
 * 13. success roll, exactly once
 * 14. roll failed              → FAILED_ROLL               (audited attempt)
 * 15. executor.prepare (read-only) → TRANSFER_FAILED       (audited attempt)
 * 16. pre-write audit (PENDING)   → AUDIT_FAILED           (commit is NEVER called)
 * 17. pre-commit drift re-validation → TRANSFER_FAILED     (audited attempt)
 * 18. daily ITEM quota reserve (8C.2.2) → TRANSFER_FAILED  (audited attempt,
 *        "daily_item_limit"; a RESERVED slot is occupied immediately)
 * 19. executor.commit          → TRANSFER_FAILED / RECOVERY_REQUIRED
 * 20. receipt integrity        → rollback exactly once (8B.1.1 §1):
 *        rollback ok           → release + ROLLED_BACK     (never plain TRANSFER_FAILED)
 *        rollback failed       → RECOVERY_REQUIRED         (quota kept)
 * 21. quota commit (ITEM only) — commitReservation must succeed:
 *        ok                    → continue
 *        false / exception     → rollback exactly once:
 *          rollback ok         → release + ROLLED_BACK
 *          rollback failed     → RECOVERY_REQUIRED         (quota kept)
 * 22. final audit write        → SUCCESS (COMMITTED quota)
 * 23. final write failed       → rollback once:
 *        rollback ok           → release (committed quota too) + ROLLED_BACK
 *        rollback failed       → RECOVERY_REQUIRED         (committed receipt, quota kept)
 * 24. commit cooldowns / victim protection / idempotency
 * </pre>
 *
 * <p>Invariants (8B.1 + 8B.1.1):
 * <ul>
 *   <li>{@code eventId} is generated once at the start of the attempt and
 *       shared by the audit record, the event and the result;</li>
 *   <li>audit disabled, unavailable or null refuses the attempt <em>before</em>
 *       any provider, random or executor call;</li>
 *   <li>the transfer is a two-phase transaction (prepare → commit →
 *       rollback); the commit is only executed after a PENDING pre-write
 *       record exists;</li>
 *   <li>a committed transfer whose receipt does not match the drawn type is
 *       <em>never</em> reported as a plain TRANSFER_FAILED — rollback runs
 *       exactly once, yielding ROLLED_BACK or RECOVERY_REQUIRED;</li>
 *   <li>a {@code SUCCESS} is only ever posted after the final audit write
 *       succeeded — a failed final write triggers exactly one rollback and
 *       yields {@code ROLLED_BACK} (rollback ok) or {@code RECOVERY_REQUIRED}
 *       (rollback failed, committed receipt reported, never a fake
 *       SUCCESS/TRANSFER_FAILED);</li>
 *   <li>every audited outcome settles the idempotency keys (eventId +
 *       thief+target+serverTick attempt key); duplicates never write new
 *       records and never post new events;</li>
 *   <li>the coordinator never mutates player property itself; all property
 *       mutations happen inside executors;</li>
 *   <li>single-attempt exception isolation: a failure in any dependency is
 *       caught, logged (throttled) and mapped to a defined outcome — the
 *       server tick never breaks;</li>
 *   <li>COIN is hard-blocked: its transfer cannot be made atomic yet, so the
 *       type is pruned from every pool before the draw.</li>
 * </ul>
 */
public final class ShadowAttemptCoordinator {

    /** Constant: COIN remains blocked until an atomic transfer exists. */
    public static final String COIN_BLOCKED_REASON = "coin_transfer_not_atomic";

    private final Supplier<ShadowFrameworkSettings> settingsSupplier;
    private final ShadowCandidateProvider candidateProvider;
    private final ShadowTransferExecutor transferExecutor;
    private final ShadowProtectionService protectionService;
    private final ShadowCooldownTracker cooldownTracker;
    private final ShadowIdempotencyTracker idempotencyTracker;
    private final Function<ServerLevel, ShadowAuditWriter> auditStoreFactory;
    private final Function<ServerLevel, ShadowDailyLimitWriter> dailyLimitStoreFactory;
    private final Supplier<RandomSource> randomSourceSupplier;
    private final Supplier<Long> epochMillisSupplier;
    private final java.util.function.Supplier<String> dailyDateSupplier;

    /**
     * The immutable result of an attempt.
     *
     * @param outcome       the final outcome
     * @param eventId       the attempt id (generated once per attempt)
     * @param eventPosted   whether the final event was posted to the game bus
     * @param receipt       the committed receipt on {@code SUCCESS}/{@code
     *                      RECOVERY_REQUIRED}, empty otherwise
     * @param failureReason a machine-readable reason for failed attempts
     */
    public record Result(ShadowTheftOutcome outcome, java.util.UUID eventId, boolean eventPosted,
                         ShadowTheftReceipt receipt, @Nullable String failureReason,
                         @Nullable ShadowTheftType theftType) {
        public Result {
            java.util.Objects.requireNonNull(outcome, "outcome");
            java.util.Objects.requireNonNull(eventId, "eventId");
            java.util.Objects.requireNonNull(receipt, "receipt");
            if (outcome == ShadowTheftOutcome.SUCCESS
                    && (theftType == null || !receipt.matches(theftType))) {
                throw new IllegalArgumentException(
                        "SUCCESS requires a theftType matching the receipt");
            }
        }
    }

    public ShadowAttemptCoordinator(Supplier<ShadowFrameworkSettings> settingsSupplier,
                                    ShadowCandidateProvider candidateProvider,
                                    ShadowTransferExecutor transferExecutor,
                                    ShadowProtectionService protectionService,
                                    ShadowCooldownTracker cooldownTracker,
                                    ShadowIdempotencyTracker idempotencyTracker,
                                    Function<ServerLevel, ShadowAuditWriter> auditStoreFactory,
                                    Function<ServerLevel, ShadowDailyLimitWriter> dailyLimitStoreFactory,
                                    Supplier<RandomSource> randomSourceSupplier,
                                    Supplier<Long> epochMillisSupplier,
                                    java.util.function.Supplier<String> dailyDateSupplier) {
        this.settingsSupplier = settingsSupplier;
        this.candidateProvider = candidateProvider;
        this.transferExecutor = transferExecutor;
        this.protectionService = protectionService;
        this.cooldownTracker = cooldownTracker;
        this.idempotencyTracker = idempotencyTracker;
        this.auditStoreFactory = auditStoreFactory;
        this.dailyLimitStoreFactory = dailyLimitStoreFactory;
        this.randomSourceSupplier = randomSourceSupplier;
        this.epochMillisSupplier = epochMillisSupplier;
        this.dailyDateSupplier = dailyDateSupplier;
    }

    /**
     * @return a coordinator wired with the production defaults: the read-only
     *         player candidate provider, the composite protection service
     *         (OPAC area provider when present, spawn protection, new-player
     *         protection), the <strong>real</strong> transfer engine
     *         ({@link PlayerAssetTransferExecutor}), the real audit and
     *         daily-limit SavedData and the shared trackers. The combined
     *         master gates (Config.ENABLED + integration + playerTheft +
     *         real-asset transfers, every read fail-closed to FALSE) keep it
     *         asset-neutral until an operator enables all four switches.
     */
    public static ShadowAttemptCoordinator defaults() {
        return new ShadowAttemptCoordinator(
                ShadowFrameworkSettings::defaults,
                PlayerReadonlyCandidateProvider.INSTANCE,
                // The real engine is wired in, but the master gate
                // (shadowRealAssetTransfersEnabled, default false) refuses
                // every attempt BEFORE any provider/random/executor call —
                // production stays asset-neutral until an operator enables it.
                PlayerAssetTransferExecutor.INSTANCE,
                new ShadowCompositeProtectionService(
                        OpacProtectionProviderFactory.create(),
                        ShadowAttemptCoordinator::newPlayerProtectionTicks),
                ShadowCooldownTracker.SHARED,
                ShadowIdempotencyTracker.SHARED,
                level -> ShadowAuditStore.current(level),
                level -> ShadowDailyLimitStore.current(level),
                RandomSource::create,
                System::currentTimeMillis,
                ShadowDailyLimitStore::today);
    }

    static long newPlayerProtectionTicks() {
        try {
            return com.tanrunn.tcth.Config.SHADOW_NEW_PLAYER_PROTECTION_TICKS.get();
        } catch (RuntimeException | LinkageError e) {
            return Long.MAX_VALUE; // fail closed: treat everyone as new
        }
    }

    /**
     * Runs one attempt through the state machine.
     *
     * @param context the immutable attempt context
     * @return the final result
     */
    public Result attempt(ShadowAttemptContext context) {
        try {
            return attemptInternal(context);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft attempt failed with a framework exception (event {}): {}",
                    context.eventId(), e.toString());
            try {
                cooldownTracker.markFailureCooldown(context.thief().getUUID(),
                        settingsSupplier.get().failureCooldownTicks());
            } catch (RuntimeException | LinkageError suppressed) {
                // The failure cooldown is best-effort; never let the recovery
                // path itself break the tick isolation.
            }
            return new Result(ShadowTheftOutcome.INVALID_CONTEXT, context.eventId(), false,
                    ShadowTheftReceipt.empty(), "framework_exception", null);
        }
    }

    private Result attemptInternal(ShadowAttemptContext context) {
        ShadowFrameworkSettings settings = settingsSupplier.get();
        java.util.UUID thiefId = context.thief().getUUID();

        // 1. master gates — combined (8C.2.2 §1): Config.ENABLED &&
        //    integrationEnabled && playerTheftEnabled && realAssetTransfersEnabled.
        //    Every switch is fail-closed in defaults(), so a config read
        //    failure ends here BEFORE the candidate pool, any random call,
        //    the PENDING audit and the executor — the provider and the
        //    engine never run, and no failure exposure happens.
        if (!settings.masterEnabled() || !settings.integrationEnabled()
                || !settings.playerTheftEnabled()) {
            return result(ShadowTheftOutcome.FRAMEWORK_DISABLED, context, null, null, null, false, null);
        }
        if (!settings.realAssetTransfersEnabled()) {
            return result(ShadowTheftOutcome.FRAMEWORK_DISABLED, context, null, null,
                    "real_asset_transfers_disabled", false, null);
        }

        // 2. context validation — including the PLAYER/ENTITY ↔ targetType
        //    invariant, enforced here (8B.1.1 §6) instead of being discovered
        //    indirectly through later exceptions.
        if (context.level() == null || context.level().isClientSide()
                || context.automated()
                || (context.targetKind() == ShadowTargetKind.ENTITY && !settings.entityTheftEnabled())
                || (context.targetKind() == ShadowTargetKind.ENTITY && context.targetType() == null)
                || (context.targetKind() == ShadowTargetKind.PLAYER && context.targetType() != null)) {
            return result(ShadowTheftOutcome.INVALID_CONTEXT, context, null, null, null, false, null);
        }

        // 3. real player / FakePlayer
        if (context.thief() instanceof FakePlayer) {
            return result(ShadowTheftOutcome.INVALID_CONTEXT, context, null, null, null, false, null);
        }

        // 4. audit gate — BEFORE any provider / random / executor call (8B.1 §1).
        if (!settings.auditEnabled()) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft refused: audit disabled (event {})", context.eventId());
            return finishAuditRefusal(ShadowTheftOutcome.AUDIT_FAILED, context, "audit_disabled");
        }
        ShadowAuditWriter audit;
        try {
            audit = auditStoreFactory.apply(context.level());
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft refused: audit store unavailable (event {}): {}",
                    context.eventId(), e.toString());
            return finishAuditRefusal(ShadowTheftOutcome.AUDIT_FAILED, context, "audit_unavailable");
        }
        if (audit == null) {
            // A factory returning null is the same as an unavailable store:
            // fail closed before any provider/random/executor call.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft refused: audit store factory returned null (event {})",
                    context.eventId());
            return finishAuditRefusal(ShadowTheftOutcome.AUDIT_FAILED, context, "audit_unavailable");
        }
        // 4b. audit HEALTH (8C.2.4 §2): a damaged/saturated audit store
        //     refuses the attempt BEFORE the candidate pool, the date
        //     source, any random call and the executor — no audit, no real
        //     asset transfer.
        if (!audit.isHealthy()) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft refused: audit store unhealthy (event {})", context.eventId());
            return finishAuditRefusal(ShadowTheftOutcome.AUDIT_FAILED, context, "audit_unhealthy");
        }
        ShadowDailyLimitWriter dailyLimits;
        try {
            dailyLimits = dailyLimitStoreFactory.apply(context.level());
        } catch (RuntimeException | LinkageError e) {
            dailyLimits = null; // unresolved → conservative: ITEM excluded
        }

        // 5. UTC day capture — AFTER every functional gate and context check
        //    (8C.2.2 §2): a gated-off attempt never touches the date
        //    supplier. A failing supplier prunes ITEM only (fail-closed);
        //    other theft types are unaffected and no ITEM asset can move.
        String utcDay;
        try {
            utcDay = dailyDateSupplier.get();
        } catch (RuntimeException | LinkageError e) {
            utcDay = null;
        }

        // 6. idempotency (8B.1 §2, 8B.1.1 §3-4): the in-memory fast path runs
        //    FIRST so an already-flagged eventId (e.g. a just-posted
        //    RECOVERY_REQUIRED) is a DUPLICATE with zero new events; then the
        //    durable eventId check; then the attempt key (thief + target +
        //    serverTick).
        if (idempotencyTracker.hasEventId(context.eventId())) {
            return result(ShadowTheftOutcome.DUPLICATE, context, null, null, null, false, audit);
        }
        ShadowAuditRecord existing = audit.byEventId(context.eventId());
        if (existing != null) {
            if (existing.auditState() == ShadowAuditState.PENDING) {
                // Unresolved crash window: the eventId has a pre-write record
                // whose outcome is unknown. Alert once per JVM session by
                // settling the in-memory keys now; a restart (fresh tracker)
                // will alert once more from the durable PENDING record.
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow theft attempt blocked: unresolved PENDING record (event {})",
                        context.eventId());
                idempotencyTracker.markEventId(context.eventId());
                idempotencyTracker.markAttempt(thiefId, context.targetId(), context.serverTick());
                return result(ShadowTheftOutcome.RECOVERY_REQUIRED, context, existing.theftType(), null,
                        "pending_record_exists", true, audit);
            }
            return result(ShadowTheftOutcome.DUPLICATE, context, null, null, null, false, audit);
        }
        if (idempotencyTracker.isAttemptDuplicate(thiefId, context.targetId(), context.serverTick())) {
            return result(ShadowTheftOutcome.DUPLICATE, context, null, null, null, false, audit);
        }

        // 7. protection service (fail-closed: UNKNOWN and exceptions deny)
        ShadowProtectionResult protection;
        try {
            protection = protectionService.check(context);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow protection service failed (event {}): {}", context.eventId(), e.toString());
            protection = ShadowProtectionResult.UNKNOWN;
        }
        logDebugProtection(context.eventId(), protection);
        if (protection != ShadowProtectionResult.ALLOWED) {
            return finishAuditedAttempt(ShadowTheftOutcome.PROTECTED, context, null, null, null, audit);
        }

        // 8. cooldowns (global / no-candidate / failure / victim protection)
        if (cooldownTracker.isGlobalCooldownActive(thiefId)
                || cooldownTracker.isNoCandidateCooldownActive(thiefId)
                || cooldownTracker.isFailureCooldownActive(thiefId)
                || cooldownTracker.isVictimProtected(context.targetId())) {
            return finishAuditedAttempt(ShadowTheftOutcome.COOLDOWN, context, null, null, null, audit);
        }

        // 9. candidate pool — only currently available types; COIN is hard
        //    blocked (no atomic transfer exists yet).
        ShadowCandidatePool pool = ShadowCandidatePool.empty();
        try {
            for (ShadowCandidate candidate : candidateProvider.provide(context)) {
                pool = pool.with(candidate);
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow candidate provider failed (event {}): {}", context.eventId(), e.toString());
            pool = ShadowCandidatePool.empty();
        }
        pool = pool.without(ShadowTheftType.COIN);
        // Daily item-loss cap (8C.2 §5, 8C.2.2 §2): checked BEFORE the draw
        // and prepare; at the cap or with an unavailable date only ITEM
        // leaves the pool — HEALTH / HUNGER / EFFECT stay, and no ITEM asset
        // can move.
        if (pool.contains(ShadowTheftType.ITEM) && dailyLimitAtOrOver(dailyLimits, context, utcDay)) {
            pool = pool.without(ShadowTheftType.ITEM);
        }
        logDebugCandidates(context.eventId(), pool);

        // 10. empty pool → short no-candidate cooldown
        if (pool.isEmpty()) {
            cooldownTracker.markNoCandidateCooldown(thiefId, settings.noCandidateCooldownTicks());
            return finishAuditedAttempt(ShadowTheftOutcome.NO_CANDIDATE, context, null, null, null, audit);
        }

        // 11. type draw — exactly once
        RandomSource random = randomSourceSupplier.get();
        ShadowCandidate selected = pool.draw(random);

        // 12. executor.prepare — read-only concrete-asset selection (8C.1 §2):
        //     the plan is built BEFORE the success roll so the chance can use
        //     the plan's modifier (e.g. the high-value item penalty). A failed
        //     prepare never re-draws the theft type.
        ShadowTransferPlan plan;
        try {
            plan = transferExecutor.prepare(context, selected, random);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow transfer prepare failed (event {}): {}", context.eventId(), e.toString());
            plan = null;
        }
        if (plan == null) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            cooldownTracker.markAlert(context.targetId(), settings.alertTicks());
            return finishAuditedAttempt(ShadowTheftOutcome.TRANSFER_FAILED, context, selected.type(), null,
                    "prepare_failed", audit);
        }
        // Protocol hardening (8C.1.1 §6): a plan for a different theft type
        // is never rolled on, never committed — no success draw, no assets.
        if (plan.type() != selected.type()) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            cooldownTracker.markAlert(context.targetId(), settings.alertTicks());
            return finishAuditedAttempt(ShadowTheftOutcome.TRANSFER_FAILED, context, selected.type(), null,
                    "plan_type_mismatch", audit);
        }

        // 13. success chance from immutable facts (LOS-aware, 8B.1 §6) + the
        //     concrete plan's modifier.
        ShadowVectorMath.ShadowDirectionFacts facts = computeFacts(context);
        double chance = ShadowSuccessCalculator.calculate(new ShadowSuccessContext(
                settings.baseSuccessChance(), facts.behind(), facts.watched(),
                cooldownTracker.isAlerted(context.targetId()), context.distance(),
                selected.successModifier() + plan.successModifier(), 0.0d,
                settings.minSuccessChance(), settings.maxSuccessChance()));

        // 14. success roll — exactly once
        boolean success = ShadowSuccessCalculator.roll(random, chance);

        // 15. failed roll → exposure (alert) + failure cooldown
        if (!success) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            cooldownTracker.markAlert(context.targetId(), settings.alertTicks());
            return finishAuditedAttempt(ShadowTheftOutcome.FAILED_ROLL, context, selected.type(), null, null, audit);
        }

        // 16. pre-write audit (PENDING) — the commit is only ever executed
        //     after this record exists (8B.1 §1.4).
        ShadowAuditRecord pendingRecord = buildAuditRecord(context, selected.type(), null, null,
                ShadowAuditState.PENDING, null);
        boolean prewriteOk = safeAppend(audit, pendingRecord, "prewrite");
        if (!prewriteOk) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            // AUDIT_FAILED is a framework-level refusal: no record (the store
            // is failing), no event — but the idempotency keys are settled
            // (8B.1 §2.4) so the same attempt is not re-rolled endlessly.
            return finishAuditRefusal(ShadowTheftOutcome.AUDIT_FAILED, context, "audit_prewrite_failed");
        }

        // 17. pre-commit re-validation (8C.1 §7): protection, distance and
        //     target state are checked once more; any drift fails closed
        //     BEFORE the commit (the PENDING record is finalised cleanly).
        String drift = preCommitDrift(context, settings, thiefId);
        if (drift != null) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            return finishAuditedAttempt(ShadowTheftOutcome.TRANSFER_FAILED, context, selected.type(), null,
                    drift, audit);
        }

        // 18. Daily ITEM quota reservation — BEFORE the asset commit
        //     (8C.2.1 §2, 8C.2.2 §5). RESERVED occupies the quota immediately;
        //     LIMIT_REACHED / REJECTED / exceptions forbid the transfer
        //     (fail-closed). The same eventId never reserves twice; the
        //     reservation state is LOCAL to this attempt and passed to the
        //     commit/release helpers explicitly (8C.2.2 §3).
        java.util.UUID reservationEventId = null;
        if (selected.type() == ShadowTheftType.ITEM) {
            ShadowDailyLimitWriter.ReservationResult reserve;
            try {
                reserve = dailyLimits == null
                        ? ShadowDailyLimitWriter.ReservationResult.REJECTED
                        : dailyLimits.tryReserve(context.targetId(), utcDay,
                                context.eventId(), settings.dailyItemLossLimit());
            } catch (RuntimeException | LinkageError e) {
                reserve = ShadowDailyLimitWriter.ReservationResult.REJECTED;
            }
            if (reserve == ShadowDailyLimitWriter.ReservationResult.RESERVED
                    || reserve == ShadowDailyLimitWriter.ReservationResult.COMMITTED_EXISTING) {
                reservationEventId = context.eventId();
            } else {
                cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
                cooldownTracker.markAlert(context.targetId(), settings.alertTicks());
                return finishAuditedAttempt(ShadowTheftOutcome.TRANSFER_FAILED, context,
                        selected.type(), null, "daily_item_limit", audit);
            }
        }

        // 19. executor.commit — full atomic transfer; the result explicitly
        //     distinguishes COMMITTED / FAILED_CLEAN / RECOVERY_REQUIRED.
        ShadowTransferResult transfer;
        try {
            transfer = transferExecutor.commit(context, selected, plan);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow transfer commit threw (event {}): {}", context.eventId(), e.toString());
            transfer = ShadowTransferResult.recoveryRequired("executor_exception",
                    ShadowTheftReceipt.empty());
        }
        if (!transfer.committed()) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            cooldownTracker.markAlert(context.targetId(), settings.alertTicks());
            if (transfer.state() == ShadowTransferState.RECOVERY_REQUIRED) {
                // The executor could not restore the asset state internally:
                // never report this as a plain failure. The reservation KEEPS
                // occupying the quota (the assets may have moved).
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow theft RECOVERY REQUIRED (event {}) — commit could not be "
                                + "restored internally; operator intervention required", context.eventId());
                return finishAuditedAttempt(ShadowTheftOutcome.RECOVERY_REQUIRED, context, selected.type(),
                        transfer.receipt(), transfer.failureReason(), audit);
            }
            releaseDailyReservation(dailyLimits, reservationEventId, context);
            return finishAuditedAttempt(ShadowTheftOutcome.TRANSFER_FAILED, context, selected.type(), null,
                    transfer.failureReason(), audit);
        }

        ShadowTheftReceipt receipt = transfer.receipt();

        // 20. receipt integrity: it must only carry fields of the drawn type.
        //     The commit has ALREADY happened — a mismatch must never be
        //     reported as a plain TRANSFER_FAILED (which would claim nothing
        //     moved). Roll back exactly once (8B.1.1 §1).
        if (!receipt.matches(selected.type())) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            boolean rollbackOk;
            try {
                rollbackOk = transferExecutor.rollback(context, selected, plan);
            } catch (RuntimeException | LinkageError e) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow transfer rollback failed (event {}): {}", context.eventId(), e.toString());
                rollbackOk = false;
            }
            if (rollbackOk) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow theft committed with a mismatched receipt; rolled back (event {})",
                        context.eventId());
                releaseDailyReservation(dailyLimits, reservationEventId, context);
                return finishAuditedAttempt(ShadowTheftOutcome.ROLLED_BACK, context, selected.type(), null,
                        "receipt_type_mismatch", audit);
            }
            // RECOVERY_REQUIRED keeps the reservation (assets may have moved).
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft RECOVERY REQUIRED (event {}) — mismatched receipt and rollback "
                            + "failed; operator intervention required", context.eventId());
            return finishAuditedAttempt(ShadowTheftOutcome.RECOVERY_REQUIRED, context, selected.type(), null,
                    "rollback_failed; receipt_type_mismatch", audit);
        }

        // 21. quota commit (ITEM only) — MUST succeed before SUCCESS
        //     (8C.2.2 §5). A false return or an exception never continues to
        //     SUCCESS: the asset transfer is rolled back exactly once;
        //     a successful rollback releases the RESERVED quota and yields
        //     ROLLED_BACK, a failed one keeps the quota (the assets may have
        //     moved) and yields RECOVERY_REQUIRED.
        if (selected.type() == ShadowTheftType.ITEM && reservationEventId != null) {
            boolean committed = commitDailyReservation(dailyLimits, reservationEventId, context);
            if (!committed) {
                cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
                boolean rollbackOk = rollbackOnce(context, selected, plan);
                if (rollbackOk) {
                    ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                            "[TCTH] Shadow theft quota commit failed; rolled back (event {})",
                            context.eventId());
                    releaseDailyReservation(dailyLimits, reservationEventId, context);
                    return finishAuditedAttempt(ShadowTheftOutcome.ROLLED_BACK, context, selected.type(),
                            null, "daily_commit_failed", audit);
                }
                // RECOVERY_REQUIRED keeps the reservation (assets may have moved).
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow theft RECOVERY REQUIRED (event {}) — quota commit failed and "
                                + "rollback failed; operator intervention required", context.eventId());
                return finishAuditedAttempt(ShadowTheftOutcome.RECOVERY_REQUIRED, context, selected.type(),
                        receipt, "rollback_failed; daily_commit_failed", audit);
            }
        }

        // 22. final audit write — SUCCESS requires it.
        ShadowAuditRecord finalRecord = buildAuditRecord(context, selected.type(),
                ShadowTheftOutcome.SUCCESS, receipt, ShadowAuditState.FINAL, null);
        boolean finalOk = safeAppend(audit, finalRecord, "final");

        // 23. final write failed → exactly one rollback. A successful
        //     rollback ALSO releases the already-committed quota (8C.2.2 §5).
        if (!finalOk) {
            cooldownTracker.markFailureCooldown(thiefId, settings.failureCooldownTicks());
            boolean rollbackOk = rollbackOnce(context, selected, plan);
            if (rollbackOk) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow theft committed but audit failed; rolled back (event {})",
                        context.eventId());
                releaseDailyReservation(dailyLimits, reservationEventId, context);
                return finishAuditedAttempt(ShadowTheftOutcome.ROLLED_BACK, context, selected.type(), null,
                        "audit_final_write_failed", audit);
            }
            // RECOVERY_REQUIRED keeps the reservation (assets may have moved).
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft RECOVERY REQUIRED (event {}) — rollback failed after commit; "
                            + "operator intervention required", context.eventId());
            return finishAuditedAttempt(ShadowTheftOutcome.RECOVERY_REQUIRED, context, selected.type(), receipt,
                    "rollback_failed", audit);
        }

        // 24. commit cooldowns / victim protection. The FINAL SUCCESS audit
        //     record was written EXACTLY ONCE in step 22 (8C.2.3 §1) — the
        //     settlement below never appends again: it only settles the
        //     idempotency keys and posts the final event.
        cooldownTracker.markGlobalCooldown(thiefId, settings.globalCooldownTicks());
        cooldownTracker.markVictimProtection(context.targetId(), settings.victimProtectionTicks());

        return finishAfterFinalAudit(context, selected.type(), receipt, audit);
    }

    /**
     * Settlement AFTER the FINAL SUCCESS audit record was already written
     * (step 22) — this must NEVER append again (8C.2.3 §1): it settles the
     * idempotency keys and posts the final event. A second write would be
     * wrong even if the record looked identical — no fixed timestamps, no
     * coincidental equality.
     */
    private Result finishAfterFinalAudit(ShadowAttemptContext context, ShadowTheftType theftType,
                                         ShadowTheftReceipt receipt, ShadowAuditWriter audit) {
        idempotencyTracker.markEventId(context.eventId());
        idempotencyTracker.markAttempt(context.thief().getUUID(), context.targetId(), context.serverTick());
        return result(ShadowTheftOutcome.SUCCESS, context, theftType, receipt, null, true, audit);
    }

    /**
     * Writes the final audit record for an audited FAILED attempt outcome
     * (upsert — replaces any PENDING pre-write), settles the idempotency keys
     * and posts the final event. SUCCESS never goes through this path: its
     * FINAL record is written once in the success sequence.
     */
    private Result finishAuditedAttempt(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                                        @Nullable ShadowTheftType theftType,
                                        @Nullable ShadowTheftReceipt receipt,
                                        @Nullable String failureReason, ShadowAuditWriter audit) {
        ShadowAuditRecord record = buildAuditRecord(context, theftType, outcome, receipt,
                ShadowAuditState.FINAL, failureReason);
        if (!safeAppend(audit, record, "attempt")) {
            // The attempt happened but could not be recorded; keep the real
            // outcome (never downgrade to AUDIT_FAILED) and log severely.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow attempt audit write failed (event {}, outcome {})",
                    context.eventId(), outcome);
        }
        idempotencyTracker.markEventId(context.eventId());
        idempotencyTracker.markAttempt(context.thief().getUUID(), context.targetId(), context.serverTick());
        return result(outcome, context, theftType, receipt, failureReason, true, audit);
    }

    /**
     * Framework-level audit refusal (AUDIT_FAILED): no record, no event, but
     * the idempotency keys are settled (8B.1 §2.4) so a broken-audit attempt
     * is never re-rolled endlessly with the same keys.
     */
    private Result finishAuditRefusal(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                                      String failureReason) {
        idempotencyTracker.markEventId(context.eventId());
        idempotencyTracker.markAttempt(context.thief().getUUID(), context.targetId(), context.serverTick());
        return result(outcome, context, null, null, failureReason, false, null);
    }

    /**
     * Pre-commit re-validation (8C.1 §7): protection, distance and target
     * state are re-checked right before the commit. Returns the drift reason,
     * or {@code null} when everything still holds.
     */
    private String preCommitDrift(ShadowAttemptContext context, ShadowFrameworkSettings settings,
                                  java.util.UUID thiefId) {
        ShadowProtectionResult protection;
        try {
            protection = protectionService.check(context);
        } catch (RuntimeException | LinkageError e) {
            protection = ShadowProtectionResult.UNKNOWN;
        }
        if (protection != ShadowProtectionResult.ALLOWED) {
            logDebugProtection(context.eventId(), protection);
            return "protection_drift";
        }
        if (context.targetKind() == ShadowTargetKind.PLAYER) {
            Player target = context.level().getPlayerByUUID(context.targetId());
            if (target == null || !target.isAlive() || target.level() != context.level()) {
                return "target_drift";
            }
            if (!context.thief().canInteractWithEntity(target.getBoundingBox(),
                    context.thief().entityInteractionRange())) {
                return "distance_drift";
            }
        }
        return null;
    }

    /** Appends with exception isolation; returns whether the append succeeded. */
    private boolean safeAppend(ShadowAuditWriter audit, ShadowAuditRecord record, String stage) {
        try {
            return audit.append(record);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow audit append failed ({}, event {}): {}", stage,
                    record.eventId(), e.toString());
            return false;
        }
    }

    private Result result(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                          @Nullable ShadowTheftType theftType, @Nullable ShadowTheftReceipt receipt,
                          @Nullable String failureReason, boolean post, @Nullable ShadowAuditWriter audit) {
        ShadowTheftReceipt finalReceipt = receipt != null ? receipt : ShadowTheftReceipt.empty();
        boolean posted = false;
        if (post) {
            posted = postEvent(context, theftType, outcome, finalReceipt);
        }
        logDebugResult(context.eventId(), outcome, theftType, failureReason);
        return new Result(outcome, context.eventId(), posted, finalReceipt, failureReason, theftType);
    }

    /** Debug output (default off): eventId, outcome, drawn type and reason
     *  only — never inventory contents, effect details or balances. */
    private static void logDebugResult(java.util.UUID eventId, ShadowTheftOutcome outcome,
                                       @Nullable ShadowTheftType theftType,
                                       @Nullable String failureReason) {
        if (ShadowDebug.isEnabled()) {
            TCTHIntegration.LOGGER.info("[TCTH][SHADOW] event={} outcome={} theftType={} reason={}",
                    eventId, outcome, theftType, failureReason != null ? failureReason : "-");
        }
    }

    private static void logDebugProtection(java.util.UUID eventId, ShadowProtectionResult protection) {
        if (ShadowDebug.isEnabled()) {
            TCTHIntegration.LOGGER.info("[TCTH][SHADOW] event={} protection={}", eventId, protection);
        }
    }

    private static void logDebugCandidates(java.util.UUID eventId, ShadowCandidatePool pool) {
        if (ShadowDebug.isEnabled()) {
            java.util.List<String> types = new java.util.ArrayList<>();
            for (ShadowTheftType type : ShadowTheftType.values()) {
                if (pool.contains(type)) {
                    types.add(type.name());
                }
            }
            TCTHIntegration.LOGGER.info("[TCTH][SHADOW] event={} candidates={}", eventId, types);
        }
    }

    private boolean postEvent(ShadowAttemptContext context, @Nullable ShadowTheftType theftType,
                              ShadowTheftOutcome outcome, ShadowTheftReceipt receipt) {
        try {
            ShadowTheftEvent event = new ShadowTheftEvent(
                    context.eventId(), context.thief(), context.targetKind(), context.targetId(),
                    context.targetType(), theftType, outcome, receipt, context.automated(),
                    context.level(), context.position());
            return ShadowTheftEventDispatcher.publish(event) == ShadowTheftEventDispatcher.Result.POSTED;
        } catch (RuntimeException | LinkageError e) {
            // Defensive: an invariant violation or dispatch failure must not
            // break the tick; the attempt outcome is still reported.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow theft event post failed (event {}): {}", context.eventId(), e.toString());
            return false;
        }
    }

    private ShadowAuditRecord buildAuditRecord(ShadowAttemptContext context,
                                               @Nullable ShadowTheftType theftType,
                                               @Nullable ShadowTheftOutcome outcome,
                                               @Nullable ShadowTheftReceipt receipt,
                                               ShadowAuditState auditState,
                                               @Nullable String failureReason) {
        ResourceLocation dimension = context.level().dimension().location();
        ShadowTheftReceipt r = receipt != null ? receipt : ShadowTheftReceipt.empty();
        return new ShadowAuditRecord(context.eventId(), context.thief().getUUID(), context.targetId(),
                context.targetKind(), context.targetType(), theftType, outcome, auditState,
                r.itemId(), r.itemCount(), r.numericAmount(), r.effectId(), r.effectDurationTicks(),
                safeEpochMillis(), context.serverTick(), dimension, context.position(), failureReason);
    }

    private long safeEpochMillis() {
        try {
            long value = epochMillisSupplier.get();
            return value >= 0L ? value : 0L;
        } catch (RuntimeException | LinkageError e) {
            return 0L;
        }
    }

    /** Frees the daily ITEM reservation after a clean failure or a
     *  successful rollback (best-effort; a failure keeps the quota occupied,
     *  which is the conservative direction). The store and the reserved
     *  eventId are explicit attempt-local state (8C.2.2 §3) — never instance
     *  fields, so one coordinator can run many attempts safely. */
    private void releaseDailyReservation(ShadowDailyLimitWriter dailyLimits,
                                         java.util.UUID reservationEventId,
                                         ShadowAttemptContext context) {
        if (reservationEventId == null || dailyLimits == null) {
            return;
        }
        try {
            if (!dailyLimits.releaseReservation(reservationEventId)) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow daily quota release failed (event {})", context.eventId());
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow daily quota release failed (event {}): {}",
                    context.eventId(), e.toString());
        }
    }

    /** Commits the ITEM reservation; {@code false} on a refused or throwing
     *  store — the caller must then roll the asset transfer back. */
    private boolean commitDailyReservation(ShadowDailyLimitWriter dailyLimits,
                                           java.util.UUID reservationEventId,
                                           ShadowAttemptContext context) {
        if (reservationEventId == null || dailyLimits == null) {
            return true; // nothing to commit
        }
        try {
            return dailyLimits.commitReservation(reservationEventId);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow daily quota commit failed (event {}): {}",
                    context.eventId(), e.toString());
            return false;
        }
    }

    /** Runs the transfer rollback exactly once with exception isolation. */
    private boolean rollbackOnce(ShadowAttemptContext context, ShadowCandidate selected,
                                 ShadowTransferPlan plan) {
        try {
            return transferExecutor.rollback(context, selected, plan);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow transfer rollback failed (event {}): {}", context.eventId(), e.toString());
            return false;
        }
    }

    /** Whether the victim has reached the configured daily ITEM cap
     *  (conservative when the store is unresolved or the date is
     *  unavailable — an invalid day prunes ITEM only, 8C.2.2 §2). */
    private boolean dailyLimitAtOrOver(ShadowDailyLimitWriter dailyLimits,
                                       ShadowAttemptContext context, String utcDay) {
        if (dailyLimits == null || utcDay == null || utcDay.isEmpty()) {
            return true; // unresolved store / unavailable date → ITEM excluded
        }
        try {
            return dailyLimits.isAtItemLimit(context.targetId(), utcDay,
                    settingsSupplier.get().dailyItemLossLimit());
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** Resolves the orientation facts; player targets only in phase 8B. */
    private static ShadowVectorMath.ShadowDirectionFacts computeFacts(ShadowAttemptContext context) {
        if (context.targetKind() != ShadowTargetKind.PLAYER) {
            return new ShadowVectorMath.ShadowDirectionFacts(false, false);
        }
        Player target = context.level().getPlayerByUUID(context.targetId());
        if (target == null) {
            return new ShadowVectorMath.ShadowDirectionFacts(false, false);
        }
        return ShadowVectorMath.computeFacts(target.getLookAngle(), context.thief().position(),
                target.position(), context.hasLineOfSight());
    }
}
