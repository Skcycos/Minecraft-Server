package com.tanrunn.tcth.impl.shadow;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

/**
 * Immutable HUNGER transfer plan (8C.1).
 *
 * <p>Stores both sides' original food level and saturation so the commit can
 * re-validate and both rollback paths can restore the exact values.
 *
 * @param victimFoodBefore the victim's food level BEFORE the commit
 * @param victimSatBefore  the victim's saturation BEFORE the commit
 * @param thiefFoodBefore  the thief's food level BEFORE the commit
 * @param thiefSatBefore   the thief's saturation BEFORE the commit
 * @param foodTransfer     the planned food-point transfer (base 2, capped by
 *                         the victim floor and the thief's 20 cap)
 * @param satTransfer      the planned saturation transfer (small, capped on
 *                         both sides)
 */
public record HungerPlan(int victimFoodBefore, float victimSatBefore, int thiefFoodBefore,
                         float thiefSatBefore, int foodTransfer, float satTransfer)
        implements ShadowTransferPlan {

    /** Base food-point transfer per theft (8C.1 §5). */
    public static final int BASE_FOOD_TRANSFER = 2;
    /** Maximum saturation transfer per theft ("少量 saturation", 8C.1 §5). */
    public static final float MAX_SATURATION_TRANSFER = 1.0f;
    /** Hunger floor of the victim (stage 8A §5.4). */
    public static final int HUNGER_FLOOR = 4;
    /** Maximum food level of a player. */
    public static final int MAX_FOOD = 20;
    /** Maximum saturation of a player. */
    public static final float MAX_SATURATION = 20.0f;

    public HungerPlan {
        if (victimFoodBefore < 0 || victimFoodBefore > MAX_FOOD
                || thiefFoodBefore < 0 || thiefFoodBefore > MAX_FOOD
                || !Float.isFinite(victimSatBefore) || victimSatBefore < 0.0f || victimSatBefore > MAX_SATURATION
                || !Float.isFinite(thiefSatBefore) || thiefSatBefore < 0.0f || thiefSatBefore > MAX_SATURATION
                || foodTransfer < 0
                || !Float.isFinite(satTransfer) || satTransfer < 0.0f) {
            throw new IllegalArgumentException("hunger plan values out of range");
        }
    }

    @Override
    public ShadowTheftType type() {
        return ShadowTheftType.HUNGER;
    }
}
