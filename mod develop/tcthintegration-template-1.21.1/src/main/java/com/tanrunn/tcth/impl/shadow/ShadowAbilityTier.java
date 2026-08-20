package com.tanrunn.tcth.impl.shadow;

/**
 * Activation tier of a {@code tcth:shadow_thief} ability-route node (phase
 * 8E).
 *
 * <p>Pure value type — no Jobs+/Arc dependency, so it can be loaded and
 * unit-tested even when those mods are absent. Route semantics: only the
 * highest <em>active</em> node of a route applies; lower nodes of the same
 * route are excluded (no stacking, no summing).
 */
public enum ShadowAbilityTier {

    /** No node of the route is active (or the player has no {@code
     *  tcth:shadow_thief} job, or the route switch is off, or the query
     *  failed). */
    NONE,

    /** First (lowest) node of the route is the highest active one. */
    I,

    /** Second node of the route is the highest active one. */
    II,

    /** Third (highest) node of the route is active. */
    III;

    /**
     * Highest active tier for three activation booleans, in route order.
     *
     * @param i   activation of the first node
     * @param ii  activation of the second node
     * @param iii activation of the third node
     * @return the highest active tier, or {@link #NONE}
     */
    public static ShadowAbilityTier highestActive(boolean i, boolean ii, boolean iii) {
        if (iii) {
            return III;
        }
        if (ii) {
            return II;
        }
        if (i) {
            return I;
        }
        return NONE;
    }
}
