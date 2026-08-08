package com.tanrunn.tcth.api.cooking;

/**
 * The device (or context) that produced a cooked dish.
 *
 * <p>This is TCTH's own neutral device model. It deliberately does not
 * reference any optional mod type: integrations map their own devices onto
 * these values.
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this enum may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 */
public enum CookingDevice {
    /** Crafting table (or any manual crafting). */
    CRAFTING,
    /** Furnace. */
    FURNACE,
    /** Smoker. */
    SMOKER,
    /** Campfire. */
    CAMPFIRE,
    /** Farmer's Delight cooking pot. */
    FARMERS_DELIGHT_COOKING_POT,
    /** Kaleidoscope Cookery wok-style cooking pot. */
    KALEIDOSCOPE_COOKING_POT,
    /** Kaleidoscope Cookery stockpot. */
    KALEIDOSCOPE_STOCKPOT,
    /** Kaleidoscope Cookery steamer. */
    KALEIDOSCOPE_STEAMER,
    /** Dungeon's Delight monster cooking pot (optional mod). */
    DUNGEONS_DELIGHT_MONSTER_POT,
    /**
     * Bakeries oven (optional mod). Reserved for a proven player-take path;
     * do not emit until a dedicated mixin is live.
     */
    BAKERIES_OVEN,
    /**
     * Bakeries blender (optional mod). Reserved for a proven player-take path;
     * do not emit until a dedicated mixin is live.
     */
    BAKERIES_BLENDER,
    /**
     * Feast/platter portioning: a player takes one real serving (e.g. with a
     * plate/bowl) out of a placed whole dish. Published once per actual
     * serving delivered into the player inventory.
     */
    PORTIONING,
    /** Any device not covered above. */
    OTHER
}
