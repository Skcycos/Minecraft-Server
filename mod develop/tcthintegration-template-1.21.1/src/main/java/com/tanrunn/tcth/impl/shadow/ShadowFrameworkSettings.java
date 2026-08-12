package com.tanrunn.tcth.impl.shadow;

import com.tanrunn.tcth.Config;

/**
 * Immutable snapshot of the shadow-thief framework settings (phase 8B).
 *
 * <p>Produced by {@link #defaults()} from {@link Config} with fail-closed
 * reads: a config read exception falls back to the safe default rather than
 * enabling anything. Tests construct their own records directly so no bare
 * JUnit test ever depends on a loaded {@code ModConfigSpec}.
 *
 * @param masterEnabled             projection of {@link Config#ENABLED}, the
 *                                framework-wide master switch (read
 *                                fail-closed: a config failure → FALSE)
 * @param realAssetTransfersEnabled master gate for REAL asset transfers
 *                                (default false; the engine is wired into
 *                                {@code defaults()} but stays inert until an
 *                                operator enables this)
 * @param dailyItemLossLimit      per-victim daily successful-ITEM cap
 *                                (default 3; conservative)
 * @param integrationEnabled      master switch for the whole shadow thief
 *                                framework (default false)
 * @param playerTheftEnabled      player-target theft switch (default false)
 * @param entityTheftEnabled      entity-target theft switch (default false)
 * @param auditEnabled            audit log switch (default true; a config
 *                                read failure fails closed to FALSE)
 * @param baseSuccessChance       base success chance (default 0.35)
 * @param minSuccessChance        lower clamp (default 0.05)
 * @param maxSuccessChance        upper clamp (default 0.85)
 * @param globalCooldownTicks     per-thief global action cooldown
 * @param noCandidateCooldownTicks short per-thief cooldown after an
 *                                empty-candidate attempt
 * @param failureCooldownTicks    per-thief cooldown after a failed roll or
 *                                transfer
 * @param victimProtectionTicks   per-victim grace period after a success
 * @param alertTicks              per-target alert window
 */
public record ShadowFrameworkSettings(boolean masterEnabled, boolean integrationEnabled, boolean playerTheftEnabled,
                                      boolean entityTheftEnabled, boolean auditEnabled,
                                      double baseSuccessChance, double minSuccessChance,
                                      double maxSuccessChance, long globalCooldownTicks,
                                      long noCandidateCooldownTicks, long failureCooldownTicks,
                                      long victimProtectionTicks, long alertTicks,
                                      boolean realAssetTransfersEnabled, long dailyItemLossLimit) {

    public static final long DEFAULT_DAILY_ITEM_LOSS_LIMIT = 3L;
    public static final double DEFAULT_BASE_SUCCESS_CHANCE = 0.35d;
    public static final double DEFAULT_MIN_SUCCESS_CHANCE = 0.05d;
    public static final double DEFAULT_MAX_SUCCESS_CHANCE = 0.85d;
    public static final long DEFAULT_GLOBAL_COOLDOWN_TICKS = 200L;
    public static final long DEFAULT_NO_CANDIDATE_COOLDOWN_TICKS = 40L;
    public static final long DEFAULT_FAILURE_COOLDOWN_TICKS = 400L;
    public static final long DEFAULT_VICTIM_PROTECTION_TICKS = 1_200L;
    public static final long DEFAULT_ALERT_TICKS = 100L;

    public ShadowFrameworkSettings {
        if (!Double.isFinite(baseSuccessChance) || !Double.isFinite(minSuccessChance)
                || !Double.isFinite(maxSuccessChance)) {
            throw new IllegalArgumentException("chances must be finite");
        }
        if (baseSuccessChance < 0.0d || baseSuccessChance > 1.0d
                || minSuccessChance < 0.0d || minSuccessChance > 1.0d
                || maxSuccessChance < 0.0d || maxSuccessChance > 1.0d) {
            throw new IllegalArgumentException("chances must be within [0,1]");
        }
        if (minSuccessChance > maxSuccessChance) {
            throw new IllegalArgumentException("minSuccessChance must not exceed maxSuccessChance");
        }
        if (dailyItemLossLimit <= 0L) {
            throw new IllegalArgumentException("dailyItemLossLimit must be positive: " + dailyItemLossLimit);
        }
        for (long ticks : new long[] { globalCooldownTicks, noCandidateCooldownTicks, failureCooldownTicks,
                victimProtectionTicks, alertTicks }) {
            if (ticks < 0L) {
                throw new IllegalArgumentException("cooldown ticks must be non-negative");
            }
        }
    }

    /**
     * @return settings read from {@link Config} with fail-closed fallbacks
     */
    public static ShadowFrameworkSettings defaults() {
        return new ShadowFrameworkSettings(
                safeGet(Config.ENABLED::get, false),
                safeGet(Config.SHADOW_THIEF_INTEGRATION_ENABLED::get, false),
                safeGet(Config.SHADOW_PLAYER_THEFT_ENABLED::get, false),
                safeGet(Config.SHADOW_ENTITY_THEFT_ENABLED::get, false),
                safeGet(Config.SHADOW_AUDIT_ENABLED::get, false),
                safeGet(Config.SHADOW_BASE_SUCCESS_CHANCE::get, DEFAULT_BASE_SUCCESS_CHANCE),
                safeGet(Config.SHADOW_MIN_SUCCESS_CHANCE::get, DEFAULT_MIN_SUCCESS_CHANCE),
                safeGet(Config.SHADOW_MAX_SUCCESS_CHANCE::get, DEFAULT_MAX_SUCCESS_CHANCE),
                safeGet(Config.SHADOW_GLOBAL_COOLDOWN_TICKS::get, DEFAULT_GLOBAL_COOLDOWN_TICKS),
                safeGet(Config.SHADOW_NO_CANDIDATE_COOLDOWN_TICKS::get, DEFAULT_NO_CANDIDATE_COOLDOWN_TICKS),
                safeGet(Config.SHADOW_FAILURE_COOLDOWN_TICKS::get, DEFAULT_FAILURE_COOLDOWN_TICKS),
                safeGet(Config.SHADOW_VICTIM_PROTECTION_TICKS::get, DEFAULT_VICTIM_PROTECTION_TICKS),
                safeGet(Config.SHADOW_ALERT_TICKS::get, DEFAULT_ALERT_TICKS),
                safeGet(Config.SHADOW_REAL_ASSET_TRANSFERS_ENABLED::get, false),
                safeGet(Config.SHADOW_DAILY_ITEM_LOSS_LIMIT::get, DEFAULT_DAILY_ITEM_LOSS_LIMIT));
    }

    private static <T> T safeGet(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            if (value == null) {
                return fallback;
            }
            if (value instanceof Double d && !Double.isFinite(d)) {
                return fallback;
            }
            if (value instanceof Long l && l < 0L) {
                return fallback;
            }
            return value;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }

    // Package-private for the supplier-injection tests; never part of the
    // public API.
    static boolean safeGetBool(java.util.function.Supplier<Boolean> supplier, boolean fallback) {
        return safeGet(supplier, fallback);
    }

    static double safeGetDouble(java.util.function.Supplier<Double> supplier, double fallback) {
        return safeGet(supplier, fallback);
    }

    static long safeGetLong(java.util.function.Supplier<Long> supplier, long fallback) {
        return safeGet(supplier, fallback);
    }
}
