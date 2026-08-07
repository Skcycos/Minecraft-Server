package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Testable adapter layer over the Jobs+ powerup state of the {@code tcth:gunner}
 * job (phase 5B).
 *
 * <p><strong>API boundary:</strong> this class is the part of the adapter that
 * the rest of TCTH may reference. It only uses Minecraft types — it never
 * touches Jobs+/Arc/SG classes, so it can be loaded and unit-tested even when
 * those mods are absent. The actual Jobs+ query lives in
 * {@link GunnerAbilityModule} (conditional compat package, loaded only when
 * Jobs+ is installed).
 *
 * <p>Semantics:
 * <ul>
 *   <li>players without the {@code tcth:gunner} job report {@link GunnerPowerupTier#NONE};</li>
 *   <li>a player with several owned nodes of the same route reports only the
 *       highest <em>active</em> one;</li>
 *   <li>query failures return a safe default ({@code NONE}) instead of
 *       interrupting a tick;</li>
 *   <li>no long-lived caching — a node can be purchased and activated at any
 *       moment, so state is queried live (or cached for at most one tick).</li>
 * </ul>
 */
public abstract class GunnerPowerupAccess {

    /** The tcth:gunner job location. */
    public static final ResourceLocation GUNNER_JOB = ResourceLocation.fromNamespaceAndPath("tcth", "gunner");

    /**
     * Highest active tier for three activation booleans (pure, route-order
     * aware). Exposed for tests and for the module implementation.
     */
    public static GunnerPowerupTier highestActive(boolean i, boolean ii, boolean iii) {
        return GunnerPowerupTier.highestActive(i, ii, iii);
    }

    /**
     * Highest <em>active</em> node tier of the given route for the player, or
     * {@link GunnerPowerupTier#NONE} when the player has no {@code tcth:gunner}
     * job, no active node of the route, or the query fails.
     *
     * <p>Must be safe to call from any server tick context; implementors must
     * catch all RuntimeException/LinkageError and return {@code NONE}.
     */
    public abstract GunnerPowerupTier highestActiveTier(ServerPlayer player, GunnerAbilityRoute route);
}
