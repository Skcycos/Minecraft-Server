package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

/**
 * A single candidate theft type inside the draw pool.
 *
 * <p>Pure data: it carries no transaction objects, no player inventories and
 * no entity references. It only describes the <em>availability</em> of a
 * theft type and the modifiers that influence the success roll:
 *
 * @param type             the theft type
 * @param weight           the positive draw weight (relative to the other
 *                         candidates in the pool)
 * @param successModifier  an additive modifier applied to the base success
 *                         chance for this candidate (e.g. the high-value-item
 *                         penalty lives here)
 * @param highValue        whether this candidate represents an above-average
 *                         reward (used by the success layer; never changes the
 *                         draw weights)
 */
public record ShadowCandidate(ShadowTheftType type, int weight, double successModifier, boolean highValue) {

    public ShadowCandidate {
        Objects.requireNonNull(type, "type");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
        if (!Double.isFinite(successModifier)) {
            throw new IllegalArgumentException("successModifier must be finite: " + successModifier);
        }
    }

    /**
     * @return a plain candidate with the given positive weight and no success
     *         modifier
     */
    public static ShadowCandidate plain(ShadowTheftType type, int weight) {
        return new ShadowCandidate(type, weight, 0.0d, false);
    }
}
