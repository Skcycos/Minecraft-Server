package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
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
 * Phase 5B: GunnerAbilityModule semantics — tier query via Jobs+ public API
 * (fail-closed), exact route multipliers (no stacking), ammo-saver probability
 * boundaries with an injected deterministic chance source, and config gating.
 */
class GunnerAbilityModuleTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        GunnerAbilityModule.resetForTesting();
    }

    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:gunner"), null,
                new ItemStack(Items.IRON_SWORD), 5, 5, PowerupType.BASIC);
    }

    private static ServerPlayer jobsPlayerWithStates(Map<String, PowerupState> states) {
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) player;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        Mockito.when(job.getPowerupManager()).thenReturn(manager);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(GunnerPowerupAccess.GUNNER_JOB))).thenReturn(job);
        GunnerAbilityModule.setPowerupResolverForTesting(GunnerAbilityModuleTest::instanceOf);
        Mockito.when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            return Optional.ofNullable(states.get(instance.getLocation().getPath()))
                    .map(state -> {
                        Powerup p = Mockito.mock(Powerup.class);
                        Mockito.when(p.getState()).thenReturn(state);
                        return p;
                    });
        });
        return player;
    }

    // ---- tier query ----

    @Test
    void nonJobsServerPlayerReturnsNone() {
        ServerPlayer plain = Mockito.mock(ServerPlayer.class);
        assertEquals(GunnerPowerupTier.NONE,
                GunnerAbilityModule.instance().highestActiveTier(plain, GunnerAbilityRoute.MARKSMANSHIP));
    }

    @Test
    void noGunnerJobReturnsNone() {
        ServerPlayer plain = Mockito.mock(ServerPlayer.class);
        assertEquals(GunnerPowerupTier.NONE,
                GunnerAbilityModule.instance().highestActiveTier(plain, GunnerAbilityRoute.STUDY));
    }

    @Test
    void highestActiveTierReflectsStates() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer player = jobsPlayerWithStates(states);

        states.put("gunner/marksmanship_basic", PowerupState.ACTIVE);
        states.put("gunner/marksmanship_adept", PowerupState.NOT_OWNED);
        states.put("gunner/marksmanship_expert", PowerupState.LOCKED);
        assertEquals(GunnerPowerupTier.I,
                GunnerAbilityModule.instance().highestActiveTier(player, GunnerAbilityRoute.MARKSMANSHIP));

        states.put("gunner/marksmanship_basic", PowerupState.ACTIVE);
        states.put("gunner/marksmanship_adept", PowerupState.ACTIVE);
        states.put("gunner/marksmanship_expert", PowerupState.ACTIVE);
        assertEquals(GunnerPowerupTier.III,
                GunnerAbilityModule.instance().highestActiveTier(player, GunnerAbilityRoute.MARKSMANSHIP));

        states.put("gunner/marksmanship_basic", PowerupState.INACTIVE);
        states.put("gunner/marksmanship_adept", PowerupState.INACTIVE);
        states.put("gunner/marksmanship_expert", PowerupState.NOT_OWNED);
        assertEquals(GunnerPowerupTier.NONE,
                GunnerAbilityModule.instance().highestActiveTier(player, GunnerAbilityRoute.MARKSMANSHIP));
    }

    @Test
    void brokenQueryReturnsNoneWithoutThrowing() {
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        Mockito.when(((JobsServerPlayer) player).jobsplus$getJob(JobInstance.of(GunnerPowerupAccess.GUNNER_JOB)))
                .thenThrow(new IllegalStateException("jobsplus data corrupt"));
        assertEquals(GunnerPowerupTier.NONE,
                GunnerAbilityModule.instance().highestActiveTier(player, GunnerAbilityRoute.DEFENSE));
    }

    // ---- exact multipliers, no stacking ----

    @Test
    void marksmanshipMultipliersExact() {
        assertEquals(1.05f, GunnerAbilityModule.marksmanshipMultiplier(GunnerPowerupTier.I), 0f);
        assertEquals(1.10f, GunnerAbilityModule.marksmanshipMultiplier(GunnerPowerupTier.II), 0f);
        assertEquals(1.15f, GunnerAbilityModule.marksmanshipMultiplier(GunnerPowerupTier.III), 0f);
        assertEquals(1.0f, GunnerAbilityModule.marksmanshipMultiplier(GunnerPowerupTier.NONE), 0f);
    }

    @Test
    void defenseMultipliersExact() {
        assertEquals(0.90f, GunnerAbilityModule.defenseMultiplier(GunnerPowerupTier.I), 0f);
        assertEquals(0.80f, GunnerAbilityModule.defenseMultiplier(GunnerPowerupTier.II), 0f);
        assertEquals(0.70f, GunnerAbilityModule.defenseMultiplier(GunnerPowerupTier.III), 0f);
        assertEquals(1.0f, GunnerAbilityModule.defenseMultiplier(GunnerPowerupTier.NONE), 0f);
    }

    @Test
    void ammoSaveChancesExact() {
        assertEquals(0.05, GunnerAbilityModule.ammoSaveChance(GunnerPowerupTier.I), 0.0);
        assertEquals(0.10, GunnerAbilityModule.ammoSaveChance(GunnerPowerupTier.II), 0.0);
        assertEquals(0.15, GunnerAbilityModule.ammoSaveChance(GunnerPowerupTier.III), 0.0);
        assertEquals(0.0, GunnerAbilityModule.ammoSaveChance(GunnerPowerupTier.NONE), 0.0);
    }

    @Test
    void experienceMultipliersExact() {
        assertEquals(1.15f, GunnerAbilityModule.experienceMultiplier(GunnerPowerupTier.I), 0f);
        assertEquals(1.35f, GunnerAbilityModule.experienceMultiplier(GunnerPowerupTier.II), 0f);
        assertEquals(1.60f, GunnerAbilityModule.experienceMultiplier(GunnerPowerupTier.III), 0f);
        assertEquals(1.0f, GunnerAbilityModule.experienceMultiplier(GunnerPowerupTier.NONE), 0f);
    }

    // ---- ammo saver probability boundaries ----

    @Test
    void ammoSaverBoundaryWithInjectedChanceSource() {
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = new HashMap<>();
        states.put("gunner/ammo_saver_basic", PowerupState.ACTIVE);
        states.put("gunner/ammo_saver_adept", PowerupState.NOT_OWNED);
        states.put("gunner/ammo_saver_expert", PowerupState.NOT_OWNED);
        ServerPlayer player = jobsPlayerWithStates(states);

        // 5% tier: a roll of 0.05 saves, 0.049999 does not (boundary).
        GunnerAbilityModule.setChanceSourceForTesting(p -> p <= 0.05);
        assertTrue(GunnerAbilityModule.ammoSaverShouldSave(player));
        GunnerAbilityModule.setChanceSourceForTesting(p -> p < 0.05);
        assertFalse(GunnerAbilityModule.ammoSaverShouldSave(player));

        // 15% tier with all three active -> III (15%, not 5+10+15).
        states.put("gunner/ammo_saver_adept", PowerupState.ACTIVE);
        states.put("gunner/ammo_saver_expert", PowerupState.ACTIVE);
        GunnerAbilityModule.setChanceSourceForTesting(p -> p <= 0.15);
        assertTrue(GunnerAbilityModule.ammoSaverShouldSave(player));
        GunnerAbilityModule.setChanceSourceForTesting(p -> p <= 0.10);
        assertFalse(GunnerAbilityModule.ammoSaverShouldSave(player));
    }

    @Test
    void ammoSaverNoTierNeverSaves() {
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
        ServerPlayer player = jobsPlayerWithStates(new HashMap<>());
        GunnerAbilityModule.setChanceSourceForTesting(p -> true);
        assertFalse(GunnerAbilityModule.ammoSaverShouldSave(player));
        assertFalse(GunnerAbilityModule.ammoSaverShouldSave(null));
    }

    // ---- config gating (fail-closed) ----

    @Test
    void masterOffDisablesAllRoutes() {
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> false,  // framework, integration, master off
                () -> true, () -> true, () -> true, () -> true);
        assertFalse(GunnerAbilityModule.marksmanshipEnabled());
        assertFalse(GunnerAbilityModule.ammoSaverEnabled());
        assertFalse(GunnerAbilityModule.defenseEnabled());
        assertFalse(GunnerAbilityModule.experienceEnabled());
    }

    @Test
    void singleRouteSwitchOnlyAffectsItsRoute() {
        // All gates on, only the ammo route switch off -> only ammo disabled.
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true,
                () -> true, () -> false, () -> true, () -> true);
        assertTrue(GunnerAbilityModule.marksmanshipEnabled());
        assertFalse(GunnerAbilityModule.ammoSaverEnabled());
        assertTrue(GunnerAbilityModule.defenseEnabled());
        assertTrue(GunnerAbilityModule.experienceEnabled());
    }

    @Test
    void configFailureFailsClosedForEveryRoute() {
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true,
                () -> { throw new IllegalStateException("config broken"); },
                () -> { throw new LinkageError("mod missing"); },
                () -> true, () -> true);
        assertFalse(GunnerAbilityModule.marksmanshipEnabled());
        assertFalse(GunnerAbilityModule.ammoSaverEnabled());
        // master supplier itself failing must not flip anything open.
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> { throw new RuntimeException("boom"); },
                () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
        assertFalse(GunnerAbilityModule.marksmanshipEnabled());
        assertFalse(GunnerAbilityModule.defenseEnabled());
        assertFalse(GunnerAbilityModule.experienceEnabled());
    }
}
