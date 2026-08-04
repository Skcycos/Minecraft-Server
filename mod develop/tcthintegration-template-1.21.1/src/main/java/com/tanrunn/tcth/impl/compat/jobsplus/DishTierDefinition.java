package com.tanrunn.tcth.impl.compat.jobsplus;

/**
 * Data-driven definition of one dish's tier.
 *
 * <p>Only the tier is stored here on purpose: XP values are controlled by the
 * Arc/Jobs+ reward data files ({@code jobsplus:job_exp} rewards), avoiding two
 * independent sets of experience numbers.
 *
 * @param tier the dish tier ({@code COMMON}, {@code T2} or {@code T3})
 */
public record DishTierDefinition(DishTier tier) {
}
