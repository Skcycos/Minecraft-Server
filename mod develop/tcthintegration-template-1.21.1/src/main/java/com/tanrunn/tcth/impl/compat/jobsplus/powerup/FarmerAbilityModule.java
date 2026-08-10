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
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.IEventBus;

/**
 * Farmer ability routes (phase 4B): tilling / harvest / livestock / study.
 *
 * <p>This class lives in the {@code jobsplus} compat package and is only
 * loaded when Jobs+ is installed; it is never resolved otherwise. Queries go
 * through Jobs+' own public API ({@code JobsServerPlayer} → {@code Job} →
 * {@code JobPowerupManager}).
 *
 * <p>Route mechanics:
 * <ul>
 *   <li><strong>Tilling</strong> — Java-driven via
 *       {@code ItemStackDurabilityMixin} on {@code #minecraft:hoes} tools
 *       (10% / 20% / 35% by tier). <em>Historical:</em> the original
 *       data-driven design ({@code arc:on_hurt_item} + {@code arc:cancel_action})
 *       was abandoned in 4C — Arc 9.0.0 injects the unused NeoForge
 *       ServerPlayer wrapper overload, so the event never fires on
 *       NeoForge 21.1.247.</li>
 *   <li><strong>Harvest</strong> — Java-driven: on {@link CropHarvestedEvent}
 *       grants Haste I 5 s / Haste I + Speed I 8 s / Haste I + Speed I 12 s,
 *       shared 10 s cooldown committed only after a successful grant.</li>
 *   <li><strong>Livestock</strong> — data-driven via {@code arc:on_breed_animal},
 *       {@code arc:on_tame_animal} and the shearing interaction, rewarding
 *       {@code tcth:farmer_livestock_effects} (tier-selected by
 *       {@code powerup_not_active}); shared 20 s cooldown condition
 *       {@code tcth:farmer_livestock_cooldown}.</li>
 *   <li><strong>Study</strong> — data-driven via {@code jobsplus:on_job_exp} +
 *       {@code jobsplus:job_exp_multiplier} (×1.15 / ×1.35 / ×1.60), highest
 *       active tier only.</li>
 * </ul>
 *
 * <p>All config reads fail closed (an exception never flips a gate open), and
 * every handler is isolated so a broken query can never interrupt a tick.
 */
public final class FarmerAbilityModule extends FarmerPowerupAccess {

    private static final FarmerAbilityModule INSTANCE = new FarmerAbilityModule();

    private PowerupInstanceResolver powerupResolver = PowerupInstance::of;

    private boolean listenersRegistered = false;

    private static final long WARN_THROTTLE_NS = 60_000_000_000L;
    private static final AtomicLong LAST_POWERUP_QUERY_WARN_NANOS = new AtomicLong(0L);
    private static final AtomicLong LAST_HARVEST_HANDLER_WARN_NANOS = new AtomicLong(0L);

    private FarmerAbilityModule() {
    }

    public static FarmerAbilityModule instance() {
        return INSTANCE;
    }

    /** Registers the game-bus listeners (idempotent). */
    public static void init(IEventBus gameBus) {
        if (INSTANCE.listenersRegistered) {
            return;
        }
        INSTANCE.listenersRegistered = true;
        gameBus.addListener(FarmerAbilityModule::onCropHarvested);
        FarmerHarvestCooldown.instance().registerLifecycle(gameBus);
        FarmerLivestockCooldown.instance().registerLifecycle(gameBus);
        TCTHIntegration.LOGGER.info("[TCTH] Farmer ability tree active (tilling / harvest / livestock / study routes)");
    }

    // ---- tier query (Jobs+ public API, fail-closed) ----

    @Override
    public FarmerPowerupTier highestActiveTier(ServerPlayer player, FarmerAbilityRoute route) {
        if (player == null || route == null) {
            return FarmerPowerupTier.NONE;
        }
        try {
            if (!(player instanceof JobsServerPlayer jobsServerPlayer)) {
                return FarmerPowerupTier.NONE;
            }
            Job job = jobsServerPlayer.jobsplus$getJob(JobInstance.of(FARMER_JOB));
            if (job == null) {
                return FarmerPowerupTier.NONE;
            }
            JobPowerupManager powerupManager = job.getPowerupManager();
            boolean i = isActive(powerupManager, route.nodeLocation(route.nodeI()));
            boolean ii = isActive(powerupManager, route.nodeLocation(route.nodeII()));
            boolean iii = isActive(powerupManager, route.nodeLocation(route.nodeIII()));
            return highestActive(i, ii, iii);
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Farmer powerup query failed for route " + route + ": " + e);
            return FarmerPowerupTier.NONE;
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

    // ---- harvest route (CropHarvestedEvent → short effects) ----

    /** Haste I duration ticks per tier (I 5 s / II 8 s / III 12 s). */
    public static int harvestHasteTicks(FarmerPowerupTier tier) {
        return switch (tier) {
            case I -> 100;
            case II -> 160;
            case III -> 240;
            case NONE -> 0;
        };
    }

    /** Speed I duration ticks per tier (I none / II 8 s / III 12 s). */
    public static int harvestSpeedTicks(FarmerPowerupTier tier) {
        return switch (tier) {
            case II -> 160;
            case III -> 240;
            case I, NONE -> 0;
        };
    }

    /**
     * Applies the harvest-route effect package for a player's highest active
     * tier. Higher tier overwrites the lower-tier effects (never stacks).
     * Returns whether any effect applied.
     */
    static boolean applyHarvestEffects(ServerPlayer player, FarmerPowerupTier tier) {
        if (player == null || tier == null || tier == FarmerPowerupTier.NONE) {
            return false;
        }
        boolean any = false;
        int hasteTicks = harvestHasteTicks(tier);
        if (hasteTicks > 0) {
            any |= player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, hasteTicks, 0));
        }
        int speedTicks = harvestSpeedTicks(tier);
        if (speedTicks > 0) {
            any |= player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speedTicks, 0));
        }
        return any;
    }

    static void onCropHarvested(CropHarvestedEvent event) {
        try {
            if (event == null || event.getPlayer() == null || event.isAutomated()) {
                return;
            }
            if (!harvestEnabled()) {
                return;
            }
            ServerPlayer player = event.getPlayer();
            if (FarmerHarvestCooldown.instance().isOnCooldown(player.getUUID(), player)) {
                return;
            }
            FarmerPowerupTier abilityTier = INSTANCE.highestActiveTier(player, FarmerAbilityRoute.HARVEST);
            if (applyHarvestEffects(player, abilityTier)) {
                // Success-driven commit: only an actual grant starts the window.
                FarmerHarvestCooldown.instance().commit(player.getUUID(), player);
            }
        } catch (RuntimeException | LinkageError e) {
            // Isolated: a broken ability query must never break the event.
            warnThrottled(LAST_HARVEST_HANDLER_WARN_NANOS,
                    "[TCTH] Farmer harvest ability handler failed: " + e);
        }
    }

    // ---- tilling route (hoe durability skip, Java-driven) ----

    /** The {@code #minecraft:hoes} tag — the audited real hoe tag (extended
     * by mods). Phase 4B audits: no {@code c:tools/hoes} exists on the server. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> HOES_TAG =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    ResourceLocation.parse("minecraft:hoes"));

    /** Skip-durability chance percent per tier (10 / 20 / 35). */
    public static int tillingChancePct(FarmerPowerupTier tier) {
        return switch (tier) {
            case I -> 10;
            case II -> 20;
            case III -> 35;
            case NONE -> 0;
        };
    }

    /** Random percent source; injectable for tests (0..99). */
    private static java.util.function.IntSupplier randomPctSupplier =
            () -> net.minecraft.util.RandomSource.create().nextInt(100);

    /**
     * Whether this durability loss should be skipped for the player's hoe.
     * The NeoForge 21.1 runtime keeps the real durability logic in
     * {@code ItemStack.hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)}
     * (Arc 9.0.0 injects the thin ServerPlayer wrapper, which is never called
     * by hoes — the 4C audit finding); TCTH injects the LivingEntity overload
     * via {@code ItemStackDurabilityMixin} and cancels it on a hit.
     */
    public static boolean shouldSkipHoeDurability(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        if (!tillingEnabled()) {
            return false; // route gates closed
        }
        if (player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!stack.is(HOES_TAG)) {
            return false; // never affects non-hoes
        }
        if (player.getAbilities() != null && player.getAbilities().instabuild) {
            return false; // creative tools do not lose durability anyway
        }
        try {
            FarmerPowerupTier tier = INSTANCE.highestActiveTier(player, FarmerAbilityRoute.TILLING);
            int pct = tillingChancePct(tier);
            if (pct <= 0) {
                return false;
            }
            return randomPctSupplier.getAsInt() < pct;
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Farmer tilling durability query failed: " + e);
            return false; // fail-closed: never skip on a broken query
        }
    }

    // ---- study route multipliers (pure; exact constants, no stacking) ----

    /** Farmer-study job-experience multiplier by tier (1.15 / 1.35 / 1.60). */
    public static float experienceMultiplier(FarmerPowerupTier tier) {
        return switch (tier) {
            case I -> 1.15f;
            case II -> 1.35f;
            case III -> 1.60f;
            case NONE -> 1.0f;
        };
    }

    // ---- config gating (all fail closed) ----

    static BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    static BooleanSupplier integrationEnabledSupplier = Config.FARMER_INTEGRATION_ENABLED::get;
    static BooleanSupplier abilitiesMasterSupplier = Config.FARMER_ABILITIES_ENABLED::get;
    static BooleanSupplier tillingSupplier = Config.TILLING_DURABILITY_ABILITIES_ENABLED::get;
    static BooleanSupplier harvestSupplier = Config.FARMER_HARVEST_ABILITIES_ENABLED::get;
    static BooleanSupplier livestockSupplier = Config.FARMER_LIVESTOCK_ABILITIES_ENABLED::get;
    static BooleanSupplier studySupplier = Config.FARMER_STUDY_ABILITIES_ENABLED::get;

    private static boolean masterEnabled() {
        try {
            return frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && abilitiesMasterSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Farmer ability master gate read failed: " + e);
            return false;
        }
    }

    /** Tilling route (Java-driven; gate also read by the durability mixin path). */
    public static boolean tillingEnabled() {
        return masterEnabled() && gate(tillingSupplier);
    }

    /** Harvest route (Java-driven). */
    static boolean harvestEnabled() {
        return masterEnabled() && gate(harvestSupplier);
    }

    /** Livestock route (data-driven condition also reads the same gates). */
    static boolean livestockEnabled() {
        return masterEnabled() && gate(livestockSupplier);
    }

    /** Study route (data-driven condition also reads the same gates). */
    static boolean studyEnabled() {
        return masterEnabled() && gate(studySupplier);
    }

    private static boolean gate(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Farmer ability route gate read failed: " + e);
            return false;
        }
    }

    private static void warnThrottled(AtomicLong lastNanos, String message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (last != 0L && now - last < WARN_THROTTLE_NS) {
            return;
        }
        lastNanos.set(now);
        TCTHIntegration.LOGGER.warn(message);
    }

    // ---- test hooks ----

    interface PowerupInstanceResolver {
        PowerupInstance resolve(ResourceLocation location);
    }

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver;
    }

    static void setRandomPctForTesting(java.util.function.IntSupplier supplier) {
        randomPctSupplier = supplier;
    }

    static void setConfigSuppliersForTesting(
            BooleanSupplier framework,
            BooleanSupplier integration,
            BooleanSupplier master,
            BooleanSupplier tilling,
            BooleanSupplier harvest,
            BooleanSupplier livestock,
            BooleanSupplier study) {
        frameworkEnabledSupplier = framework != null ? framework : Config.ENABLED::get;
        integrationEnabledSupplier = integration != null ? integration : Config.FARMER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = master != null ? master : Config.FARMER_ABILITIES_ENABLED::get;
        tillingSupplier = tilling != null ? tilling : Config.TILLING_DURABILITY_ABILITIES_ENABLED::get;
        harvestSupplier = harvest != null ? harvest : Config.FARMER_HARVEST_ABILITIES_ENABLED::get;
        livestockSupplier = livestock != null ? livestock : Config.FARMER_LIVESTOCK_ABILITIES_ENABLED::get;
        studySupplier = study != null ? study : Config.FARMER_STUDY_ABILITIES_ENABLED::get;
    }

    static void resetForTesting() {
        INSTANCE.listenersRegistered = false;
        randomPctSupplier = () -> net.minecraft.util.RandomSource.create().nextInt(100);
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.FARMER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.FARMER_ABILITIES_ENABLED::get;
        tillingSupplier = Config.TILLING_DURABILITY_ABILITIES_ENABLED::get;
        harvestSupplier = Config.FARMER_HARVEST_ABILITIES_ENABLED::get;
        livestockSupplier = Config.FARMER_LIVESTOCK_ABILITIES_ENABLED::get;
        studySupplier = Config.FARMER_STUDY_ABILITIES_ENABLED::get;
        FarmerHarvestCooldown.resetForTesting();
        FarmerLivestockCooldown.resetForTesting();
    }
}
