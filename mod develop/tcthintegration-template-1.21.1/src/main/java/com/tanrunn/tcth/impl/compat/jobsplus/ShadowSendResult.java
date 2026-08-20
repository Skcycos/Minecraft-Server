package com.tanrunn.tcth.impl.compat.jobsplus;

/**
 * Explicit result of an Arc action send attempt (phase 8E.2.1).
 *
 * <p>Replaces nullable {@code ActionResult} to make the three outcomes
 * unambiguous at the type level:
 * <ul>
 *   <li>{@link #SUCCESS} — the Arc action returned a result (the XP was
 *       processed by Arc; commit the reservation);</li>
 *   <li>{@link #CLEAR_FAILURE} — the send clearly failed (e.g. the
 *       dispatcher caught an exception); release the reservation so the
 *       attempt can be retried;</li>
 *   <li>{@link #UNKNOWN} — the outcome is indeterminate (e.g. the sender
 *       threw an exception that escaped the dispatcher); keep the
 *       reservation occupied to prevent duplicate XP.</li>
 * </ul>
 */
public enum ShadowSendResult {
    SUCCESS,
    CLEAR_FAILURE,
    UNKNOWN;

    static ShadowSendResult success() {
        return SUCCESS;
    }

    static ShadowSendResult clearFailure() {
        return CLEAR_FAILURE;
    }

    static ShadowSendResult unknown() {
        return UNKNOWN;
    }
}
