package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance;
import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.shadow.ShadowAbilityRoute;
import com.tanrunn.tcth.impl.shadow.ShadowAbilitySnapshot;
import com.tanrunn.tcth.impl.shadow.ShadowAbilityTier;

import net.minecraft.server.level.ServerPlayer;

/**
 * Jobs+-backed implementation of {@link ShadowPowerupAccess} for the
 * {@code tcth:shadow_thief} job (phase 8E).
 *
 * <p>Lives in the conditional compat package and is only ever loaded when
 * Jobs+ is installed; the class is never resolved otherwise. Queries go
 * through Jobs+' own public API ({@code JobsServerPlayer} → {@code Job} →
 * {@code JobPowerupManager.getPowerup} → {@code PowerupState.ACTIVE}); no
 * player NBT is ever read or written. No long-lived caching — a powerup can
 * be bought and toggled at any moment, so the state is queried live on every
 * call. The interaction handler queries the snapshot AT MOST ONCE per theft
 * attempt; this module never re-queries.
 *
 * <p>Route gating: a route applies only when
 * {@code Config.ENABLED && shadowThiefIntegrationEnabled &&
 * shadowAbilitiesEnabled && <route switch>} — every read fails closed, so a
 * broken config yields {@link ShadowAbilityTier#NONE} (never flipped to
 * active). Only the highest active tier of a route applies (no stacking).
 */
public final class ShadowAbilityModule extends ShadowPowerupAccess {

    private static final ShadowAbilityModule INSTANCE = new ShadowAbilityModule();

    /** 60 s throttle window for the WARN logs below. */
    private static final long WARN_THROTTLE_NS = 60_000_000_000L;
    private static final AtomicLong LAST_QUERY_WARN_NANOS = new AtomicLong(0);
    private static final AtomicLong LAST_CONFIG_WARN_NANOS = new AtomicLong(0);

    /**
     * Powerup instance lookup. Production resolves from Jobs+ data via
     * {@link PowerupInstance#of}; tests inject constructed instances because
     * Jobs+ data is not loaded in a bare JUnit JVM.
     */
    @FunctionalInterface
    interface PowerupInstanceResolver {
        PowerupInstance resolve(net.minecraft.resources.ResourceLocation node);
    }

    private PowerupInstanceResolver powerupResolver = PowerupInstance::of;

    /** Config suppliers, injectable for tests (defaults read the real Config). */
    static BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    static BooleanSupplier integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
    static BooleanSupplier abilitiesMasterSupplier = Config.SHADOW_ABILITIES_ENABLED::get;
    static BooleanSupplier sleightSupplier = Config.SHADOW_SLEIGHT_ABILITIES_ENABLED::get;
    static BooleanSupplier lifeSiphonSupplier = Config.SHADOW_LIFE_SIPHON_ABILITIES_ENABLED::get;
    static BooleanSupplier spellTheftSupplier = Config.SHADOW_SPELL_THEFT_ABILITIES_ENABLED::get;
    static BooleanSupplier escapeSupplier = Config.SHADOW_ESCAPE_ABILITIES_ENABLED::get;

    private ShadowAbilityModule() {
    }

    public static ShadowAbilityModule instance() {
        return INSTANCE;
    }

    /** No game-bus listeners are needed: this is a pure query service. The
     *  interaction handler queries {@link #snapshotFor} at most once per
     *  attempt. */
    public static void install() {
        com.tanrunn.tcth.impl.shadow.ShadowAbilityAccess.setProvider(INSTANCE::snapshotFor);
    }

    @Override
    public ShadowAbilitySnapshot snapshotFor(ServerPlayer player) {
        if (player == null || !masterEnabled()) {
            return ShadowAbilitySnapshot.none();
        }
        return new ShadowAbilitySnapshot(
                queryRoute(player, ShadowAbilityRoute.SLEIGHT, sleightSupplier),
                queryRoute(player, ShadowAbilityRoute.LIFE_SIPHON, lifeSiphonSupplier),
                queryRoute(player, ShadowAbilityRoute.SPELL_THEFT, spellTheftSupplier),
                queryRoute(player, ShadowAbilityRoute.SHADOW_ESCAPE, escapeSupplier));
    }

    /** Per-route gating + query; any config/query failure → NONE for that
     *  route only (never affects the other routes). */
    private static ShadowAbilityTier queryRoute(ServerPlayer player, ShadowAbilityRoute route,
                                                BooleanSupplier routeSwitch) {
        try {
            if (!routeSwitch.getAsBoolean()) {
                return ShadowAbilityTier.NONE;
            }
            return INSTANCE.highestActiveTier(player, route);
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_QUERY_WARN_NANOS,
                    "[TCTH] Shadow ability route query failed for route " + route + ": " + e);
            return ShadowAbilityTier.NONE;
        }
    }

    /** Highest ACTIVE tier via the Jobs+ public API chain (fail-closed). */
    @Override
    public ShadowAbilityTier highestActiveTier(ServerPlayer player, ShadowAbilityRoute route) {
        if (player == null || route == null) {
            return ShadowAbilityTier.NONE;
        }
        try {
            if (!(player instanceof JobsServerPlayer jobsServerPlayer)) {
                return ShadowAbilityTier.NONE;
            }
            Job job = jobsServerPlayer.jobsplus$getJob(JobInstance.of(SHADOW_THIEF_JOB));
            if (job == null) {
                return ShadowAbilityTier.NONE;
            }
            JobPowerupManager powerupManager = job.getPowerupManager();
            boolean i = isActive(powerupManager, route.nodeLocation(route.nodeI()));
            boolean ii = isActive(powerupManager, route.nodeLocation(route.nodeII()));
            boolean iii = isActive(powerupManager, route.nodeLocation(route.nodeIII()));
            return ShadowAbilityTier.highestActive(i, ii, iii);
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_QUERY_WARN_NANOS,
                    "[TCTH] Shadow powerup query failed for route " + route + ": " + e);
            return ShadowAbilityTier.NONE;
        }
    }

    private boolean isActive(JobPowerupManager powerupManager, net.minecraft.resources.ResourceLocation node) {
        PowerupInstance instance = powerupResolver.resolve(node);
        if (instance == null) {
            return false;
        }
        java.util.Optional<Powerup> powerup = powerupManager.getPowerup(instance);
        return powerup.map(p -> p.getState() == PowerupState.ACTIVE).orElse(false);
    }

    /** {@code Config.ENABLED && integration && abilitiesMaster}, fail-closed. */
    private static boolean masterEnabled() {
        try {
            return frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && abilitiesMasterSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_CONFIG_WARN_NANOS,
                    "[TCTH] Shadow ability config read failed; abilities fail-closed (NONE): " + e);
            return false;
        }
    }

    /** At most one WARN per 60 s window per call site. */
    private static void warnThrottled(AtomicLong lastNanos, String message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (now - last >= WARN_THROTTLE_NS && lastNanos.compareAndSet(last, now)) {
            TCTHIntegration.LOGGER.warn(message);
        }
    }

    // ---- test hooks ----

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver != null ? resolver : PowerupInstance::of;
    }

    static void setConfigSuppliersForTesting(BooleanSupplier framework, BooleanSupplier integration,
                                             BooleanSupplier master, BooleanSupplier sleight,
                                             BooleanSupplier lifeSiphon, BooleanSupplier spellTheft,
                                             BooleanSupplier escape) {
        frameworkEnabledSupplier = framework;
        integrationEnabledSupplier = integration;
        abilitiesMasterSupplier = master;
        sleightSupplier = sleight;
        lifeSiphonSupplier = lifeSiphon;
        spellTheftSupplier = spellTheft;
        escapeSupplier = escape;
    }

    static void resetForTesting() {
        INSTANCE.powerupResolver = PowerupInstance::of;
        LAST_QUERY_WARN_NANOS.set(0);
        LAST_CONFIG_WARN_NANOS.set(0);
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.SHADOW_ABILITIES_ENABLED::get;
        sleightSupplier = Config.SHADOW_SLEIGHT_ABILITIES_ENABLED::get;
        lifeSiphonSupplier = Config.SHADOW_LIFE_SIPHON_ABILITIES_ENABLED::get;
        spellTheftSupplier = Config.SHADOW_SPELL_THEFT_ABILITIES_ENABLED::get;
        escapeSupplier = Config.SHADOW_ESCAPE_ABILITIES_ENABLED::get;
    }
}
