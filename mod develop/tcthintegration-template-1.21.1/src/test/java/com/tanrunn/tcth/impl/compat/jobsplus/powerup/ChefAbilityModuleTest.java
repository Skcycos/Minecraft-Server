package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance;
import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
import com.daqem.jobsplus.player.job.powerup.PowerupType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 3D: ChefAbilityModule query semantics — no tcth:chef job -> NONE,
 * highest active tier only, exception isolation.
 */
class ChefAbilityModuleTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        ChefAbilityModule.resetForTesting();
    }

    /** Constructs a real PowerupInstance (Jobs+ data is absent in JUnit). */
    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:chef"), null,
                new ItemStack(Items.IRON_SWORD), 5, 5, PowerupType.BASIC);
    }

    @Test
    void nonJobsServerPlayerReturnsNone() {
        ServerPlayer plainPlayer = Mockito.mock(ServerPlayer.class);
        assertEquals(ChefPowerupTier.NONE,
                ChefAbilityModule.instance().highestActiveTier(plainPlayer, ChefAbilityRoute.KNIFE));
    }

    @Test
    void noChefJobReturnsNone() {
        JobsServerPlayer jobsPlayer = Mockito.mock(JobsServerPlayer.class);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(ChefPowerupAccess.CHEF_JOB))).thenReturn(null);
        assertEquals(ChefPowerupTier.NONE,
                ChefAbilityModule.instance().highestActiveTier(Mockito.mock(ServerPlayer.class), ChefAbilityRoute.KNIFE));
        // Also: player without the interface must not throw.
        assertEquals(ChefPowerupTier.NONE,
                ChefAbilityModule.instance().highestActiveTier(Mockito.mock(ServerPlayer.class), ChefAbilityRoute.STUDY));
    }

    @Test
    void highestActiveTierReflectsJobPowerupStates() {
        // In production ServerPlayer is mixed-in as JobsServerPlayer; simulate
        // that with extraInterfaces so `instanceof JobsServerPlayer` holds.
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) player;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        Mockito.when(job.getPowerupManager()).thenReturn(manager);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(ChefPowerupAccess.CHEF_JOB))).thenReturn(job);

        // PowerupInstance.of() needs Jobs+ data; inject a resolver that builds
        // real instances. Dispatch by location in the answer.
        ChefAbilityModule.setPowerupResolverForTesting(ChefAbilityModuleTest::instanceOf);
        Mockito.when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            return Optional.ofNullable(STATES.get(instance.getLocation().getPath()))
                    .map(state -> {
                        Powerup p = Mockito.mock(Powerup.class);
                        Mockito.when(p.getState()).thenReturn(state);
                        return p;
                    });
        });

        // Only knife_basic active -> I.
        STATES.put("chef/knife_basic", PowerupState.ACTIVE);
        STATES.put("chef/knife_adept", PowerupState.NOT_OWNED);
        STATES.put("chef/knife_expert", PowerupState.LOCKED);
        assertEquals(ChefPowerupTier.I, ChefAbilityModule.instance().highestActiveTier(player, ChefAbilityRoute.KNIFE));

        // All three owned/active -> III only (35%, not 10+20+35).
        STATES.put("chef/knife_basic", PowerupState.ACTIVE);
        STATES.put("chef/knife_adept", PowerupState.ACTIVE);
        STATES.put("chef/knife_expert", PowerupState.ACTIVE);
        assertEquals(ChefPowerupTier.III, ChefAbilityModule.instance().highestActiveTier(player, ChefAbilityRoute.KNIFE));

        // INACTIVE (purchased but not toggled on) must not count.
        STATES.put("chef/knife_basic", PowerupState.INACTIVE);
        STATES.put("chef/knife_adept", PowerupState.INACTIVE);
        STATES.put("chef/knife_expert", PowerupState.NOT_OWNED);
        assertEquals(ChefPowerupTier.NONE, ChefAbilityModule.instance().highestActiveTier(player, ChefAbilityRoute.KNIFE));
    }

    private static final java.util.Map<String, PowerupState> STATES = new java.util.HashMap<>();

    @Test
    void brokenQueryReturnsNoneWithoutThrowing() {
        JobsServerPlayer jobsPlayer = Mockito.mock(JobsServerPlayer.class);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(ChefPowerupAccess.CHEF_JOB)))
                .thenThrow(new IllegalStateException("jobsplus data corrupt"));
        assertEquals(ChefPowerupTier.NONE,
                ChefAbilityModule.instance().highestActiveTier(Mockito.mock(ServerPlayer.class), ChefAbilityRoute.HEARTH));
    }
}
