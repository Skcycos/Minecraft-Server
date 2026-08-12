package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable audit record of a single shadow theft attempt (8B.1.1 schema v1).
 *
 * <p>Deliberately free of {@code ItemStack}s, NBT, item components and
 * account objects — only scalar facts are persisted. All scalar fields are
 * validated as finite and non-negative.
 *
 * <p>Consistency rules (8B.1 §3 + 8B.1.1 §2):
 * <ul>
 *   <li>ENTITY targets must carry a {@code targetType}; PLAYER targets must
 *       not;</li>
 *   <li>asset fields (item / numeric / effect) are only allowed on the
 *       asset-carrying outcomes {@code SUCCESS} (always) and
 *       {@code RECOVERY_REQUIRED} (when the record actually carries assets);
 *       every other outcome (including PENDING and FAILED_ROLL /
 *       TRANSFER_FAILED / ROLLED_BACK) must have all asset fields at their
 *       default — e.g. a FAILED_ROLL for theft type EFFECT legitimately has
 *       {@code effectId == null};</li>
 *   <li>an asset-carrying record must be consistent with the final
 *       {@code theftType}: ITEM → item fields only; COIN/HEALTH/HUNGER →
 *       numericAmount only; EFFECT → effectId + duration only;</li>
 *   <li>{@code timestampEpochMillis} (wall clock) and {@code serverTick} are
 *       separate fields and never conflated;</li>
 *   <li>{@code outcome} is {@code null} only while {@code auditState} is
 *       {@code PENDING}; a {@code FINAL} record always has an outcome;</li>
 *   <li>{@code position} is defensively copied to an immutable
 *       {@code BlockPos};</li>
 *   <li>{@code failureReason} is length-limited (256 chars) and must not
 *       contain control characters — never a stack trace or asset NBT.</li>
 * </ul>
 */
public record ShadowAuditRecord(UUID eventId, UUID thiefId, UUID targetId, ShadowTargetKind targetKind,
                                @Nullable ResourceLocation targetType, @Nullable ShadowTheftType theftType,
                                @Nullable ShadowTheftOutcome outcome, ShadowAuditState auditState,
                                @Nullable ResourceLocation itemId, int itemCount, double numericAmount,
                                @Nullable ResourceLocation effectId, int effectDurationTicks,
                                long timestampEpochMillis, long serverTick, ResourceLocation dimension,
                                @Nullable BlockPos position, @Nullable String failureReason) {

    /** Maximum length of the failure reason; longer values are rejected. */
    public static final int MAX_FAILURE_REASON_LENGTH = 256;

    public ShadowAuditRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(thiefId, "thiefId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(auditState, "auditState");
        Objects.requireNonNull(dimension, "dimension");
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be non-negative: " + itemCount);
        }
        if (effectDurationTicks < 0) {
            throw new IllegalArgumentException("effectDurationTicks must be non-negative: " + effectDurationTicks);
        }
        if (!Double.isFinite(numericAmount) || numericAmount < 0.0d) {
            throw new IllegalArgumentException("numericAmount must be finite and non-negative: " + numericAmount);
        }
        if (timestampEpochMillis < 0L) {
            throw new IllegalArgumentException("timestampEpochMillis must be non-negative: " + timestampEpochMillis);
        }
        if (serverTick < 0L) {
            throw new IllegalArgumentException("serverTick must be non-negative: " + serverTick);
        }
        // Entity targets must record their type; player targets must not.
        // (Also enforced early in the coordinator's context validation —
        // this constructor is the last line of defence.)
        if (targetKind == ShadowTargetKind.ENTITY && targetType == null) {
            throw new IllegalArgumentException("targetType is required for ENTITY targets");
        }
        if (targetKind == ShadowTargetKind.PLAYER && targetType != null) {
            throw new IllegalArgumentException("targetType must be null for PLAYER targets");
        }
        // Outcome/state consistency.
        if (auditState == ShadowAuditState.PENDING && outcome != null) {
            throw new IllegalArgumentException("a PENDING record must not carry a final outcome");
        }
        if (auditState == ShadowAuditState.FINAL && outcome == null) {
            throw new IllegalArgumentException("a FINAL record must carry an outcome");
        }
        // Asset-field consistency, keyed on the outcome (8B.1.1 §2).
        boolean carriesAssets = itemId != null || itemCount != 0 || numericAmount != 0.0d
                || effectId != null || effectDurationTicks != 0;
        if (outcome == ShadowTheftOutcome.SUCCESS) {
            // SUCCESS always follows a committed, type-checked receipt.
            if (theftType == null) {
                throw new IllegalArgumentException("theftType is required for SUCCESS records");
            }
            requireScalarsMatch(theftType, itemId, itemCount, numericAmount, effectId, effectDurationTicks);
        } else if (outcome == ShadowTheftOutcome.RECOVERY_REQUIRED) {
            if (theftType == null) {
                throw new IllegalArgumentException("theftType is required for RECOVERY_REQUIRED records");
            }
            if (carriesAssets) {
                requireScalarsMatch(theftType, itemId, itemCount, numericAmount, effectId, effectDurationTicks);
            }
            // Empty scalars are allowed: the asset state is ambiguous (e.g. a
            // committed receipt whose type did not match the drawn one).
        } else {
            // PENDING (outcome null) and every non-asset outcome (FAILED_ROLL,
            // TRANSFER_FAILED, ROLLED_BACK, …) must carry no assets — a
            // theftType of EFFECT with effectId == null is perfectly legal
            // here.
            if (carriesAssets) {
                throw new IllegalArgumentException(
                        "asset fields are not allowed for outcome " + outcome);
            }
        }
        // Failure reason: bounded and control-character free.
        if (failureReason != null) {
            if (failureReason.length() > MAX_FAILURE_REASON_LENGTH) {
                throw new IllegalArgumentException("failureReason exceeds " + MAX_FAILURE_REASON_LENGTH + " chars");
            }
            for (int i = 0; i < failureReason.length(); i++) {
                if (Character.isISOControl(failureReason.charAt(i))) {
                    throw new IllegalArgumentException("failureReason must not contain control characters");
                }
            }
        }
        // Defensive immutable copy of the position.
        position = position == null ? null : position.immutable();
    }

    /** Verifies that the asset scalars exactly match the given theft type. */
    private static void requireScalarsMatch(ShadowTheftType theftType, @Nullable ResourceLocation itemId,
                                            int itemCount, double numericAmount,
                                            @Nullable ResourceLocation effectId, int effectDurationTicks) {
        boolean ok = switch (theftType) {
            case ITEM -> itemId != null && itemCount > 0 && numericAmount == 0.0d
                    && effectId == null && effectDurationTicks == 0;
            case COIN, HEALTH, HUNGER -> itemId == null && itemCount == 0
                    && numericAmount > 0.0d && effectId == null && effectDurationTicks == 0;
            case EFFECT -> itemId == null && itemCount == 0 && numericAmount == 0.0d
                    && effectId != null && effectDurationTicks > 0;
        };
        if (!ok) {
            throw new IllegalArgumentException("asset fields are inconsistent with theftType " + theftType
                    + " (itemId=" + itemId + " itemCount=" + itemCount + " numericAmount=" + numericAmount
                    + " effectId=" + effectId + " effectDurationTicks=" + effectDurationTicks + ")");
        }
    }
}
