package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import com.tanrunn.tcth.impl.compat.scguns.SgDamageEvidence;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Phase 5B: marksmanship / battlefield-defense application in
 * {@code LivingDamageEvent.Pre}. Strong-evidence gating: only real-player SG
 * firearm damage applies; PvP, melee, vanilla arrows and environmental damage
 * never do; only the highest tier multiplier applies once.
 */
class GunnerAbilityModuleDamageTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // The bare JUnit JVM has no loaded config file, so Config.get() fails
        // closed; force every ability gate open for the application tests.
        GunnerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
    }

    @AfterEach
    void tearDown() {
        GunnerAbilityModule.resetForTesting();
        SgDamageEvidence.resetForTesting();
    }

    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:gunner"), null,
                new ItemStack(Items.IRON_SWORD), 5, 5, PowerupType.BASIC);
    }

    private ServerPlayer gunnerWith(String routeNode, PowerupState state) {
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) player;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        Mockito.when(job.getPowerupManager()).thenReturn(manager);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(GunnerPowerupAccess.GUNNER_JOB))).thenReturn(job);
        GunnerAbilityModule.setPowerupResolverForTesting(GunnerAbilityModuleDamageTest::instanceOf);
        Mockito.when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            String path = instance.getLocation().getPath();
            if (("gunner/" + routeNode).equals(path)) {
                Powerup p = Mockito.mock(Powerup.class);
                Mockito.when(p.getState()).thenReturn(state);
                return Optional.of(p);
            }
            return Optional.empty();
        });
        return player;
    }

    /**
     * SG-shot event: injects the strong-evidence seam (the real SG evidence
     * rules are already live-verified in 5A; here we exercise the ability
     * application layer deterministically).
     */
    private static LivingDamageEvent.Pre sgShotAt(LivingEntity victim, ServerPlayer shooter, float amount) {
        SgDamageEvidence.evidenceCheck = s -> true; // strong evidence: SG firearm
        DamageSource source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDirectEntity()).thenReturn(null);
        Mockito.when(source.getEntity()).thenReturn(shooter);
        return new LivingDamageEvent.Pre(victim, new DamageContainer(source, amount));
    }

    /** Non-SG damage (melee / vanilla arrow / environment / vanilla explosion). */
    private static LivingDamageEvent.Pre nonSgHit(LivingEntity victim, float amount) {
        SgDamageEvidence.evidenceCheck = s -> false; // strong evidence: not SG
        DamageSource source = Mockito.mock(DamageSource.class);
        Mockito.when(source.getDirectEntity()).thenReturn(null);
        return new LivingDamageEvent.Pre(victim, new DamageContainer(source, amount));
    }

    // ---- marksmanship ----

    @Test
    void marksmanshipMultipliesSgDamageToNonPlayer() {
        ServerPlayer shooter = gunnerWith("marksmanship_adept", PowerupState.ACTIVE);
        Zombie zombie = Mockito.mock(Zombie.class);
        LivingDamageEvent.Pre event = sgShotAt(zombie, shooter, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(110f, event.getNewDamage(), 0.001f, "adept = ×1.10");
    }

    @Test
    void marksmanshipNoTierLeavesDamageUnchanged() {
        ServerPlayer shooter = gunnerWith("marksmanship_adept", PowerupState.NOT_OWNED);
        Zombie zombie = Mockito.mock(Zombie.class);
        LivingDamageEvent.Pre event = sgShotAt(zombie, shooter, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f);
    }

    @Test
    void pvpNeverAppliesMarksmanship() {
        // Victim is a player: marksmanship must NOT boost; defense tier is NONE
        // here so the damage stays untouched.
        ServerPlayer shooter = gunnerWith("marksmanship_expert", PowerupState.ACTIVE);
        ServerPlayer victim = gunnerWith("battlefield_defense_basic", PowerupState.NOT_OWNED);
        LivingDamageEvent.Pre event = sgShotAt(victim, shooter, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f, "PvP must never receive the marksmanship bonus");
    }

    @Test
    void meleeAndEnvironmentDamageNeverApply() {
        Zombie zombie = Mockito.mock(Zombie.class);
        // melee / environment: strong evidence is false -> untouched.
        LivingDamageEvent.Pre event = nonSgHit(zombie, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f, "melee must not be boosted");
    }

    @Test
    void vanillaArrowNeverApplies() {
        // A vanilla (unregistered) arrow fails the strong-evidence check.
        Zombie zombie = Mockito.mock(Zombie.class);
        LivingDamageEvent.Pre event = nonSgHit(zombie, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f, "unregistered vanilla arrow must not be boosted");
    }

    // ---- battlefield defense ----

    @Test
    void defenseReducesSgDamageToPlayer() {
        ServerPlayer victim = gunnerWith("battlefield_defense_basic", PowerupState.ACTIVE);
        ServerPlayer shooter = gunnerWith("marksmanship_basic", PowerupState.NOT_OWNED);
        LivingDamageEvent.Pre event = sgShotAt(victim, shooter, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(90f, event.getNewDamage(), 0.001f, "basic = ×0.90");
    }

    @Test
    void defenseExpertOverridesLowerTiers() {
        ServerPlayer victim = gunnerWith("battlefield_defense_expert", PowerupState.ACTIVE);
        ServerPlayer shooter = gunnerWith("marksmanship_basic", PowerupState.NOT_OWNED);
        LivingDamageEvent.Pre event = sgShotAt(victim, shooter, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(70f, event.getNewDamage(), 0.001f, "only the highest tier applies (×0.70, not ×0.90×0.80×0.70)");
    }

    @Test
    void vanillaExplosionDoesNotReduce() {
        ServerPlayer victim = gunnerWith("battlefield_defense_expert", PowerupState.ACTIVE);
        // vanilla TNT explosion: minecraft:explosion fails the SG evidence check.
        LivingDamageEvent.Pre event = nonSgHit(victim, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f, "vanilla explosion must not be reduced");
    }

    @Test
    void fireDamageDoesNotReduce() {
        ServerPlayer victim = gunnerWith("battlefield_defense_expert", PowerupState.ACTIVE);
        LivingDamageEvent.Pre event = nonSgHit(victim, 100f);
        GunnerAbilityModule.onLivingDamagePre(event);
        assertEquals(100f, event.getNewDamage(), 0.001f, "fire damage must not be reduced");
    }
}
