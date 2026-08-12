package com.tanrunn.tcth.impl.shadow;

/**
 * Commit state of a transfer transaction (8C.1 §1).
 *
 * <ul>
 *   <li>{@link #COMMITTED} — the whole transfer completed atomically;</li>
 *   <li>{@link #FAILED_CLEAN} — nothing was moved (the executor rolled back
 *       any partial change internally);</li>
 *   <li>{@link #RECOVERY_REQUIRED} — the transfer may have partially moved
 *       and the internal rollback failed; the asset state is unknown and
 *       operator intervention is required.</li>
 * </ul>
 */
public enum ShadowTransferState {
    COMMITTED,
    FAILED_CLEAN,
    RECOVERY_REQUIRED
}
