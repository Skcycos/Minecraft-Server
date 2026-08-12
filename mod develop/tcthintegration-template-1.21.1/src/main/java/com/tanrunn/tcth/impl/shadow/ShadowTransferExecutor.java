package com.tanrunn.tcth.impl.shadow;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.RandomSource;

/**
 * Two-phase transfer transaction for a drawn candidate (8C.1).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #prepare} is <em>read-only</em>: it re-validates the asset,
 *       may select a concrete object (ITEM stack / EFFECT) with <em>its
 *       own</em> single random call, and returns an immutable
 *       {@link ShadowTransferPlan} holding only UUIDs, slots, values and
 *       defensive snapshots — or {@code null} when the asset is gone (the
 *       theft type is <em>never</em> re-drawn);</li>
 *   <li>{@link #commit} re-validates the world against the plan's snapshots,
 *       executes the full atomic transfer and distinguishes
 *       {@link ShadowTransferState#COMMITTED} /
 *       {@link ShadowTransferState#FAILED_CLEAN} /
 *       {@link ShadowTransferState#RECOVERY_REQUIRED}; any exception after a
 *       partial change triggers an <em>internal</em> rollback — a failed
 *       internal rollback is never reported as a plain failure;</li>
 *   <li>{@link #rollback} restores the pre-commit state; the coordinator
 *       calls it exactly once when the final audit write fails.</li>
 * </ul>
 */
public interface ShadowTransferExecutor {

    /**
     * Read-only validation, concrete-asset selection and planning. Must not
     * modify any asset.
     *
     * @param context  the immutable attempt context
     * @param selected the candidate the coordinator drew (exactly one per
     *                 attempt; never re-drawn here)
     * @param random   the coordinator's random source; may be used for at
     *                 most one concrete-asset selection call
     * @return the immutable transaction plan, or {@code null} when the
     *         transfer cannot proceed (nothing happens)
     */
    @Nullable
    ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected, RandomSource random);

    /**
     * Executes the full atomic transfer.
     *
     * @param context  the immutable attempt context
     * @param selected the drawn candidate
     * @param plan     the plan returned by {@link #prepare}
     * @return the transfer result; committed, clean-failed or
     *         recovery-required
     */
    ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected, ShadowTransferPlan plan);

    /**
     * Restores the pre-commit state after a committed transfer whose final
     * audit write failed.
     *
     * @param context  the immutable attempt context
     * @param selected the drawn candidate
     * @param plan     the plan returned by {@link #prepare}
     * @return {@code true} when the restore succeeded
     */
    boolean rollback(ShadowAttemptContext context, ShadowCandidate selected, ShadowTransferPlan plan);
}
