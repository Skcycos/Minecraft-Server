package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Testable adapter layer over the Jobs+ powerup state of the {@code tcth:chef}
 * job (phase 3D).
 *
 * <p><strong>API boundary:</strong> this class is the part of the adapter that
 * the rest of TCTH may reference. It only uses Minecraft types — it never
 * touches Jobs+/Arc classes, so it can be loaded and unit-tested even when
 * Jobs+ is absent. The actual Jobs+ query lives in {@link ChefAbilityModule}
 * (conditional compat package, loaded only when Jobs+ is installed).
 *
 * <p>Semantics:
 * <ul>
 *   <li>players without the {@code tcth:chef} job report {@link ChefPowerupTier#NONE};</li>
 *   <li>a player with several owned nodes of the same route reports only the
 *       highest <em>active</em> one;</li>
 *   <li>query failures return a safe default ({@code NONE}) instead of
 *       interrupting a tick;</li>
 *   <li>no long-lived caching — a node can be purchased and activated at any
 *       moment, so state is queried live (or cached for at most one tick).</li>
 * </ul>
 */
public abstract class ChefPowerupAccess {

    /** The tcth:chef job location. */
    public static final ResourceLocation CHEF_JOB = ResourceLocation.fromNamespaceAndPath("tcth", "chef");

    /**
     * Highest active tier for three activation booleans (pure, route-order
     * aware). Exposed for tests and for the module implementation.
     */
    public static ChefPowerupTier highestActive(boolean i, boolean ii, boolean iii) {
        return ChefPowerupTier.highestActive(i, ii, iii);
    }

    /**
     * Highest <em>active</em> node tier of the given route for the player, or
     * {@link ChefPowerupTier#NONE} when the player has no {@code tcth:chef}
     * job, no active node of the route, or the query fails.
     *
     * <p>Must be safe to call from any server tick context; implementors must
     * catch all RuntimeException/LinkageError and return {@code NONE}.
     */
    public abstract ChefPowerupTier highestActiveTier(ServerPlayer player, ChefAbilityRoute route);
}
