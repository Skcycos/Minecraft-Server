package com.tanrunn.tcth.api.brewing;

/**
 * Runtime beverage tiers.
 *
 * <p>Phase 7A.1: audit-only concepts {@code T3_CANDIDATE} and
 * {@code INGREDIENT} never enter runtime events — this enum is
 * {@code UNKNOWN/COMMON/T2/T3} only. {@code T3} is currently not enabled
 * (T3 candidates stay review-only until manually promoted).
 */
public enum BeverageTier {
    /** Tier not resolvable (device did not expose a mapping). */
    UNKNOWN,
    /** Common beverage (coffee, milkshake, tea). */
    COMMON,
    /** Composite/fermented beverage (cocktails, wines, ales). */
    T2,
    /** High-tier beverage (review-only; currently not auto-enabled). */
    T3
}
