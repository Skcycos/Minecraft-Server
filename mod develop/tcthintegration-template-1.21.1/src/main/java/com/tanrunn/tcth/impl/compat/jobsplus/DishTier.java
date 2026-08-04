package com.tanrunn.tcth.impl.compat.jobsplus;

/**
 * Tier of a dish, resolved from the data-driven tier maps.
 *
 * <p>COMMON, T2 and T3 mirror the server's dish grading system. XP amounts are
 * deliberately <strong>not</strong> stored here — they live in the Arc/Jobs+
 * reward data files (jobsplus:job_exp rewards), so there is only one source of
 * truth for experience values.
 */
public enum DishTier {
    /** Ordinary dishes. */
    COMMON,
    /** Tier-2 dishes. */
    T2,
    /** Tier-3 dishes. */
    T3
}
