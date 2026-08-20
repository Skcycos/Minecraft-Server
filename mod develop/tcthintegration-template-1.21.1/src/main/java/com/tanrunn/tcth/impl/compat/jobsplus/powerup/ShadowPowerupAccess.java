package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import com.tanrunn.tcth.impl.shadow.ShadowAbilityRoute;
import com.tanrunn.tcth.impl.shadow.ShadowAbilitySnapshot;
import com.tanrunn.tcth.impl.shadow.ShadowAbilityTier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Testable adapter layer over the Jobs+ powerup state of the
 * {@code tcth:shadow_thief} job (phase 8E).
 *
 * <p><strong>API boundary:</strong> this class is the part of the adapter
 * that the rest of TCTH may reference. It only uses TCTH/Minecraft types —
 * it never touches Jobs+/Arc classes — so it can be loaded and unit-tested
 * even when those mods are absent. The actual Jobs+ query lives in
 * {@link ShadowAbilityModule} (conditional compat package, loaded only when
 * Jobs+ is installed).
 *
 * <p>Semantics:
 * <ul>
 *   <li>players without the {@code tcth:shadow_thief} job report a snapshot
 *       with every route at {@code NONE};</li>
 *   <li>a player with several owned nodes of the same route reports only the
 *       highest <em>active</em> one;</li>
 *   <li>query failures return the safe {@code NONE} snapshot instead of
 *       interrupting a tick;</li>
 *   <li>no long-lived caching — a node can be purchased and activated at any
 *       moment, so state is queried live (at most once per attempt, by the
 *       interaction handler).</li>
 * </ul>
 */
public abstract class ShadowPowerupAccess {

    /** The tcth:shadow_thief job location. */
    public static final ResourceLocation SHADOW_THIEF_JOB =
            ResourceLocation.fromNamespaceAndPath("tcth", "shadow_thief");

    /**
     * The ability snapshot for ONE theft attempt, or the all-{@code NONE}
     * snapshot when the player has no {@code tcth:shadow_thief} job, no
     * active node of a route, the route/master switches are off, or the query
     * fails.
     *
     * <p>Must be safe to call from any server tick context; implementors must
     * catch all RuntimeException/LinkageError and fail closed to the
     * {@code NONE} snapshot.
     */
    public abstract ShadowAbilitySnapshot snapshotFor(ServerPlayer player);

    /**
     * Highest <em>active</em> node tier of the given route for the player, or
     * {@code NONE} when the player has no {@code tcth:shadow_thief} job, no
     * active node of the route, or the query fails.
     *
     * <p>Must be safe to call from any server tick context; implementors must
     * catch all RuntimeException/LinkageError and return {@code NONE}.
     */
    public abstract ShadowAbilityTier highestActiveTier(ServerPlayer player,
                                                        ShadowAbilityRoute route);
}
