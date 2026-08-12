package com.tanrunn.tcth.impl.shadow;

/**
 * Immutable inputs of the success-chance calculation.
 *
 * <p>Every input is captured before the attempt's success stage and never
 * mutated afterwards. The line-of-sight flag is reserved for a future phase
 * (real ray-cast) and is not used by the 8B formula.
 *
 * @param baseChance        the base chance, default 0.35
 * @param behind            whether the thief is behind the target
 * @param watched           whether the target is watching the thief
 * @param alerted           whether the target is in an alert state
 * @param distance          the distance in blocks between thief and target
 * @param candidateModifier the additive modifier of the drawn candidate
 *                          (e.g. the high-value-item penalty)
 * @param abilityModifier   the additive modifier from the ability tree;
 *                          always 0 in phase 8B but kept as an input
 * @param minChance         the lower clamp, default 0.05
 * @param maxChance         the upper clamp, default 0.85 (never 100%)
 */
public record ShadowSuccessContext(double baseChance, boolean behind, boolean watched, boolean alerted,
                                   double distance, double candidateModifier, double abilityModifier,
                                   double minChance, double maxChance) {

    /** Default base chance (stage 8A §10). */
    public static final double DEFAULT_BASE_CHANCE = 0.35d;
    /** Default lower clamp (stage 8A §10). */
    public static final double DEFAULT_MIN_CHANCE = 0.05d;
    /** Default upper clamp (stage 8A §10). */
    public static final double DEFAULT_MAX_CHANCE = 0.85d;

    /**
     * @return a context with the stage-8A defaults (base 0.35, clamp
     *         0.05..0.85, no modifiers)
     */
    public static ShadowSuccessContext standard() {
        return new ShadowSuccessContext(DEFAULT_BASE_CHANCE, false, false, false, 0.0d,
                0.0d, 0.0d, DEFAULT_MIN_CHANCE, DEFAULT_MAX_CHANCE);
    }
}
