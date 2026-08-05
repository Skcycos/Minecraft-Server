package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance;
import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
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
            // Isolated: a broken query must never interrupt a tick.
            TCTHIntegration.LOGGER.warn("[TCTH] Chef powerup query failed for route {}: {}",
                    route, e.toString());
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

    // ---- test hooks ----

    static void setPowerupResolverForTesting(PowerupInstanceResolver resolver) {
        INSTANCE.powerupResolver = resolver;
    }

    static void resetForTesting() {
        INSTANCE.powerupResolver = PowerupInstance::of;
    }
}
