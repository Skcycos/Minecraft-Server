package com.tanrunn.tcth.api.guncombat;

/**
 * Difficulty tier of a target killed with a firearm.
 *
 * <p>Used by {@link GunKillEvent} to describe the difficulty of the defeated
 * target so that reward modules can scale experience/gold accordingly. The
 * server-side classification is entirely data-driven
 * ({@code data/tcth/tags/entity_type/gunner_targets/*.json}); this enum only
 * carries the stable names.
 *
 * <p>Resolution order (highest priority first): {@link #EXCLUDED} &gt;
 * {@link #BOSS} &gt; {@link #HEAVY} &gt; {@link #ELITE} &gt; {@link #COMMON}.
 * A target may only ever resolve to one tier (strictly mutually exclusive).
 */
public enum GunTargetTier {
    /**
     * Ordinary hostile mob (e.g. zombies, skeletons, spiders).
     */
    COMMON,
    /**
     * Raiders, gun-toting enemies, {@code #scguns:gunner} and similar threats.
     */
    ELITE,
    /**
     * Heavy targets such as {@code #scguns:heavy} / {@code #scguns:very_heavy}.
     */
    HEAVY,
    /**
     * Boss-tier targets explicitly listed in the TCTH boss tag.
     */
    BOSS
}
