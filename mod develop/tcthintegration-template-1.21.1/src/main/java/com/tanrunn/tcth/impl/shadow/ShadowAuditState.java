package com.tanrunn.tcth.impl.shadow;

/**
 * Lifecycle state of an audit record (8B.1).
 *
 * <p>{@code PENDING} is strictly <em>audit-internal</em>: it marks the
 * pre-write record created before a transfer commits and never appears as a
 * public {@code ShadowTheftOutcome}. A record that is still {@code PENDING}
 * when its eventId is seen again signals an unresolved crash window and is
 * reported to the coordinator as {@code ShadowTheftOutcome#RECOVERY_REQUIRED}.
 */
public enum ShadowAuditState {
    /** Pre-write record before the transfer commit; not a final state. */
    PENDING,
    /** Final state; the record carries the definitive outcome. */
    FINAL
}
