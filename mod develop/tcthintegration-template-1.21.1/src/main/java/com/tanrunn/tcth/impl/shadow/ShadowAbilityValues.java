package com.tanrunn.tcth.impl.shadow;

/**
 * Single source of the {@code tcth:shadow_thief} ability-route NUMBERS (phase
 * 8E).
 *
 * <p>Both the candidate probe and the transfer engine read their tier values
 * from here — the "候选池可行性与 prepare 必须共享同一个数值来源" rule — and
 * the cooldown / success layers read theirs too, so a tier value can never
 * drift between layers. Every returned value is finite; tiers never stack
 * (only the highest active tier of a route applies).
 */
public final class ShadowAbilityValues {

    private ShadowAbilityValues() {
    }

    // ---- 妙手路线 (sleight) ----

    /** Final success-chance bonus by tier (+0 / +0.05 / +0.10 / +0.15). */
    public static double sleightSuccessBonus(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE -> 0.0d;
            case I -> 0.05d;
            case II -> 0.10d;
            case III -> 0.15d;
        };
    }

    /** Per-tier global-cooldown reduction in ticks (200 → 180 / 160 / 140). */
    public static final long SLEIGHT_COOLDOWN_REDUCTION_PER_TIER = 20L;

    /**
     * The thief's global action cooldown with the sleight reduction applied
     * (200 → 180 / 160 / 140 ticks), clamped at zero.
     *
     * <p>Equivalent safe arithmetic (8E.1 §5): {@code base <= reduction} →
     * {@code 0}; otherwise {@code base - reduction}. A {@code Long.MAX_VALUE}
     * base therefore yields {@code Long.MAX_VALUE - reduction} — never
     * {@code Long.MAX_VALUE} itself, and no overflow is possible because the
     * reduction is a small positive constant.
     */
    public static long sleightGlobalCooldownTicks(long baseCooldownTicks, ShadowAbilityTier tier) {
        long reduction = switch (tier) {
            case NONE -> 0L;
            case I -> SLEIGHT_COOLDOWN_REDUCTION_PER_TIER;
            case II -> 2L * SLEIGHT_COOLDOWN_REDUCTION_PER_TIER;
            case III -> 3L * SLEIGHT_COOLDOWN_REDUCTION_PER_TIER;
        };
        if (baseCooldownTicks <= reduction) {
            return 0L;
        }
        return baseCooldownTicks - reduction;
    }

    /** High-value item success penalty by tier (-0.10 / -0.10 / -0.05 / 0). */
    public static double highValueModifier(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I -> ItemPlan.HIGH_VALUE_MODIFIER;
            case II -> -0.05d;
            case III -> 0.0d;
        };
    }

    // ---- 夺生路线 (life siphon) ----

    /** HEALTH transfer in points by tier (1 / 1 / 2 / 4). */
    public static float lifeSiphonHealthTransfer(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I -> 1.0f;
            case II -> 2.0f;
            case III -> 4.0f;
        };
    }

    /** HUNGER transfer in food points by tier (2 / 2 / 3 / 4). */
    public static int lifeSiphonHungerTransfer(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I -> 2;
            case II -> 3;
            case III -> 4;
        };
    }

    // ---- 窃法路线 (spell theft) ----

    /** Maximum EFFECT transfer in ticks by tier (200 / 200 / 400 / 600). */
    public static int spellTheftMaxTicks(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I -> EffectPlan.BASE_MAX_TRANSFER_TICKS;
            case II -> 400;
            case III -> 600;
        };
    }

    // ---- 潜影路线 (shadow escape) ----

    /** SUCCESS speed effect duration in ticks by tier (0 / 80 / 120 / 160). */
    public static int escapeSpeedTicks(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE -> 0;
            case I -> 80;
            case II -> 120;
            case III -> 160;
        };
    }

    /** SUCCESS speed effect amplifier by tier (0 / 0 / 0 / 1). */
    public static int escapeSpeedAmplifier(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I, II -> 0;
            case III -> 1;
        };
    }

    /** SUCCESS invisibility duration in ticks by tier (0 / 0 / 40 / 80). */
    public static int escapeInvisibilityTicks(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE, I -> 0;
            case II -> 40;
            case III -> 80;
        };
    }

    /** FAILED_ROLL exposure-duration multiplier by tier (1.0 / 0.8 / 0.6 / 0.4). */
    public static double escapeFailureMultiplier(ShadowAbilityTier tier) {
        return switch (tier) {
            case NONE -> 1.0d;
            case I -> 0.8d;
            case II -> 0.6d;
            case III -> 0.4d;
        };
    }
}
