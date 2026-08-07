package com.tanrunn.tcth.impl.stats;

import java.util.Locale;
import java.util.Optional;

/**
 * Fixed set of gunner battlefield medals (phase 5C / 5C.1).
 *
 * <p>Thresholds are code constants — single authoritative source, not
 * configurable and not data-driven. Display text lives in lang files via
 * {@link #translationKey()}; this enum never embeds player-facing literals.
 * Display order is the enum declaration order.
 */
public enum GunnerMedal {

    FIRST_BLOOD("first_blood") {
        @Override
        public boolean isSatisfied(PlayerGunnerStats stats) {
            return stats.getTotalGunKills() >= FIRST_BLOOD_KILLS;
        }
    },
    CENTURION("centurion") {
        @Override
        public boolean isSatisfied(PlayerGunnerStats stats) {
            return stats.getTotalGunKills() >= CENTURION_KILLS;
        }
    },
    LONG_SHOT("long_shot") {
        @Override
        public boolean isSatisfied(PlayerGunnerStats stats) {
            return stats.getMaxDistance() >= LONG_SHOT_DISTANCE;
        }
    },
    ELITE_HUNTER("elite_hunter") {
        @Override
        public boolean isSatisfied(PlayerGunnerStats stats) {
            return stats.getEliteKills() >= ELITE_HUNTER_KILLS;
        }
    },
    BOSS_FINISHER("boss_finisher") {
        @Override
        public boolean isSatisfied(PlayerGunnerStats stats) {
            return stats.getBossKills() >= BOSS_FINISHER_KILLS;
        }
    };

    /** First Blood unlock: total confirmed kills (inclusive). */
    public static final int FIRST_BLOOD_KILLS = 1;
    /** Centurion unlock: total confirmed kills (inclusive). */
    public static final int CENTURION_KILLS = 100;
    /** Long Shot unlock distance in blocks (inclusive). */
    public static final float LONG_SHOT_DISTANCE = 50.0f;
    /** Elite Hunter unlock count (ELITE tier only, not HEAVY). */
    public static final int ELITE_HUNTER_KILLS = 25;
    /** Boss Finisher unlock: BOSS-tier kills (inclusive). */
    public static final int BOSS_FINISHER_KILLS = 1;

    private static final String TRANSLATION_PREFIX = "tcth.gunner.medal.";

    private final String id;

    GunnerMedal(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Lang key for the medal display name ({@code tcth.gunner.medal.<id>}).
     */
    public String translationKey() {
        return TRANSLATION_PREFIX + id;
    }

    /**
     * Whether the player's current stats meet this medal's unlock condition.
     */
    public abstract boolean isSatisfied(PlayerGunnerStats stats);

    public static Optional<GunnerMedal> byId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        String key = raw.toLowerCase(Locale.ROOT);
        for (GunnerMedal medal : values()) {
            if (medal.id.equals(key)) {
                return Optional.of(medal);
            }
        }
        return Optional.empty();
    }
}
