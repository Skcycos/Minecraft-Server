package com.tanrunn.tcth.api.farming;

/**
 * How a crop harvest was performed.
 *
 * <p>Used by {@link CropHarvestedEvent} to describe the harvest source:
 * <ul>
 *   <li>{@link #BREAK} — the crop block was destroyed (player break);</li>
 *   <li>{@link #RIGHT_CLICK} — the crop was harvested through a right-click
 *       interaction (sweet berries, Farmers Delight tomatoes, Kaleidoscope
 *       Cookery crops);</li>
 *   <li>{@link #SPECIAL_BLOCK} — a special block interaction that performs a
 *       harvest (reserved for future sources);</li>
 *   <li>{@link #OTHER} — any other harvest source.</li>
 * </ul>
 */
public enum HarvestMethod {
    BREAK,
    RIGHT_CLICK,
    SPECIAL_BLOCK,
    OTHER
}
