package com.tanrunn.tcth.impl.shadow;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;

/**
 * The outcome of a transfer commit (8C.1).
 *
 * <p>Exactly one of three states is possible:
 * <ul>
 *   <li>{@link ShadowTransferState#COMMITTED} with a non-null receipt — the
 *       whole transfer completed atomically and the receipt describes exactly
 *       what moved (it must match the drawn candidate's type);</li>
 *   <li>{@link ShadowTransferState#FAILED_CLEAN} with a failure reason —
 *       nothing was moved and nothing was lost (the executor rolled back any
 *       partial change internally);</li>
 *   <li>{@link ShadowTransferState#RECOVERY_REQUIRED} with a receipt — the
 *       transfer may have partially moved and the internal rollback failed;
 *       the asset state is unknown and operator intervention is required.
 *       Never reported as a plain failure.</li>
 * </ul>
 *
 * @param state         the commit state
 * @param receipt       the committed receipt on COMMITTED / RECOVERY_REQUIRED,
 *                      or {@code null} on FAILED_CLEAN
 * @param failureReason the machine-readable failure reason on FAILED_CLEAN
 *                      (and on RECOVERY_REQUIRED), or {@code null} on success
 */
public record ShadowTransferResult(ShadowTransferState state, @Nullable ShadowTheftReceipt receipt,
                                   @Nullable String failureReason) {

    public ShadowTransferResult {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (state == ShadowTransferState.COMMITTED && receipt == null) {
            throw new IllegalArgumentException("a committed transfer requires a receipt");
        }
        if (state == ShadowTransferState.FAILED_CLEAN && receipt != null) {
            throw new IllegalArgumentException("a clean failure must not carry a receipt");
        }
        if (state == ShadowTransferState.RECOVERY_REQUIRED && receipt == null) {
            throw new IllegalArgumentException("a recovery-required result must carry a receipt");
        }
    }

    /**
     * @return a committed result carrying the given receipt
     */
    public static ShadowTransferResult committed(ShadowTheftReceipt receipt) {
        return new ShadowTransferResult(ShadowTransferState.COMMITTED, receipt, null);
    }

    /**
     * @return a clean failure carrying the given failure reason (nothing moved)
     */
    public static ShadowTransferResult failed(String failureReason) {
        return new ShadowTransferResult(ShadowTransferState.FAILED_CLEAN, null, failureReason);
    }

    /**
     * @return a recovery-required result: the transfer may have moved assets
     *         and the internal rollback failed
     */
    public static ShadowTransferResult recoveryRequired(String failureReason, ShadowTheftReceipt receipt) {
        return new ShadowTransferResult(ShadowTransferState.RECOVERY_REQUIRED, receipt, failureReason);
    }

    /**
     * @return whether the whole transaction committed atomically
     */
    public boolean committed() {
        return state == ShadowTransferState.COMMITTED;
    }
}
