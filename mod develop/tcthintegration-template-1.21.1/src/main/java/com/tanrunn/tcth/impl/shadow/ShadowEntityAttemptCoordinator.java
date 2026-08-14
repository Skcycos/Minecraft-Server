package com.tanrunn.tcth.impl.shadow;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.debug.ShadowDebug;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Entity-target loot attempt state machine (8D.1 §5, reworked 8D.1.1).
 *
 * <p>Fixed random/audit/asset order (8D.1.1 §7):
 * <pre>
 *  gates / audit-enabled / context / audit health / cooldowns / idempotency
 *  → protection → REAL entity type revalidation + hard exclusion
 *  → attachment health → full definition
 *  → pool (1) → entry (1) → count (1, even when min==max)
 *  → slot feasibility (prepare) → success roll (1)
 *  → PENDING audit → PENDING marker (verified) → slot commit → LOOTED marker
 *    (verified) → FINAL audit → single event
 * </pre>
 *
 * <p>Audit-state rules (8D.1.1 §2): {@code auditEnabled} is checked BEFORE
 * any random/marker/asset operation; a cleanly recoverable failure after
 * PENDING writes a FINAL FAILED_CLEAN (no PENDING residue); only the FINAL
 * write itself failing may keep PENDING (operator recovery); RECOVERY_REQUIRED
 * with a known delivered count carries the receipt.
 *
 * <p>Delivery is an explicit-slot transaction ({@link SlotItemTransaction});
 * attachment and item restoration are ALWAYS attempted independently.
 */
public final class ShadowEntityAttemptCoordinator {

    private final Supplier<ShadowFrameworkSettings> settingsSupplier;
    private final Function<ServerLevel, ShadowAuditWriter> auditStoreFactory;
    private final ShadowProtectionService protectionService;
    private final ShadowCooldownTracker cooldownTracker;
    private final ShadowIdempotencyTracker idempotencyTracker;
    private final Supplier<RandomSource> randomSourceSupplier;
    private final Supplier<Long> epochMillisSupplier;

    public ShadowEntityAttemptCoordinator(Supplier<ShadowFrameworkSettings> settingsSupplier,
                                          Function<ServerLevel, ShadowAuditWriter> auditStoreFactory,
                                          ShadowProtectionService protectionService,
                                          ShadowCooldownTracker cooldownTracker,
                                          ShadowIdempotencyTracker idempotencyTracker,
                                          Supplier<RandomSource> randomSourceSupplier,
                                          Supplier<Long> epochMillisSupplier) {
        this.settingsSupplier = settingsSupplier;
        this.auditStoreFactory = auditStoreFactory;
        this.protectionService = protectionService;
        this.cooldownTracker = cooldownTracker;
        this.idempotencyTracker = idempotencyTracker;
        this.randomSourceSupplier = randomSourceSupplier;
        this.epochMillisSupplier = epochMillisSupplier;
    }

    /** The immutable result of an entity loot attempt. */
    public record Result(ShadowTheftOutcome outcome, UUID eventId, boolean eventPosted,
                         ShadowTheftReceipt receipt, @Nullable String failureReason,
                         @Nullable ResourceLocation entityType) {
    }

    public static ShadowEntityAttemptCoordinator defaults() {
        return new ShadowEntityAttemptCoordinator(
                ShadowFrameworkSettings::defaults,
                level -> ShadowAuditStore.current(level),
                new ShadowCompositeProtectionService(
                        com.tanrunn.tcth.impl.shadow.protection.OpacProtectionProviderFactory.create(),
                        ShadowAttemptCoordinator::newPlayerProtectionTicks),
                ShadowCooldownTracker.SHARED,
                ShadowIdempotencyTracker.SHARED,
                RandomSource::create,
                System::currentTimeMillis);
    }

    public Result attempt(ShadowAttemptContext context) {
        try {
            return attemptInternal(context);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow entity loot attempt failed with a framework exception (event {}): {}",
                    context.eventId(), e.toString());
            return new Result(ShadowTheftOutcome.INVALID_CONTEXT, context.eventId(), false,
                    ShadowTheftReceipt.empty(), "framework_exception", context.targetType());
        }
    }

    private Result attemptInternal(ShadowAttemptContext context) {
        ShadowFrameworkSettings settings = settingsSupplier.get();
        UUID thiefId = context.thief().getUUID();

        // 1. gates + auditEnabled BEFORE any random/marker/asset (8D.1.1 §2).
        if (!settings.masterEnabled() || !settings.integrationEnabled()
                || !settings.entityTheftEnabled() || !settings.realAssetTransfersEnabled()) {
            return new Result(ShadowTheftOutcome.FRAMEWORK_DISABLED, context.eventId(), false,
                    ShadowTheftReceipt.empty(), null, context.targetType());
        }
        if (context.level() == null || context.level().isClientSide() || context.automated()
                || context.targetKind() != ShadowTargetKind.ENTITY
                || context.targetType() == null) {
            return new Result(ShadowTheftOutcome.INVALID_CONTEXT, context.eventId(), false,
                    ShadowTheftReceipt.empty(), null, context.targetType());
        }
        if (!settings.auditEnabled()) {
            return new Result(ShadowTheftOutcome.AUDIT_FAILED, context.eventId(), false,
                    ShadowTheftReceipt.empty(), "audit_disabled", context.targetType());
        }
        ShadowAuditWriter audit;
        try {
            audit = auditStoreFactory.apply(context.level());
        } catch (RuntimeException | LinkageError e) {
            return refusal(context, "audit_unavailable");
        }
        if (audit == null || !audit.isHealthy()) {
            return refusal(context, "audit_unhealthy");
        }

        // 2. idempotency FIRST (player-path parity): eventId (in-memory +
        //    durable) and thief+target+tick.
        if (idempotencyTracker.hasEventId(context.eventId())
                || audit.byEventId(context.eventId()) != null) {
            return settle(ShadowTheftOutcome.DUPLICATE, context, null, null, audit);
        }
        if (idempotencyTracker.isAttemptDuplicate(thiefId, context.targetId(), context.serverTick())) {
            return settle(ShadowTheftOutcome.DUPLICATE, context, null, null, audit);
        }

        // 3. cooldowns (8D.1.1 §5): global / failure / no-candidate.
        if (cooldownTracker.isGlobalCooldownActive(thiefId)
                || cooldownTracker.isFailureCooldownActive(thiefId)
                || cooldownTracker.isNoCandidateCooldownActive(thiefId)) {
            return settle(ShadowTheftOutcome.COOLDOWN, context, null, null, audit);
        }

        // 4. protection.
        ShadowProtectionResult protection;
        try {
            protection = protectionService.check(context);
        } catch (RuntimeException | LinkageError e) {
            protection = ShadowProtectionResult.UNKNOWN;
        }
        if (protection != ShadowProtectionResult.ALLOWED) {
            return settle(ShadowTheftOutcome.PROTECTED, context, null, null, audit);
        }

        // 5. REAL entity type revalidation (8D.1.1 §5): never trust the
        //    context for the hard exclusion — re-resolve from the entity.
        Entity target = context.level().getEntity(context.targetId());
        if (target == null || target.isRemoved() || !target.isAlive()
                || target.level() != context.level()) {
            return settle(ShadowTheftOutcome.INVALID_CONTEXT, context, null, null, audit);
        }
        ResourceLocation realType;
        try {
            Registry<net.minecraft.world.entity.EntityType<?>> entityRegistry =
                    context.level().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
            realType = entityRegistry.getKey(target.getType());
        } catch (RuntimeException | LinkageError e) {
            realType = null;
        }
        if (realType == null || !realType.equals(context.targetType())) {
            return settle(ShadowTheftOutcome.INVALID_CONTEXT, context, null, null, audit);
        }
        if (ShadowLootLoader.isHardExcluded(realType)) {
            return settle(ShadowTheftOutcome.NO_CANDIDATE, context, null, null, audit);
        }

        // 6. attachment health: PENDING / LOOTED / CORRUPT all block.
        ShadowLootState state = attachmentAccess.read(target);
        if (state.blocksTheft()) {
            return settle(ShadowTheftOutcome.DUPLICATE, context, null, null, audit);
        }

        // 7. full definition (items already registry-validated at reload).
        ShadowLootDefinition definition = ShadowLootLoader.instance().get(realType);
        if (definition == null) {
            return settle(ShadowTheftOutcome.NO_CANDIDATE, context, null, null, audit,
                    settings, ShadowTheftOutcome.NO_CANDIDATE);
        }

        // 8. pool → entry → count: exactly one random call each.
        RandomSource random = randomSourceSupplier.get();
        ShadowLootDefinition.ShadowLootPool pool = ShadowLootLoader.selectPool(definition, random);
        ShadowLootDefinition.ShadowLootEntry entry = ShadowLootLoader.selectEntry(pool, random);
        int count = ShadowLootLoader.rollCount(entry, random);
        Registry<Item> itemRegistry =
                context.level().registryAccess().registryOrThrow(Registries.ITEM);
        // containsKey first: Registry.get() falls back to a default entry for
        // unknown ids (8D.1.2 §4).
        if (!itemRegistry.containsKey(entry.itemId())) {
            return settle(ShadowTheftOutcome.NO_CANDIDATE, context, null, null, audit,
                    settings, ShadowTheftOutcome.NO_CANDIDATE);
        }
        Item item = itemRegistry.get(entry.itemId());
        if (item == null) {
            return settle(ShadowTheftOutcome.NO_CANDIDATE, context, null, null, audit,
                    settings, ShadowTheftOutcome.NO_CANDIDATE);
        }
        ItemStack delivery = new ItemStack(item, count);

        // 9. slot feasibility BEFORE the success roll (8D.1.1 §7): a full
        //    main inventory refuses before any audit/marker write.
        SlotItemTransaction transaction = SlotItemTransaction.prepare(
                context.thief().getInventory(), delivery);
        if (transaction == null) {
            return settle(ShadowTheftOutcome.TRANSFER_FAILED, context, null,
                    "inventory_full", audit, settings,
                        ShadowTheftOutcome.TRANSFER_FAILED);
        }

        // 10. success roll — exactly one.
        double chance = ShadowSuccessCalculator.calculate(new ShadowSuccessContext(
                settings.baseSuccessChance(), false, false, false, context.distance(),
                0.0d, 0.0d, settings.minSuccessChance(), settings.maxSuccessChance()));
        if (!ShadowSuccessCalculator.roll(random, chance)) {
            retaliate(target, context.thief());
            return settle(ShadowTheftOutcome.FAILED_ROLL, context, null, null, audit,
                    settings, ShadowTheftOutcome.FAILED_ROLL);
        }

        // 11. clock check BEFORE the PENDING audit: a STABLE positive time
        //     snapshot is taken ONCE per attempt (8D.1.3 §1) and reused by
        //     every record — the time source is never read again after the
        //     asset commit.
        long startedAt = safeEpochMillis();
        if (startedAt <= 0L) {
            return settle(ShadowTheftOutcome.TRANSFER_FAILED, context, null,
                    "clock_unavailable", audit, settings,
                    ShadowTheftOutcome.TRANSFER_FAILED);
        }

        // 12. PENDING audit (explicit timestamp, never read again).
        ShadowAuditRecord pendingRecord = buildAuditRecord(context, startedAt, null, null,
                ShadowAuditState.PENDING, null);
        if (!safeAppend(audit, pendingRecord, "entity_prewrite")) {
            return refusal(context, "audit_prewrite_failed");
        }

        // 13. PENDING marker + read-back. Failure: attempt a marker restore
        //     FIRST; a failed restore MUST be RECOVERY_REQUIRED (8D.1.1 §1.8).
        ShadowLootState pending = ShadowLootState.pending(context.eventId(), thiefId, startedAt);
        if (!attachmentAccess.write(target, pending) || !pending.equals(attachmentAccess.read(target))) {
            boolean markerRestored = attachmentAccess.restore(target, pending);
            if (markerRestored) {
                if (!finaliseClean(audit, context, startedAt, "attachment_write_failed")) {
                    // audit did NOT close: never claim a clean finalisation
                    return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, null,
                            "attachment_write_failed; audit_clean_write_failed", audit);
                }
                return settle(ShadowTheftOutcome.FAILED_CLEAN, context, null,
                        "attachment_write_failed", audit);
            }
            return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, null,
                    "attachment_write_failed; unrestorable", audit);
        }

        // 14. slot commit.
        if (!transaction.commit()) {
            boolean itemRestored = transaction.rollback();
            boolean markerRestored = attachmentAccess.restore(target, pending); // independent
            if (itemRestored && markerRestored) {
                if (!finaliseClean(audit, context, startedAt, "delivery_commit_failed")) {
                    return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, null,
                            "delivery_commit_failed; audit_clean_write_failed", audit);
                }
                return settle(ShadowTheftOutcome.FAILED_CLEAN, context, null,
                        "delivery_commit_failed", audit, settings,
                        ShadowTheftOutcome.FAILED_CLEAN);
            }
            return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, null,
                    "delivery_commit_failed; unrestorable", audit);
        }

        // 15-17. POST-COMMIT settlement (8D.1.3 §1-2): any construction or
        //        clock failure after the asset commit rolls back and NEVER
        //        returns INVALID_CONTEXT. The stable `startedAt` snapshot is
        //        reused; `settings` is the attempt-initial snapshot; every
        //        auxiliary settlement is exception-isolated.
        try {
            // 15. LOOTED marker + read-back.
            ShadowLootState looted = ShadowLootState.looted(context.eventId(), entry.itemId(),
                    count, startedAt);
            if (!attachmentAccess.write(target, looted)
                    || !looted.equals(attachmentAccess.read(target))) {
                boolean itemRestored = transaction.rollback();
                // Last successfully written marker state is PENDING — the
                // restore must verify against it.
                boolean markerRestored = attachmentAccess.restore(target, pending);
                if (itemRestored && markerRestored) {
                    if (!finaliseClean(audit, context, startedAt, "attachment_commit_failed")) {
                        return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, null,
                                "attachment_commit_failed; audit_clean_write_failed", audit);
                    }
                    return settle(ShadowTheftOutcome.FAILED_CLEAN, context, null,
                            "attachment_commit_failed", audit, settings,
                            ShadowTheftOutcome.FAILED_CLEAN);
                }
                ShadowTheftReceipt receipt = ShadowTheftReceipt.item(entry.itemId(), count);
                return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, receipt,
                        "attachment_commit_failed; unrestorable", audit);
            }

            // 16. FINAL audit. Only THIS failure may keep PENDING.
            ShadowTheftReceipt receipt = ShadowTheftReceipt.item(entry.itemId(), count);
            ShadowAuditRecord finalRecord = buildAuditRecord(context, startedAt,
                    ShadowTheftOutcome.SUCCESS, receipt, ShadowAuditState.FINAL, null);
            if (!safeAppend(audit, finalRecord, "entity_final")) {
                boolean itemRestored = transaction.rollback();
                boolean markerRestored = attachmentAccess.restore(target, looted);
                if (itemRestored && markerRestored) {
                    return settle(ShadowTheftOutcome.ROLLED_BACK, context, null,
                            "audit_final_write_failed", audit, settings,
                            ShadowTheftOutcome.ROLLED_BACK);
                }
                return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, receipt,
                        "audit_final_write_failed; unrestorable", audit);
            }

            // 17. success settlement + single event: the Result carries the
            //     REAL eventPosted boolean (8D.1.2 §5).
            boolean posted = postEvent(context, receipt);
            settleCooldown(ShadowTheftOutcome.SUCCESS, context, settings);
            settleIdempotency(context);
            return new Result(ShadowTheftOutcome.SUCCESS, context.eventId(), posted, receipt,
                    null, context.targetType());
        } catch (RuntimeException | LinkageError e) {
            // Post-commit construction failure (clock/looted/final record):
            // roll back everything; NEVER report INVALID_CONTEXT (8D.1.3 §1).
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow entity post-commit settlement failed (event {}): {}",
                    context.eventId(), e.toString());
            boolean itemRestored = transaction.rollback();
            ShadowLootState current = attachmentAccess.read(target);
            ShadowLootState expected = current.state() == ShadowLootState.State.LOOTED
                    ? current : pending;
            boolean markerRestored = attachmentAccess.restore(target, expected);
            ShadowTheftReceipt receipt = ShadowTheftReceipt.item(entry.itemId(), count);
            if (itemRestored && markerRestored) {
                return settle(ShadowTheftOutcome.ROLLED_BACK, context, null,
                        "post_commit_exception", audit, settings, ShadowTheftOutcome.ROLLED_BACK);
            }
            return settle(ShadowTheftOutcome.RECOVERY_REQUIRED, context, receipt,
                    "post_commit_exception; unrestorable", audit);
        }
    }

    // ---- settlement ----

    /** Settles idempotency keys for every outcome after the audit health
     *  gate, and returns the result. */
    private Result settle(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                          @Nullable ShadowTheftReceipt receipt, @Nullable String failureReason,
                          ShadowAuditWriter audit) {
        settleIdempotency(context);
        return new Result(outcome, context.eventId(), false,
                receipt != null ? receipt : ShadowTheftReceipt.empty(), failureReason,
                context.targetType());
    }

    /** Settles + applies the cooldown matching the outcome, then returns. */
    private Result settle(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                          @Nullable ShadowTheftReceipt receipt, @Nullable String failureReason,
                          ShadowAuditWriter audit, ShadowFrameworkSettings settings,
                          ShadowTheftOutcome cooldownFor) {
        settleCooldown(cooldownFor, context, settings);
        return settle(outcome, context, receipt, failureReason, audit);
    }

    /** Cooldown marking, fully exception-isolated (8D.1.3 §2). */
    private void settleCooldown(ShadowTheftOutcome outcome, ShadowAttemptContext context,
                                ShadowFrameworkSettings settings) {
        try {
            UUID thiefId = context.thief().getUUID();
            switch (outcome) {
                case SUCCESS -> cooldownTracker.markGlobalCooldown(thiefId,
                        settings.globalCooldownTicks());
                case FAILED_ROLL, TRANSFER_FAILED, FAILED_CLEAN, ROLLED_BACK ->
                        cooldownTracker.markFailureCooldown(thiefId,
                                settings.failureCooldownTicks());
                case NO_CANDIDATE -> cooldownTracker.markNoCandidateCooldown(thiefId,
                        settings.noCandidateCooldownTicks());
                default -> {
                    // no cooldown
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // settlement is best-effort; never affects the outcome
        }
    }

    /** Idempotency settlement, fully exception-isolated (8D.1.3 §2). */
    private void settleIdempotency(ShadowAttemptContext context) {
        try {
            idempotencyTracker.markEventId(context.eventId());
            idempotencyTracker.markAttempt(context.thief().getUUID(), context.targetId(),
                    context.serverTick());
        } catch (RuntimeException | LinkageError e) {
            // settlement is best-effort; never affects the outcome
        }
    }

    /** Writes a FINAL FAILED_CLEAN audit record after a clean recovery. */
    private boolean finaliseClean(ShadowAuditWriter audit, ShadowAttemptContext context,
                                  long epochMillis, String reason) {
        ShadowAuditRecord record = buildAuditRecord(context, epochMillis,
                ShadowTheftOutcome.FAILED_CLEAN, null, ShadowAuditState.FINAL, reason);
        return safeAppend(audit, record, "entity_clean");
    }

    private Result refusal(ShadowAttemptContext context, String reason) {
        return settle(ShadowTheftOutcome.AUDIT_FAILED, context, null, reason, null);
    }

    // ---- attachment access ----

    interface AttachmentAccess {
        /** Existing attachment or {@code null}; never creates an AVAILABLE
         *  attachment on read (getExistingDataOrNull). */
        ShadowLootState read(Entity entity);

        boolean write(Entity entity, ShadowLootState state);

        /** Restores AVAILABLE: the current state MUST equal {@code expected}
         *  (the state this transaction wrote); removeData must actually land
         *  (re-read must be null). No-op / wrong state / exceptions → false. */
        boolean restore(Entity entity, ShadowLootState expected);
    }

    private static final AttachmentAccess DEFAULT_ATTACHMENT_ACCESS = new AttachmentAccess() {
        @Override
        public ShadowLootState read(Entity entity) {
            try {
                ShadowLootState state = entity.getExistingDataOrNull(
                        TcthShadowAttachments.SHADOW_LOOT_STATE.get());
                return state != null ? state : ShadowLootState.available();
            } catch (RuntimeException | LinkageError e) {
                return ShadowLootState.corrupt(); // unreadable → block
            }
        }

        @Override
        public boolean write(Entity entity, ShadowLootState state) {
            try {
                entity.setData(TcthShadowAttachments.SHADOW_LOOT_STATE.get(), state);
                return true;
            } catch (RuntimeException | LinkageError e) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow loot attachment write failed: {}", e.toString());
                return false;
            }
        }

        @Override
        public boolean restore(Entity entity, ShadowLootState expected) {
            try {
                ShadowLootState current = entity.getExistingDataOrNull(
                        TcthShadowAttachments.SHADOW_LOOT_STATE.get());
                if (current == null) {
                    return true; // already available
                }
                if (expected == null || !current.equals(expected)) {
                    return false; // wrong state: keep the blocking state
                }
                entity.removeData(TcthShadowAttachments.SHADOW_LOOT_STATE.get());
                ShadowLootState after = entity.getExistingDataOrNull(
                        TcthShadowAttachments.SHADOW_LOOT_STATE.get());
                return after == null; // a no-op removal must not report success
            } catch (RuntimeException | LinkageError e) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow loot attachment restore failed: {}", e.toString());
                return false;
            }
        }
    };

    private static AttachmentAccess attachmentAccess = DEFAULT_ATTACHMENT_ACCESS;

    // ---- hostile reaction (8D.1.1 §6): only explicit enemies ----

    private static void retaliate(Entity target, ServerPlayer thief) {
        if (target instanceof Mob mob && target instanceof Enemy) {
            try {
                mob.setTarget(thief);
                if (mob.getTarget() != thief) {
                    // reaction did not apply (cancelled / read-back mismatch) —
                    // recorded only, never affects the outcome.
                    ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                            "[TCTH] Shadow loot reaction did not apply (entity {})",
                            mob.getStringUUID());
                }
            } catch (RuntimeException | LinkageError e) {
                // reaction did not apply — outcome unaffected
            }
        }
    }

    // ---- helpers ----

    private boolean safeAppend(ShadowAuditWriter audit, ShadowAuditRecord record, String stage) {
        try {
            return audit.append(record);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow entity audit append failed ({}, event {}): {}", stage,
                    record.eventId(), e.toString());
            return false;
        }
    }

    private ShadowAuditRecord buildAuditRecord(ShadowAttemptContext context, long epochMillis,
                                               @Nullable ShadowTheftOutcome outcome,
                                               @Nullable ShadowTheftReceipt receipt,
                                               ShadowAuditState auditState,
                                               @Nullable String failureReason) {
        ShadowTheftReceipt r = receipt != null ? receipt : ShadowTheftReceipt.empty();
        return new ShadowAuditRecord(context.eventId(), context.thief().getUUID(), context.targetId(),
                context.targetKind(), context.targetType(), ShadowTheftType.ITEM, outcome, auditState,
                r.itemId(), r.itemCount(), r.numericAmount(), r.effectId(), r.effectDurationTicks(),
                epochMillis, context.serverTick(), context.level().dimension().location(),
                context.position(), failureReason);
    }

    private long safeEpochMillis() {
        try {
            long value = epochMillisSupplier.get();
            return value >= 0L ? value : 0L;
        } catch (RuntimeException | LinkageError e) {
            return 0L;
        }
    }

    private boolean postEvent(ShadowAttemptContext context, ShadowTheftReceipt receipt) {
        try {
            ShadowTheftEvent event = new ShadowTheftEvent(
                    context.eventId(), context.thief(), context.targetKind(), context.targetId(),
                    context.targetType(), ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS, receipt,
                    context.automated(), context.level(), context.position());
            return ShadowTheftEventDispatcher.publish(event) == ShadowTheftEventDispatcher.Result.POSTED;
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow entity loot event post failed (event {}): {}", context.eventId(), e.toString());
            return false;
        }
    }

    // ---- test hooks (not part of the public API) ----

    static void setAttachmentAccessForTesting(AttachmentAccess access) {
        attachmentAccess = access != null ? access : DEFAULT_ATTACHMENT_ACCESS;
    }

    static void resetForTesting() {
        attachmentAccess = DEFAULT_ATTACHMENT_ACCESS;
    }
}
