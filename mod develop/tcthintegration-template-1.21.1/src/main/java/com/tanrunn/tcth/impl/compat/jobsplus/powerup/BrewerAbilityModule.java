package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

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
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Jobs+-backed implementation of {@link BrewerPowerupAccess} plus the brewer
 * ability-route effects (phase 7E).
 *
 * <p>Lives in the conditional compat package and is only ever loaded when
 * Jobs+ is installed; the class is never resolved otherwise. Queries go
 * through Jobs+' own public API ({@code JobsServerPlayer} → {@code Job} →
 * {@code JobPowerupManager.getPowerup} → {@code PowerupState.ACTIVE}); no
 * player NBT is ever read or written. No long-lived caching — a powerup can be
 * bought and toggled at any moment, so the state is queried live on every
 * call. Query failures are caught and mapped to {@link BrewerPowerupTier#NONE}
 * so a transient error can never break a tick.
 *
 * <p>Route effects:
 * <ul>
 *   <li><b>Brewing (调饮)</b>: preparing a graded beverage ({@code BeveragePreparedEvent},
 *       non-automated, COMMON/T2) grants Speed I / Speed I+Luck I with a
 *       longer duration at higher tiers; higher tier overwrites, never stacks.</li>
 *   <li><b>Tasting (品鉴)</b>: effect package is applied by the Arc
 *       {@code tcth:tasting_effects} reward on {@code arc:on_drink} (see
 *       {@link BrewerTastingEffectsReward}); this module owns the shared
 *       {@link BrewerDrinkCooldown} lifecycle.</li>
 *   <li><b>Resistance (魔酿耐受)</b>: reliably-recognised magical /
 *       indirect-magical / wither damage taken by the player ×0.90 / ×0.80 /
 *       ×0.65 (10% / 20% / 35% reduction). Never full immunity; fire, fall,
 *       melee and projectile damage are unaffected.</li>
 *   <li><b>Study (研修)</b>: tcth:brewer job experience ×1.15 / ×1.35 / ×1.60
 *       (data-driven via {@code jobsplus:on_job_exp} + {@code job_exp_multiplier};
 *       the multiplier constants live here for consistency tests).</li>
 * </ul>
 *
 * <p>Every route is gated by {@code Config.ENABLED} &&
 * {@code Config.BREWER_INTEGRATION_ENABLED} && {@code Config.BREWER_ABILITIES_ENABLED}
 * and its own route switch; any config read failure fails closed (never
 * flipped by inverted conditions). Only the highest active tier of a route
 * applies (no stacking). Effects never touch BeveragePreparedEvent attribution,
 * never bypass {@code brewerRewardsEnabled}, and run server-side only.
 */
public final class BrewerAbilityModule extends BrewerPowerupAccess {

    private static final BrewerAbilityModule INSTANCE = new BrewerAbilityModule();

    /**
     * Powerup instance lookup. Production resolves from Jobs+ data via
     * {@link PowerupInstance#of}; tests inject constructed instances because
     * Jobs+ data is not loaded in a bare JUnit JVM.
     */
    @FunctionalInterface
    interface PowerupInstanceResolver {
        PowerupInstance resolve(ResourceLocation node);
    }

    private PowerupInstanceResolver powerupResolver = PowerupInstance::of;

    private boolean listenersRegistered = false;

    /** 60 s throttle window for the high-frequency WARN logs below. */
    private static final long WARN_THROTTLE_NS = 60_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_POWERUP_QUERY_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong LAST_DAMAGE_HANDLER_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0);

    private BrewerAbilityModule() {
    }

    public static BrewerAbilityModule instance() {
        return INSTANCE;
    }

    /** Registers the game-bus listeners (idempotent). */
    public static void init(IEventBus gameBus) {
        if (INSTANCE.listenersRegistered) {
            return;
        }
        INSTANCE.listenersRegistered = true;
        gameBus.addListener(BrewerAbilityModule::onBeveragePrepared);
        gameBus.addListener(BrewerAbilityModule::onLivingDamagePre);
        BrewerDrinkCooldown.instance().registerLifecycle(gameBus);
        TCTHIntegration.LOGGER.info("[TCTH] Brewer ability module active (brewing / tasting / resistance / study)");
    }

    // ---- tier query (Jobs+ public API, fail-closed) ----

    @Override
    public BrewerPowerupTier highestActiveTier(ServerPlayer player, BrewerAbilityRoute route) {
        if (player == null || route == null) {
            return BrewerPowerupTier.NONE;
        }
        try {
            if (!(player instanceof JobsServerPlayer jobsServerPlayer)) {
                return BrewerPowerupTier.NONE;
            }
            Job job = jobsServerPlayer.jobsplus$getJob(JobInstance.of(BREWER_JOB));
            if (job == null) {
                return BrewerPowerupTier.NONE;
            }
            JobPowerupManager powerupManager = job.getPowerupManager();
            boolean i = isActive(powerupManager, route.nodeLocation(route.nodeI()));
            boolean ii = isActive(powerupManager, route.nodeLocation(route.nodeII()));
            boolean iii = isActive(powerupManager, route.nodeLocation(route.nodeIII()));
            return highestActive(i, ii, iii);
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Brewer powerup query failed for route " + route + ": " + e);
            return BrewerPowerupTier.NONE;
        }
    }

    private boolean isActive(JobPowerupManager powerupManager, ResourceLocation node) {
        PowerupInstance instance = powerupResolver.resolve(node);
        if (instance == null) {
            return false;
        }
        java.util.Optional<Powerup> powerup = powerupManager.getPowerup(instance);
        return powerup.map(p -> p.getState() == PowerupState.ACTIVE).orElse(false);
    }

    // ---- brewing route (BeveragePreparedEvent → short effects) ----

    /** Speed I duration ticks per tier (I 5 s / II 8 s / III 12 s). */
    public static int brewingSpeedTicks(BrewerPowerupTier tier) {
        return switch (tier) {
            case I -> 100;
            case II -> 160;
            case III -> 240;
            case NONE -> 0;
        };
    }

    /** Luck I duration ticks per tier (I none / II 8 s / III 12 s). */
    public static int brewingLuckTicks(BrewerPowerupTier tier) {
        return switch (tier) {
            case II -> 160;
            case III -> 240;
            case I, NONE -> 0;
        };
    }

    /**
     * Applies the brewing-route effect package for a player's highest active
     * tier. Called from {@code onBeveragePrepared}; higher tier overwrites the
     * lower-tier effects (never stacks). Returns whether any effect applied.
     */
    static boolean applyBrewingEffects(ServerPlayer player, BrewerPowerupTier tier) {
        if (player == null || tier == null || tier == BrewerPowerupTier.NONE) {
            return false;
        }
        boolean any = false;
        int speedTicks = brewingSpeedTicks(tier);
        if (speedTicks > 0) {
            any |= player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speedTicks, 0));
        }
        int luckTicks = brewingLuckTicks(tier);
        if (luckTicks > 0) {
            any |= player.addEffect(new MobEffectInstance(MobEffects.LUCK, luckTicks, 0));
        }
        return any;
    }

    static void onBeveragePrepared(BeveragePreparedEvent event) {
        try {
            if (event == null || event.getPlayer() == null || event.isAutomated()) {
                return;
            }
            if (!brewingEnabled()) {
                return;
            }
            BeverageTier tier = event.getTier();
            if (tier == null || tier == BeverageTier.UNKNOWN || tier == BeverageTier.T3) {
                return; // only graded COMMON/T2 beverages grant the brewing boost
            }
            if (!(event.getPlayer() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            BrewerPowerupTier abilityTier = INSTANCE.highestActiveTier(serverPlayer, BrewerAbilityRoute.BREWING);
            applyBrewingEffects(serverPlayer, abilityTier);
        } catch (RuntimeException | LinkageError e) {
            // Isolated: a broken ability query must never break the event.
            warnThrottled(LAST_DAMAGE_HANDLER_WARN_NANOS,
                    "[TCTH] Brewer brewing ability handler failed: " + e);
        }
    }

    // ---- resistance route (magical / indirect-magical / wither damage) ----

    /** Damage multiplier by tier (0.90 / 0.80 / 0.65 → 10% / 20% / 35%). */
    public static float resistanceMultiplier(BrewerPowerupTier tier) {
        return switch (tier) {
            case I -> 0.90f;
            case II -> 0.80f;
            case III -> 0.65f;
            case NONE -> 1.0f;
        };
    }

    /**
     * Strong-evidence magical-damage check. Reliable types only:
     * {@link DamageTypes#MAGIC}, {@link DamageTypes#INDIRECT_MAGIC} and
     * {@link DamageTypes#WITHER}. Fire, fall, melee and projectile damage are
     * deliberately excluded. In Minecraft 1.21.1 there is no dedicated
     * {@code poison} DamageType (poison is a status effect, not a damage
     * event), so poison is out of scope — documented in the 7E report.
     */
    static boolean isMagicalDamage(DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            return source.is(DamageTypes.MAGIC)
                    || source.is(DamageTypes.INDIRECT_MAGIC)
                    || source.is(DamageTypes.WITHER);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        if (event == null || event.getEntity() == null || event.getSource() == null) {
            return;
        }
        Entity victim = event.getEntity();
        DamageSource source = event.getSource();
        try {
            if (!(victim instanceof ServerPlayer player)) {
                return;
            }
            if (player instanceof FakePlayer) {
                return;
            }
            if (!resistanceEnabled()) {
                return;
            }
            if (!isMagicalDamage(source)) {
                return;
            }
            BrewerPowerupTier tier = INSTANCE.highestActiveTier(player, BrewerAbilityRoute.RESISTANCE);
            float mult = resistanceMultiplier(tier);
            if (mult < 1.0f) {
                float current = event.getNewDamage();
                // Fail-closed on non-finite damage: never multiply NaN/inf into
                // a damage event (would corrupt the player's damage pipeline).
                if (!Float.isFinite(current)) {
                    return;
                }
                event.setNewDamage(current * mult);
            }
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_DAMAGE_HANDLER_WARN_NANOS,
                    "[TCTH] Brewer resistance ability handler failed: " + e);
        }
    }

    // ---- study route multipliers (pure; exact constants, no stacking) ----

    /** Brewer-study job-experience multiplier by tier (1.15 / 1.35 / 1.60). */
    public static float experienceMultiplier(BrewerPowerupTier tier) {
        return switch (tier) {
            case I -> 1.15f;
            case II -> 1.35f;
            case III -> 1.60f;
            case NONE -> 1.0f;
        };
    }

    // ---- config gating (all fail closed) ----

    static java.util.function.BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    static java.util.function.BooleanSupplier integrationEnabledSupplier = Config.BREWER_INTEGRATION_ENABLED::get;
    static java.util.function.BooleanSupplier abilitiesMasterSupplier = Config.BREWER_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier brewingSupplier = Config.BREWER_BREWING_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier tastingSupplier = Config.BREWER_TASTING_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier resistanceSupplier = Config.BREWER_RESISTANCE_ABILITIES_ENABLED::get;
    static java.util.function.BooleanSupplier studySupplier = Config.BREWER_STUDY_ABILITIES_ENABLED::get;

    private static boolean masterEnabled() {
        try {
            return frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && abilitiesMasterSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean brewingEnabled() {
        try {
            return masterEnabled() && brewingSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean tastingEnabled() {
        try {
            return masterEnabled() && tastingSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean resistanceEnabled() {
        try {
            return masterEnabled() && resistanceSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean studyEnabled() {
        try {
            return masterEnabled() && studySupplier.getAsBoolean();
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

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver != null ? resolver : PowerupInstance::of;
    }

    static void setConfigSuppliersForTesting(
            BooleanSupplier framework,
            BooleanSupplier integration,
            BooleanSupplier master,
            BooleanSupplier brewing,
            BooleanSupplier tasting,
            BooleanSupplier resistance,
            BooleanSupplier study) {
        frameworkEnabledSupplier = framework;
        integrationEnabledSupplier = integration;
        abilitiesMasterSupplier = master;
        brewingSupplier = brewing;
        tastingSupplier = tasting;
        resistanceSupplier = resistance;
        studySupplier = study;
    }

    static void resetForTesting() {
        INSTANCE.powerupResolver = PowerupInstance::of;
        INSTANCE.listenersRegistered = false;
        LAST_POWERUP_QUERY_WARN_NANOS.set(0);
        LAST_DAMAGE_HANDLER_WARN_NANOS.set(0);
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.BREWER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.BREWER_ABILITIES_ENABLED::get;
        brewingSupplier = Config.BREWER_BREWING_ABILITIES_ENABLED::get;
        tastingSupplier = Config.BREWER_TASTING_ABILITIES_ENABLED::get;
        resistanceSupplier = Config.BREWER_RESISTANCE_ABILITIES_ENABLED::get;
        studySupplier = Config.BREWER_STUDY_ABILITIES_ENABLED::get;
        BrewerDrinkCooldown.resetForTesting();
    }
}
