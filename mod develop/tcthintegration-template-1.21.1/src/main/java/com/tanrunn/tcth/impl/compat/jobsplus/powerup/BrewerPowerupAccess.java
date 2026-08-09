package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Testable adapter layer over the Jobs+ powerup state of the {@code tcth:brewer}
 * job (phase 7E).
 *
 * <p><strong>API boundary:</strong> this class is the part of the adapter that
 * the rest of TCTH may reference. It only uses Minecraft types — it never
 * touches Jobs+/Arc/BAC classes, so it can be loaded and unit-tested even when
 * those mods are absent. The actual Jobs+ query lives in
 * {@link BrewerAbilityModule} (conditional compat package, loaded only when
 * Jobs+ is installed).
 *
 * <p>Semantics:
 * <ul>
 *   <li>players without the {@code tcth:brewer} job report {@link BrewerPowerupTier#NONE};</li>
 *   <li>a player with several owned nodes of the same route reports only the
 *       highest <em>active</em> one;</li>
 *   <li>query failures return a safe default ({@code NONE}) instead of
 *       interrupting a tick;</li>
 *   <li>no long-lived caching — a node can be purchased and activated at any
 *       moment, so state is queried live (or cached for at most one tick).</li>
 * </ul>
 */
public abstract class BrewerPowerupAccess {

    /** The tcth:brewer job location. */
    public static final ResourceLocation BREWER_JOB = ResourceLocation.fromNamespaceAndPath("tcth", "brewer");

    /**
     * Highest active tier for three activation booleans (pure, route-order
     * aware). Exposed for tests and for the module implementation.
     */
    public static BrewerPowerupTier highestActive(boolean i, boolean ii, boolean iii) {
        return BrewerPowerupTier.highestActive(i, ii, iii);
    }

    /**
     * Highest <em>active</em> node tier of the given route for the player, or
     * {@link BrewerPowerupTier#NONE} when the player has no {@code tcth:brewer}
     * job, no active node of the route, or the query fails.
     *
     * <p>Must be safe to call from any server tick context; implementors must
     * catch all RuntimeException/LinkageError and return {@code NONE}.
     */
    public abstract BrewerPowerupTier highestActiveTier(ServerPlayer player, BrewerAbilityRoute route);
}
