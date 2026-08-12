package com.tanrunn.tcth.impl.shadow;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

/**
 * Immutable HEALTH transfer plan (8C.1).
 *
 * <p>Stores both sides' original health so commit re-validation, the internal
 * rollback and the outer rollback can restore the exact values.
 *
 * @param victimHealthBefore the victim's health BEFORE the commit
 * @param thiefHealthBefore  the thief's health BEFORE the commit
 * @param transfer           the planned transfer (base 1 point, capped by the
 *                           victim floor; recomputed at commit)
 */
public record HealthPlan(float victimHealthBefore, float thiefHealthBefore, float transfer)
        implements ShadowTransferPlan {

    /** Base health transfer per theft (8C.1 §4). */
    public static final float BASE_TRANSFER = 1.0f;

    public HealthPlan {
        if (!Float.isFinite(victimHealthBefore) || victimHealthBefore < 0.0f
                || !Float.isFinite(thiefHealthBefore) || thiefHealthBefore < 0.0f
                || !Float.isFinite(transfer) || transfer < 0.0f) {
            throw new IllegalArgumentException("health plan values must be finite and non-negative");
        }
    }

    @Override
    public ShadowTheftType type() {
        return ShadowTheftType.HEALTH;
    }
}
