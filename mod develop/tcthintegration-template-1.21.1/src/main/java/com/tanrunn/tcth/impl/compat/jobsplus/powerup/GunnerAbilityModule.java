package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance;
import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.scguns.SgDamageEvidence;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Jobs+-backed implementation of {@link GunnerPowerupAccess} plus the four
 * gunner ability-route effects (phase 5B).
 *
 * <p>Lives in the conditional compat package and is only ever loaded when
 * Jobs+ is installed; the class is never resolved otherwise. Queries go
 * through Jobs+' own public API ({@code JobsServerPlayer} → {@code Job} →
 * {@code JobPowerupManager.getPowerup} → {@code PowerupState.ACTIVE}); no
 * player NBT is ever read or written. No long-lived caching — a powerup can be
 * bought and toggled at any moment, so the state is queried live on every
 * call. Query failures are caught and mapped to {@link GunnerPowerupTier#NONE}
 * so a transient error can never break a tick.
 *
 * <p>Route effects:
 * <ul>
 *   <li><b>Marksmanship</b>: SG firearm damage dealt to non-player targets
 *       ×1.05 / ×1.10 / ×1.15 (applied in {@code LivingDamageEvent.Pre});</li>
 *   <li><b>Ammo saver</b>: chance not to consume ammo on each real deduction
 *       entry 5% / 10% / 15% (common {@code handleShoot} {@code Math.max}
 *       redirect and BEAM-period {@code consumeAmmo} HEAD after preconditions);</li>
 *   <li><b>Battlefield defense</b>: SG firearm damage taken by the player
 *       ×0.90 / ×0.80 / ×0.70 (applied in {@code LivingDamageEvent.Pre});</li>
 *   <li><b>Gunner study</b>: tcth:gunner job experience ×1.15 / ×1.35 / ×1.60
 *       (data-driven via {@code jobsplus:on_job_exp} + {@code job_exp_multiplier};
 *       the multiplier constants live here for consistency tests).</li>
 * </ul>
 *
 * <p>Every route is gated by {@code Config.ENABLED} &&
 * {@code Config.GUNNER_INTEGRATION_ENABLED} && {@code Config.GUNNER_ABILITIES_ENABLED}
 * and its own route switch; any config read failure fails closed (never
 * flipped by inverted conditions). Only the highest active tier of a route
 * applies (no stacking). Effects never touch GunKillEvent attribution, never
 * bypass {@code gunnerRewardsEnabled}, and run server-side only.
 */
public final class GunnerAbilityModule extends GunnerPowerupAccess {

    private static final GunnerAbilityModule INSTANCE = new GunnerAbilityModule();

    /**
     * Deterministic chance source for the ammo-saver roll. Production uses
     * {@link Math#random()}; tests inject a fixed source.
     */
    @FunctionalInterface
    interface ChanceSource {
        boolean chance(double probability);
    }

    private ChanceSource chanceSource = p -> Math.random() < p;

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

    private boolean listenersRegistered = false;

    /** 60 s throttle window for the two high-frequency WARN logs below. */
    private static final long WARN_THROTTLE_NS = 60_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_POWERUP_QUERY_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong LAST_DAMAGE_HANDLER_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0);

    private GunnerAbilityModule() {
    }

    public static GunnerAbilityModule instance() {
        return INSTANCE;
    }

    /** Registers the damage listeners (idempotent). */
    public static void init(IEventBus gameBus) {
        if (INSTANCE.listenersRegistered) {
            return;
        }
        INSTANCE.listenersRegistered = true;
        gameBus.addListener(GunnerAbilityModule::onLivingDamagePre);
        TCTHIntegration.LOGGER.info("[TCTH] Gunner ability module active (marksmanship / ammo / defense / study)");
    }

    // ---- tier query (Jobs+ public API, fail-closed) ----

    @Override
    public GunnerPowerupTier highestActiveTier(ServerPlayer player, GunnerAbilityRoute route) {
        if (player == null || route == null) {
            return GunnerPowerupTier.NONE;
        }
        try {
            if (!(player instanceof JobsServerPlayer jobsServerPlayer)) {
                return GunnerPowerupTier.NONE;
            }
            Job job = jobsServerPlayer.jobsplus$getJob(JobInstance.of(GUNNER_JOB));
            if (job == null) {
                return GunnerPowerupTier.NONE;
            }
            JobPowerupManager powerupManager = job.getPowerupManager();
            boolean i = isActive(powerupManager, route.nodeLocation(route.nodeI()));
            boolean ii = isActive(powerupManager, route.nodeLocation(route.nodeII()));
            boolean iii = isActive(powerupManager, route.nodeLocation(route.nodeIII()));
            return highestActive(i, ii, iii);
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Gunner powerup query failed for route " + route + ": " + e);
            return GunnerPowerupTier.NONE;
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

    // ---- route multipliers (pure; exact constants, no stacking) ----

    /** Marksmanship damage multiplier by tier (1.05 / 1.10 / 1.15). */
    public static float marksmanshipMultiplier(GunnerPowerupTier tier) {
        return switch (tier) {
            case I -> 1.05f;
            case II -> 1.10f;
            case III -> 1.15f;
            case NONE -> 1.0f;
        };
    }

    /** Battlefield-defense damage multiplier by tier (0.90 / 0.80 / 0.70). */
    public static float defenseMultiplier(GunnerPowerupTier tier) {
        return switch (tier) {
            case I -> 0.90f;
            case II -> 0.80f;
            case III -> 0.70f;
            case NONE -> 1.0f;
        };
    }

    /** Ammo-save probability by tier (0.05 / 0.10 / 0.15). */
    public static double ammoSaveChance(GunnerPowerupTier tier) {
        return switch (tier) {
            case I -> 0.05;
            case II -> 0.10;
            case III -> 0.15;
            case NONE -> 0.0;
        };
    }

    /** Gunner-study job-experience multiplier by tier (1.15 / 1.35 / 1.60). */
    public static float experienceMultiplier(GunnerPowerupTier tier) {
        return switch (tier) {
            case I -> 1.15f;
            case II -> 1.35f;
            case III -> 1.60f;
            case NONE -> 1.0f;
        };
    }

    // ---- ammo saver (called from the conditional SG mixin) ----

    /**
     * Whether one real ammo-deduction entry should skip consumption. Called
     * once per real entry — the common {@code handleShoot} {@code Math.max}
     * redirect, and (separately) each BEAM-period {@code consumeAmmo} that
     * passes the real-deduction preconditions in {@code AmmoSaverBeamGate}.
     * Not "once per shot" across all fire modes: a BEAM tick may hit both
     * entries. Gated by the route switches; fails closed.
     */
    public static boolean ammoSaverShouldSave(ServerPlayer player) {
        if (player == null || !ammoSaverEnabled()) {
            return false;
        }
        GunnerPowerupTier tier = INSTANCE.highestActiveTier(player, GunnerAbilityRoute.AMMO_SAVER);
        double chance = ammoSaveChance(tier);
        if (chance <= 0.0) {
            return false;
        }
        try {
            return INSTANCE.chanceSource.chance(chance);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ---- damage listeners (marksmanship + battlefield defense) ----

    static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        if (event == null || event.getEntity() == null || event.getSource() == null) {
            return;
        }
        Entity victim = event.getEntity();
        DamageSource source = event.getSource();
        try {
            if (victim instanceof ServerPlayer player) {
                // Battlefield defense: player takes SG firearm damage.
                if (defenseEnabled() && SgDamageEvidence.isSgFirearmDamage(source, victim)) {
                    GunnerPowerupTier tier = INSTANCE.highestActiveTier(player, GunnerAbilityRoute.DEFENSE);
                    float mult = defenseMultiplier(tier);
                    if (mult < 1.0f) {
                        event.setNewDamage(event.getNewDamage() * mult);
                    }
                }
                return;
            }
            // Marksmanship: real player deals SG firearm damage to a non-player
            // target. PvP never applies (victim is a player, handled above).
            Entity causing = source.getEntity();
            if (causing instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
                if (marksmanshipEnabled() && SgDamageEvidence.isSgFirearmDamage(source, victim)) {
                    GunnerPowerupTier tier = INSTANCE.highestActiveTier(player, GunnerAbilityRoute.MARKSMANSHIP);
                    float mult = marksmanshipMultiplier(tier);
                    if (mult > 1.0f) {
                        event.setNewDamage(event.getNewDamage() * mult);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // Isolated: a broken ability query must never alter or break damage.
            warnThrottled(LAST_DAMAGE_HANDLER_WARN_NANOS,
                    "[TCTH] Gunner ability damage handler failed: " + e);
        }
    }

    // ---- config gating (all fail closed) ----

    /** Config suppliers, injectable for tests (defaults read the real Config). */
    static java.util.function.BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    static java.util.function.BooleanSupplier integrationEnabledSupplier = Config.GUNNER_INTEGRATION_ENABLED::get;
    static java.util.function.BooleanSupplier abilitiesMasterSupplier = Config.GUNNER_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier marksmanshipSupplier = Config.GUN_DAMAGE_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier ammoSaverSupplier = Config.GUN_AMMO_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier defenseSupplier = Config.GUN_DEFENSE_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier experienceSupplier = Config.GUN_EXPERIENCE_ABILITIES_ENABLED::get;

    private static boolean masterEnabled() {
        try {
            return frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && abilitiesMasterSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean marksmanshipEnabled() {
        try {
            return masterEnabled() && marksmanshipSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean ammoSaverEnabled() {
        try {
            return masterEnabled() && ammoSaverSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean defenseEnabled() {
        try {
            return masterEnabled() && defenseSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean experienceEnabled() {
        try {
            return masterEnabled() && experienceSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** At most one WARN per 60 s window per call site. */
    private static void warnThrottled(java.util.concurrent.atomic.AtomicLong lastNanos, String message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (now - last >= WARN_THROTTLE_NS && lastNanos.compareAndSet(last, now)) {
            TCTHIntegration.LOGGER.warn(message);
        }
    }

    // ---- test hooks ----

    static void setChanceSourceForTesting(ChanceSource source) {
        INSTANCE.chanceSource = source != null ? source : p -> Math.random() < p;
    }

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver != null ? resolver : PowerupInstance::of;
    }

    static void setConfigSuppliersForTesting(
            java.util.function.BooleanSupplier framework,
            java.util.function.BooleanSupplier integration,
            java.util.function.BooleanSupplier master,
            java.util.function.BooleanSupplier marksmanship,
            java.util.function.BooleanSupplier ammoSaver,
            java.util.function.BooleanSupplier defense,
            java.util.function.BooleanSupplier experience) {
        frameworkEnabledSupplier = framework;
        integrationEnabledSupplier = integration;
        abilitiesMasterSupplier = master;
        marksmanshipSupplier = marksmanship;
        ammoSaverSupplier = ammoSaver;
        defenseSupplier = defense;
        experienceSupplier = experience;
    }

    static void resetForTesting() {
        INSTANCE.chanceSource = p -> Math.random() < p;
        INSTANCE.powerupResolver = PowerupInstance::of;
        INSTANCE.listenersRegistered = false;
        LAST_POWERUP_QUERY_WARN_NANOS.set(0);
        LAST_DAMAGE_HANDLER_WARN_NANOS.set(0);
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.GUNNER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.GUNNER_ABILITIES_ENABLED::get;
        marksmanshipSupplier = Config.GUN_DAMAGE_ABILITIES_ENABLED::get;
        ammoSaverSupplier = Config.GUN_AMMO_ABILITIES_ENABLED::get;
        defenseSupplier = Config.GUN_DEFENSE_ABILITIES_ENABLED::get;
        experienceSupplier = Config.GUN_EXPERIENCE_ABILITIES_ENABLED::get;
    }
}
