package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

/**
 * Activation tier of a tcth:gunner ability-route node (phase 5B).
 *
 * <p>Pure value type — no Jobs+/Arc/SG dependency. Route semantics: only the
 * highest <em>active</em> node of a route applies; lower nodes of the same
 * route are excluded (no stacking, no summing).
 */
public enum GunnerPowerupTier {

    /** No node of the route is active (or the player has no tcth:gunner job). */
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
     * @param i activation of the first node
     * @param ii activation of the second node
     * @param iii activation of the third node
     * @return the highest active tier, or {@link #NONE}
     */
    public static GunnerPowerupTier highestActive(boolean i, boolean ii, boolean iii) {
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
