package com.tanrunn.tcth.api.shadow;

/**
 * The five possible types of shadow theft, as defined by the stage 8A audit.
 *
 * <p>One type is drawn per attempt by the server from the pool of currently
 * available candidates; the player cannot choose a type.
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this enum may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 */
public enum ShadowTheftType {
    /** A single item stack from the victim's main inventory. */
    ITEM,
    /** A fraction of the victim's bank balance (currency). */
    COIN,
    /** A chunk of the victim's health, capped by the health floor. */
    HEALTH,
    /** A chunk of the victim's hunger and saturation. */
    HUNGER,
    /** A beneficial mob effect (shortened from the victim's remaining time). */
    EFFECT
}
