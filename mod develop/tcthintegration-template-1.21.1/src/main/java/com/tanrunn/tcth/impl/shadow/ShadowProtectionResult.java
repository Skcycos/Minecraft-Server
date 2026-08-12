package com.tanrunn.tcth.impl.shadow;

/**
 * Structured result of a protection-service query.
 *
 * <p>Fail-closed semantics: {@link #UNKNOWN} must be treated as a denial by
 * the coordinator, and a protection-service exception also results in a
 * denial. Only {@link #ALLOWED} permits the attempt to continue.
 */
public enum ShadowProtectionResult {
    /** The attempt is allowed by the protection layer. */
    ALLOWED,
    /** The area (claim / region / spawn protection) denies the attempt. */
    DENIED_AREA,
    /** The target is under new-player protection. */
    DENIED_NEW_PLAYER,
    /** The target itself is protected (dead, FakePlayer, flagged entity…). */
    DENIED_TARGET,
    /** The thief is targeting themselves. */
    DENIED_SELF,
    /** The target's or thief's gamemode forbids the theft (spectator,
     *  creative…). */
    DENIED_GAMEMODE,
    /** The protection layer could not determine the answer; must fail
     *  closed. */
    UNKNOWN
}
