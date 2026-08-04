package com.tanrunn.tcth.api.cooking;

/**
 * Quality tier of a cooked dish.
 *
 * <p>This is TCTH's own neutral quality model. When a third-party mod provides
 * a richer quality system (for example Kaleidoscope Cookery's
 * {@code Quality} enum), its compat module is responsible for mapping that
 * value onto this enum — this API never references third-party types.
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this enum may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 */
public enum DishQuality {
    /** No quality information available (e.g. vanilla crafting). */
    UNKNOWN,
    /** Poor quality. */
    POOR,
    /** Standard quality. */
    STANDARD,
    /** Excellent quality. */
    EXCELLENT,
    /** Superb quality. */
    SUPERB
}
