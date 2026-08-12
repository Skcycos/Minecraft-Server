package com.tanrunn.tcth.api.shadow;

/**
 * The final, immutable result of a shadow theft attempt.
 *
 * <p>Every attempt that reaches the coordination stage produces exactly one
 * outcome. Reward systems must only ever settle on {@link #SUCCESS}; all other
 * outcomes carry an empty {@link ShadowTheftReceipt} except
 * {@link #RECOVERY_REQUIRED} (which carries the committed receipt so the
 * operator knows exactly what may have moved).
 *
 * <p><b>State ownership (8B.1):</b>
 * <ul>
 *   <li>{@code PENDING} is an <em>audit-internal</em> state — it only exists
 *       as {@code ShadowAuditState.PENDING} on the pre-write audit record and
 *       is never a public outcome;</li>
 *   <li>{@link #ROLLED_BACK} and {@link #RECOVERY_REQUIRED} are <em>public
 *       outcomes</em> of the transfer settlement;</li>
 *   <li>{@link #AUDIT_FAILED} is the framework-level refusal when the audit
 *       is disabled or unavailable <em>before</em> any provider/random/asset
 *       operation — it never carries a committed receipt (the
 *       committed-but-unrecorded state became ROLLED_BACK / RECOVERY_REQUIRED
 *       in 8B.1).</li>
 * </ul>
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this enum may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 */
public enum ShadowTheftOutcome {
    /** The theft type was drawn, the success roll passed, the transfer
     *  transaction committed atomically and the audit record was finalised
     *  (COMMITTED). The success event was posted. */
    SUCCESS,
    /** The attempt passed all pre-checks but the single success roll failed. */
    FAILED_ROLL,
    /** No theft type had any currently available candidate. */
    NO_CANDIDATE,
    /** The protection service denied the attempt (area, new player, target,
     *  self, gamemode or unknown). */
    PROTECTED,
    /** A cooldown (global, no-candidate, failure or victim protection) is
     *  still active. */
    COOLDOWN,
    /** The exact same attempt was already handled: same eventId or the same
     *  thief + target + tick key. */
    DUPLICATE,
    /** The attempt context was invalid (not server side, null ids, FakePlayer
     *  thief, disabled target kind, …). */
    INVALID_CONTEXT,
    /** The transfer failed (prepare or commit) and nothing was committed; no
     *  other type was drawn. */
    TRANSFER_FAILED,
    /** The audit is disabled or unavailable (or the pre-write record could
     *  not be written) and the attempt was refused <em>before</em> any
     *  provider / random / asset operation. No receipt, no event: this is a
     *  framework-level refusal, not an attempt result. */
    AUDIT_FAILED,
    /** The transfer committed but the final audit write failed; the rollback
     *  succeeded, so nothing was moved. Receipt is empty. */
    ROLLED_BACK,
    /** Severe error state: the transfer committed and the final audit write
     *  failed, then the rollback <em>also</em> failed — the asset state is
     *  unknown and operator intervention is required. The committed receipt
     *  is carried so the operator knows what may have moved. Never reported
     *  as TRANSFER_FAILED or SUCCESS. */
    RECOVERY_REQUIRED,
    /** The framework master switch (or the shadow thief switch) is disabled. */
    FRAMEWORK_DISABLED
}
