package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import net.minecraft.util.RandomSource;

/**
 * Success-chance calculation and the single success roll (stage 8A §10,
 * phase 8B).
 *
 * <p>Formula (values are phase-8B suggestions, final balance is later):
 * <pre>
 *   chance = baseChance
 *          + 0.25 if behind
 *          - 0.25 if watched
 *          - 0.20 if alerted
 *          - 0.02 * max(0, distance - 1.5)
 *          + candidateModifier
 *          + abilityModifier          (always 0 in 8B)
 *   chance = clamp(chance, minChance, maxChance)   (defaults 0.05 .. 0.85)
 * </pre>
 *
 * <p>Fail-closed rules:
 * <ul>
 *   <li>any non-finite input (including a non-finite clamp) yields the
 *       constant lower clamp — an unpredictable success must never happen;</li>
 *   <li>the success roll calls the random source exactly once and uses a
 *       single, explicit {@code <} comparison ({@link #roll(RandomSource, double)}):
 *       {@code nextDouble() < chance}; at the exact boundary the roll fails
 *       (fail-safe for the target).</li>
 * </ul>
 */
public final class ShadowSuccessCalculator {

    /** Base success chance (stage 8A §10). */
    public static final double BASE_CHANCE = 0.35d;
    /** Bonus when the thief is behind the target. */
    public static final double BEHIND_BONUS = 0.25d;
    /** Penalty when the target watches the thief. */
    public static final double WATCHED_PENALTY = 0.25d;
    /** Penalty when the target is alerted. */
    public static final double ALERTED_PENALTY = 0.20d;
    /** Distance beyond which a decay starts (blocks). */
    public static final double DISTANCE_DECAY_START = 1.5d;
    /** Per-block distance decay beyond {@link #DISTANCE_DECAY_START}. */
    public static final double DISTANCE_DECAY_PER_BLOCK = 0.02d;
    /** Absolute lower clamp — a success chance never goes below this. */
    public static final double MIN_CHANCE = 0.05d;
    /** Absolute upper clamp — a success chance never reaches 100%. */
    public static final double MAX_CHANCE = 0.85d;

    private ShadowSuccessCalculator() {
    }

    /**
     * Computes the clamped success chance from an immutable context.
     *
     * @param context the immutable success context
     * @return the clamped chance in {@code [minChance, maxChance]}, or
     *         {@link #MIN_CHANCE} for any non-finite input (fail-closed)
     */
    public static double calculate(ShadowSuccessContext context) {
        Objects.requireNonNull(context, "context");
        double chance = context.baseChance()
                + (context.behind() ? BEHIND_BONUS : 0.0d)
                - (context.watched() ? WATCHED_PENALTY : 0.0d)
                - (context.alerted() ? ALERTED_PENALTY : 0.0d)
                - DISTANCE_DECAY_PER_BLOCK * Math.max(0.0d, context.distance() - DISTANCE_DECAY_START)
                + context.candidateModifier()
                + context.abilityModifier();
        if (!Double.isFinite(chance)
                || !Double.isFinite(context.baseChance())
                || !Double.isFinite(context.distance())
                || !Double.isFinite(context.candidateModifier())
                || !Double.isFinite(context.abilityModifier())
                || !Double.isFinite(context.minChance())
                || !Double.isFinite(context.maxChance())) {
            return MIN_CHANCE;
        }
        return Math.max(context.minChance(), Math.min(context.maxChance(), chance));
    }

    /**
     * Performs the single success roll.
     *
     * <p>Uses the explicit comparison {@code random.nextDouble() < chance}
     * with a single call to the random source. A roll at exactly {@code
     * chance} fails.
     *
     * @param random the random source (server random in production)
     * @param chance the clamped success chance
     * @return {@code true} on success
     */
    public static boolean roll(RandomSource random, double chance) {
        Objects.requireNonNull(random, "random");
        if (!Double.isFinite(chance)) {
            return false; // fail-closed: a non-finite chance can never succeed
        }
        return random.nextDouble() < chance;
    }
}
