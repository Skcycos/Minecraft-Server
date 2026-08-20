package com.tanrunn.tcth.impl.shadow;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;

/**
 * Shared read-only feasibility rules (8C.1.3 §5).
 *
 * <p>Both the candidate probe ({@link PlayerReadonlyCandidateProvider}) and
 * the transaction engine ({@link PlayerAssetTransferExecutor}) must agree on
 * which theft types are currently available. The rules live here exactly once
 * so the two sides can never drift apart:
 * <ul>
 *   <li>{@link #effectIsCandidateFor} — whitelist / blacklist / beneficial /
 *       finite / non-ambient <em>and</em> the thief does not already hold the
 *       effect (the safest strategy);</li>
 *   <li>{@link #computeHungerPlan} — the full food + saturation conservation
 *       feasibility (victim floor, thief cap, {@code 0 <= saturation <=
 *       foodLevel} on both sides after the transfer, within the 1-point
 *       saturation budget); returns the immutable plan, or {@code null} when
 *       no conserving plan exists.</li>
 * </ul>
 *
 * <p>All functions are strictly read-only.
 */
public final class ShadowFeasibility {

    private ShadowFeasibility() {
    }

    /** Whitelist + blacklist + beneficial + finite + non-ambient (8C.1 §6). */
    public static boolean isStealableEffect(MobEffectInstance instance) {
        if (instance.isInfiniteDuration() || instance.getDuration() <= 0) {
            return false;
        }
        if (instance.isAmbient()) {
            return false;
        }
        if (!instance.getEffect().value().isBeneficial()) {
            return false;
        }
        if (!instance.getEffect().is(ShadowTags.STEALABLE_EFFECTS)) {
            return false;
        }
        return !instance.getEffect().is(ShadowTags.UNSTEALABLE_EFFECTS);
    }

    /**
     * Whether the given effect is a candidate for the thief: stealable by the
     * shared rules AND the thief does not already hold the same effect
     * (8C.1.1 §5 safest strategy, 8C.1.3 §5 shared with the probe).
     */
    public static boolean effectIsCandidateFor(ServerPlayer thief, MobEffectInstance instance) {
        return isStealableEffect(instance) && !thief.hasEffect(instance.getEffect());
    }

    /**
     * Computes the conserving HUNGER plan for the given food data and the
     * desired food-point transfer (phase 8E: the tier-adjusted transfer from
     * {@link ShadowAbilityValues#lifeSiphonHungerTransfer}), or {@code null}
     * when no plan can keep {@code 0 <= saturation <= foodLevel} on both
     * sides within the 1-point saturation budget.
     *
     * <p>The candidate probe and the engine's {@code prepareHunger} MUST call
     * this with the SAME desired transfer — the shared numeric source, so an
     * ability-tier change can never make a candidate "available" while
     * prepare (without drift) returns {@code null}.
     *
     * @param desiredFoodTransfer the tier's desired food-point transfer
     *                            (positive; e.g. 2 / 3 / 4)
     */
    @Nullable
    public static HungerPlan computeHungerPlan(FoodData victimFood, FoodData thiefFood,
                                               int desiredFoodTransfer) {
        int victimLevel = victimFood.getFoodLevel();
        int thiefLevel = thiefFood.getFoodLevel();
        float victimSat = victimFood.getSaturationLevel();
        float thiefSat = thiefFood.getSaturationLevel();
        if (victimLevel <= HungerPlan.HUNGER_FLOOR || thiefLevel >= HungerPlan.MAX_FOOD) {
            return null;
        }
        // An already-illegal saturation state cannot be transferred legally.
        if (victimSat > victimLevel || thiefSat > thiefLevel) {
            return null;
        }
        int foodTransfer = Math.min(Math.max(desiredFoodTransfer, 0),
                Math.min(victimLevel - HungerPlan.HUNGER_FLOOR,
                        HungerPlan.MAX_FOOD - thiefLevel));
        if (foodTransfer <= 0) {
            return null;
        }
        // Feasible saturation range keeping 0 <= sat <= foodLevel on BOTH
        // sides after the transfer, within the 1-point budget (8C.1.1 §4).
        float satLow = Math.max(0.0f, victimSat - (victimLevel - foodTransfer));
        float satHigh = Math.min(HungerPlan.MAX_SATURATION_TRANSFER,
                Math.min((thiefLevel + foodTransfer) - thiefSat, victimSat));
        if (satLow > satHigh) {
            return null; // conservation within the budget is infeasible
        }
        return new HungerPlan(victimLevel, victimSat, thiefLevel, thiefSat,
                foodTransfer, satHigh);
    }
}
