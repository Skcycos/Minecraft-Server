package com.tanrunn.tcth.impl.shadow;

import net.minecraft.world.phys.Vec3;

/**
 * Pure vector math for the shadow thief success modifiers (stage 8A §10,
 * 8B.1 line-of-sight fix).
 *
 * <p>All thresholds live here as single-source constants:
 * <ul>
 *   <li>{@code watched}: the target is looking at the thief <em>and</em> the
 *       thief is visible. True when {@code dot(targetLook, toThief) >=
 *       WATCHED_DOT_MIN} (angle &lt;= 45°) and {@code hasLineOfSight};</li>
 *   <li>{@code behind}: the target is looking away from the thief and the
 *       thief is visible. True when {@code dot(targetLook, toThief) <=
 *       BEHIND_DOT_MAX} (angle &gt;= 135°) and {@code hasLineOfSight}.</li>
 * </ul>
 *
 * <p>{@code watched} and {@code behind} are mutually exclusive by
 * construction. Zero-length, non-finite, null vectors and a missing/unknown
 * line of sight fail closed to {@code (behind=false, watched=false)} — an
 * unproven orientation never grants a bonus, and an unproven sight never
 * imposes the watched penalty (8B.1 decision, documented).
 *
 * <p>The thresholds correspond to {@code cos(45°) = cos(135°) = ±√2/2}.
 */
public final class ShadowVectorMath {

    /** Dot-product threshold for "the target is watching the thief": angle
     *  between the target's look and the direction to the thief &lt;= 45°.
     *  Single-source constant (stage 8A §10). */
    public static final double WATCHED_DOT_MIN = 0.7071067811865476d; // cos(45°)
    /** Dot-product threshold for "the thief is behind the target": angle
     *  between the target's look and the direction to the thief &gt;= 135°.
     *  Single-source constant (stage 8A §10). */
    public static final double BEHIND_DOT_MAX = -0.7071067811865476d; // cos(135°)
    /** Comparison epsilon for the boundary tests: a dot product within one
     *  ULP of the threshold counts as watched (fail-safe for the target). */
    static final double DOT_EPSILON = 1.0E-12d;

    private ShadowVectorMath() {
    }

    /**
     * Immutable result of the orientation computation.
     *
     * @param behind  whether the thief is behind the target (target looking
     *                away, thief visible)
     * @param watched whether the target is watching the thief (looking at
     *                the thief, thief visible)
     */
    public record ShadowDirectionFacts(boolean behind, boolean watched) {
        public ShadowDirectionFacts {
            if (behind && watched) {
                throw new IllegalStateException("behind and watched must be mutually exclusive");
            }
        }
    }

    /**
     * Computes the {@code behind}/{@code watched} facts from pure vectors and
     * the line-of-sight input.
     *
     * <p>Both facts require {@code hasLineOfSight}: without a proven
     * unobstructed ray neither a bonus nor a penalty is applied (fail-closed,
     * 8B.1).
     *
     * @param targetLook     the target's look vector (world space)
     * @param thiefPos       the thief's position
     * @param targetPos      the target's position
     * @param hasLineOfSight whether the thief is visible to the target
     *                       (unobstructed); {@code false} fails closed
     * @return the orientation facts; both {@code false} for degenerate input
     *         or a missing line of sight
     */
    public static ShadowDirectionFacts computeFacts(Vec3 targetLook, Vec3 thiefPos, Vec3 targetPos,
                                                    boolean hasLineOfSight) {
        if (!hasLineOfSight) {
            return new ShadowDirectionFacts(false, false);
        }
        if (targetLook == null || thiefPos == null || targetPos == null) {
            return new ShadowDirectionFacts(false, false);
        }
        if (!isFinite(targetLook) || !isFinite(thiefPos) || !isFinite(targetPos)) {
            return new ShadowDirectionFacts(false, false);
        }
        Vec3 toThief = thiefPos.subtract(targetPos);
        double toThiefLen = toThief.length();
        double lookLen = targetLook.length();
        if (toThiefLen < 1.0E-4d || lookLen < 1.0E-4d) {
            return new ShadowDirectionFacts(false, false);
        }
        double dot = (targetLook.x * toThief.x + targetLook.y * toThief.y + targetLook.z * toThief.z)
                / (lookLen * toThiefLen);
        if (!Double.isFinite(dot)) {
            return new ShadowDirectionFacts(false, false);
        }
        // Fail-safe for the target: the exact boundary (angle == threshold)
        // counts as watched, never as behind.
        boolean watched = dot >= WATCHED_DOT_MIN - DOT_EPSILON;
        boolean behind = !watched && dot <= BEHIND_DOT_MAX + DOT_EPSILON;
        return new ShadowDirectionFacts(behind, watched);
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
