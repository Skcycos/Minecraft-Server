package com.tanrunn.tcth.api.brewing;

/**
 * Beverage-producing devices recognised by the TCTH brewing framework.
 *
 * <p>No third-party types are referenced; the framework only ever emits
 * {@link BeveragePreparedEvent}s for devices with a proven player-delivery
 * path (phase 7A audit). Enum members for devices without a live mixin are
 * reserved but never emitted.
 */
public enum BeverageDevice {
    /** Brewin' and Chewin' Keg (player pours a drink with a container). */
    KEG,
    /** Kaleidoscope Tavern Shaker (mixed cocktails). */
    SHAKER,
    /** Kaleidoscope Tavern Barrel (fermented wines). */
    BARREL,
    /** Bakeries Blender. */
    BLENDER,
    /** Any device not covered above. */
    OTHER
}
