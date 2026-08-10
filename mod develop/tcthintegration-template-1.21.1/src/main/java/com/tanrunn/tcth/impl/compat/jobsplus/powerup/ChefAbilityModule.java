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

import net.minecraft.server.level.ServerPlayer;

/**
 * Jobs+-backed implementation of {@link ChefPowerupAccess}.
 *
 * <p>Lives in the conditional compat package and is only ever loaded (via the
 * {@code jobsplus} compat module) when Jobs+ is installed; the class is never
 * resolved otherwise. Queries go through Jobs+' own public API
 * ({@code ServerPlayer instanceof JobsServerPlayer} → {@code jobsplus$getJob}
 * → {@code JobPowerupManager.getPowerup} → {@code PowerupState.ACTIVE}); no
 * player NBT is ever read or written.
 *
 * <p>No long-lived caching: a powerup can be bought and toggled at any moment,
 * so the state is queried live on every call (the Jobs+ query is cheap and
 * local). Query failures are caught and mapped to {@link ChefPowerupTier#NONE}
 * so a transient error can never break a tick.
 */
public final class ChefAbilityModule extends ChefPowerupAccess {

    private static final ChefAbilityModule INSTANCE = new ChefAbilityModule();

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

    private ChefAbilityModule() {
    }

    public static ChefAbilityModule instance() {
        return INSTANCE;
    }

    @Override
    public ChefPowerupTier highestActiveTier(ServerPlayer player, ChefAbilityRoute route) {
        if (player == null || route == null) {
            return ChefPowerupTier.NONE;
        }
        try {
            if (!(player instanceof JobsServerPlayer jobsServerPlayer)) {
                return ChefPowerupTier.NONE;
            }
            Job job = jobsServerPlayer.jobsplus$getJob(JobInstance.of(CHEF_JOB));
            if (job == null) {
                return ChefPowerupTier.NONE;
            }
            JobPowerupManager powerupManager = job.getPowerupManager();
            boolean i = isActive(powerupManager, route.nodeLocation(route.nodeI()));
            boolean ii = isActive(powerupManager, route.nodeLocation(route.nodeII()));
            boolean iii = isActive(powerupManager, route.nodeLocation(route.nodeIII()));
            return highestActive(i, ii, iii);
        } catch (RuntimeException | LinkageError e) {
            // Isolated: a broken query must never interrupt a tick; the warn is
            // throttled (60 s) because this path runs inside the per-durability
            // mixin — a persistent failure would otherwise spam the log on
            // every tool use.
            warnThrottled(LAST_POWERUP_QUERY_WARN_NANOS,
                    "[TCTH] Chef powerup query failed for route " + route + ": " + e);
            return ChefPowerupTier.NONE;
        }
    }

    private boolean isActive(JobPowerupManager powerupManager, net.minecraft.resources.ResourceLocation node) {
        PowerupInstance instance = powerupResolver.resolve(node);
        if (instance == null) {
            // Jobs+ data not loaded (or node unknown): treat as inactive.
            return false;
        }
        java.util.Optional<Powerup> powerup = powerupManager.getPowerup(instance);
        return powerup.map(p -> p.getState() == PowerupState.ACTIVE).orElse(false);
    }

    // ---- knife route (durability skip, Java-driven since 4C) ----

    /** The {@code #c:tools/knife} tag provided by Farmer's Delight etc. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> KNIVES_TAG =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    net.minecraft.resources.ResourceLocation.parse("c:tools/knife"));

    /** Skip-durability chance percent per tier (10 / 20 / 35). */
    public static int knifeChancePct(ChefPowerupTier tier) {
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

    /** Knife-route gate: framework + chef master + knife route switches, fail-closed. */
    private static java.util.function.BooleanSupplier knifeEnabledSupplier =
            ChefAbilityModule::defaultKnifeEnabled;

    private static final long WARN_THROTTLE_NS = 60_000_000_000L; // 60 s
    private static final java.util.concurrent.atomic.AtomicLong LAST_POWERUP_QUERY_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static final java.util.concurrent.atomic.AtomicLong LAST_KNIFE_HANDLER_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(0L);

    private static boolean defaultKnifeEnabled() {
        return Config.ENABLED.get()
                && Config.CHEF_ABILITIES_ENABLED.get()
                && Config.KNIFE_DURABILITY_ABILITIES_ENABLED.get();
    }

    public static boolean knifeEnabled() {
        try {
            return knifeEnabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_KNIFE_HANDLER_WARN_NANOS,
                    "[TCTH] Knife toggle config read failed; abilities fail-closed (disabled): " + e);
            return false;
        }
    }

    /**
     * Whether this durability loss should be skipped for the player's knife.
     * Same NeoForge-21.1 rationale as the farmer tilling mixin (see
     * {@code ItemStackDurabilityMixin}): the Arc data-driven knife route
     * ({@code arc:on_hurt_item}) never fires because Arc injects the unused
     * ServerPlayer wrapper overload.
     */
    public static boolean shouldSkipKnifeDurability(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        if (!knifeEnabled()) {
            return false;
        }
        if (player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!stack.is(KNIVES_TAG)) {
            return false; // never affects non-knives
        }
        if (player.getAbilities() != null && player.getAbilities().instabuild) {
            return false;
        }
        try {
            ChefPowerupTier tier = INSTANCE.highestActiveTier(player, ChefAbilityRoute.KNIFE);
            int pct = knifeChancePct(tier);
            if (pct <= 0) {
                return false;
            }
            return randomPctSupplier.getAsInt() < pct;
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(LAST_KNIFE_HANDLER_WARN_NANOS,
                    "[TCTH] Chef knife durability query failed: " + e);
            return false; // fail-closed: never skip on a broken query
        }
    }

    /** 60-second warn throttle for the per-durability mixin path (see farmer). */
    static void warnThrottled(java.util.concurrent.atomic.AtomicLong lastNanos, String message) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        if (last != 0L && now - last < WARN_THROTTLE_NS) {
            return;
        }
        lastNanos.set(now);
        TCTHIntegration.LOGGER.warn(message);
    }

    // ---- test hooks ----

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver;
    }

    static void setRandomPctForTesting(java.util.function.IntSupplier supplier) {
        randomPctSupplier = supplier;
    }

    static void setKnifeEnabledSupplierForTesting(java.util.function.BooleanSupplier supplier) {
        knifeEnabledSupplier = supplier;
    }

    static void resetForTesting() {
        INSTANCE.powerupResolver = PowerupInstance::of;
        randomPctSupplier = () -> net.minecraft.util.RandomSource.create().nextInt(100);
        knifeEnabledSupplier = ChefAbilityModule::defaultKnifeEnabled;
        LAST_POWERUP_QUERY_WARN_NANOS.set(0L);
        LAST_KNIFE_HANDLER_WARN_NANOS.set(0L);
    }
}
