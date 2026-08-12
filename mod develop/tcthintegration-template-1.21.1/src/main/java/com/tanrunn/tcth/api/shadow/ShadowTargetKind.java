package com.tanrunn.tcth.api.shadow;

/**
 * The kind of target a shadow theft attempt is performed against.
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this enum may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 */
public enum ShadowTargetKind {
    /** A player target (victim is a real {@code ServerPlayer}). */
    PLAYER,
    /** A non-player entity target (victim is any other living entity). */
    ENTITY
}
