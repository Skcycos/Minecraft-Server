package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Phase 7E: BrewerAbilityModule semantics — tier query via Jobs+ public API
 * (fail-closed), brewing-route effects on BeveragePreparedEvent (including
 * automated / UNKNOWN / T3 negative cases and higher-tier overwrite),
 * resistance-route damage reduction (magical/indirect/wither positives and
 * fire/fall/melee/projectile negatives), and config gating (all fail closed).
 */
class BrewerAbilityModuleTest {

    private ServerLevel level;
    private ServerPlayer player;
    private final java.util.List<MobEffectInstance> capturedEffects = new java.util.ArrayList<>();

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        BrewerAbilityModule.resetForTesting();
        BrewerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        capturedEffects.clear();
        Mockito.when(player.addEffect(Mockito.any(MobEffectInstance.class))).thenAnswer(invocation -> {
            capturedEffects.add(invocation.getArgument(0));
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        BrewerAbilityModule.resetForTesting();
    }

    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:brewer"), null,
                new ItemStack(Items.POTION), 5, 5, PowerupType.BASIC);
    }

    private ServerPlayer brewerPlayerWithStates(Map<String, PowerupState> states) {
        ServerPlayer sp = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) sp;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        Mockito.when(job.getPowerupManager()).thenReturn(manager);
        Mockito.when(jobsPlayer.jobsplus$getJob(JobInstance.of(BrewerPowerupAccess.BREWER_JOB))).thenReturn(job);
        BrewerAbilityModule.setPowerupResolverForTesting(BrewerAbilityModuleTest::instanceOf);
        Mockito.when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            return Optional.ofNullable(states.get(instance.getLocation().getPath()))
                    .map(state -> {
                        Powerup p = Mockito.mock(Powerup.class);
                        Mockito.when(p.getState()).thenReturn(state);
                        return p;
                    });
        });
        // Route to the shared effect list so effect assertions work.
        Mockito.when(sp.addEffect(Mockito.any(MobEffectInstance.class))).thenAnswer(invocation -> {
            capturedEffects.add(invocation.getArgument(0));
            return true;
        });
        return sp;
    }

    /** Returns the shared effect capture list (populated by addEffect mocks). */
    private java.util.List<MobEffectInstance> captureEffects() {
        return capturedEffects;
    }

    // ---- tier query ----

    @Test
    void nonJobsServerPlayerReturnsNone() {
        assertEquals(BrewerPowerupTier.NONE,
                BrewerAbilityModule.instance().highestActiveTier(Mockito.mock(ServerPlayer.class),
                        BrewerAbilityRoute.BREWING));
    }

    @Test
    void highestActiveTierReflectsStates() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        states.put("brewer/brewing_adept", PowerupState.NOT_OWNED);
        states.put("brewer/brewing_expert", PowerupState.LOCKED);
        assertEquals(BrewerPowerupTier.I,
                BrewerAbilityModule.instance().highestActiveTier(p, BrewerAbilityRoute.BREWING));

        states.put("brewer/brewing_adept", PowerupState.ACTIVE);
        assertEquals(BrewerPowerupTier.II,
                BrewerAbilityModule.instance().highestActiveTier(p, BrewerAbilityRoute.BREWING));
    }

    @Test
    void brokenQueryReturnsNoneWithoutThrowing() {
        ServerPlayer p = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        Mockito.when(((JobsServerPlayer) p).jobsplus$getJob(JobInstance.of(BrewerPowerupAccess.BREWER_JOB)))
                .thenThrow(new IllegalStateException("corrupt"));
        assertEquals(BrewerPowerupTier.NONE,
                BrewerAbilityModule.instance().highestActiveTier(p, BrewerAbilityRoute.TASTING));
    }

    // ---- brewing route effects ----

    private BeveragePreparedEvent event(ServerPlayer p, BeverageTier tier, boolean automated) {
        return new BeveragePreparedEvent(java.util.UUID.randomUUID(),
                automated ? null : p, null, new ItemStack(Items.HONEY_BOTTLE),
                BeverageDevice.KEG, tier, automated, level, null);
    }

    @Test
    void gradedEventGrantsSpeedAndLuckByTier() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        states.put("brewer/brewing_adept", PowerupState.ACTIVE);
        states.put("brewer/brewing_expert", PowerupState.NOT_OWNED);

        java.util.List<MobEffectInstance> effects = captureEffects();
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.COMMON, false));

        assertFalse(effects.isEmpty(), "a graded beverage must grant effects");
        boolean hasSpeed = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED && e.getDuration() == 160);
        boolean hasLuck = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.LUCK && e.getDuration() == 160);
        assertTrue(hasSpeed, "tier II must grant Speed I 8 s");
        assertTrue(hasLuck, "tier II must grant Luck I 8 s");
    }

    @Test
    void higherTierOverwritesLower() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        states.put("brewer/brewing_adept", PowerupState.NOT_OWNED);
        states.put("brewer/brewing_expert", PowerupState.ACTIVE);

        java.util.List<MobEffectInstance> effects = captureEffects();
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.T2, false));

        assertFalse(effects.isEmpty());
        boolean hasSpeed = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED && e.getDuration() == 240);
        boolean hasLuck = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.LUCK && e.getDuration() == 240);
        assertTrue(hasSpeed, "tier III must grant Speed I 12 s, not lower-tier value");
        assertTrue(hasLuck, "tier III must grant Luck I 12 s");
    }

    @Test
    void automatedEventGrantsNothing() {
        BrewerAbilityModule.onBeveragePrepared(event(null, BeverageTier.COMMON, true));
        assertTrue(capturedEffects.isEmpty(), "automated events never grant brewing effects");
    }

    @Test
    void unknownTierGrantsNothing() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.UNKNOWN, false));
        assertTrue(capturedEffects.isEmpty());
    }

    @Test
    void t3GrantsNothing() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.T3, false));
        assertTrue(capturedEffects.isEmpty());
    }

    @Test
    void noTierGrantsNothing() {
        BrewerAbilityModule.onBeveragePrepared(event(player, BeverageTier.COMMON, false));
        assertTrue(capturedEffects.isEmpty(), "player without the brewing route gets no effect");
    }

    // ---- resistance route ----

    private DamageSource damageSource(ResourceKey<DamageType> key) {
        DamageSource source = Mockito.mock(DamageSource.class);
        Mockito.when(source.is(key)).thenReturn(true);
        return source;
    }

    private float reducedDamage(ServerPlayer victim, DamageSource source, float base) {
        LivingDamageEvent.Pre ev = Mockito.mock(LivingDamageEvent.Pre.class);
        Mockito.when(ev.getEntity()).thenReturn(victim);
        Mockito.when(ev.getSource()).thenReturn(source);
        Mockito.when(ev.getNewDamage()).thenReturn(base);
        AtomicReference<Float> applied = new AtomicReference<>(base);
        Mockito.doAnswer(inv -> {
            applied.set(inv.getArgument(0));
            return null;
        }).when(ev).setNewDamage(Mockito.anyFloat());
        BrewerAbilityModule.onLivingDamagePre(ev);
        return applied.get();
    }

    @Test
    void resistanceReducesMagicalDamage() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/resistance_basic", PowerupState.ACTIVE);
        states.put("brewer/resistance_adept", PowerupState.ACTIVE);
        states.put("brewer/resistance_expert", PowerupState.NOT_OWNED);

        assertEquals(16.0f, reducedDamage(p, damageSource(DamageTypes.MAGIC), 20.0f), 0.001f);
    }

    @Test
    void resistanceReducesWitherDamage() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/resistance_basic", PowerupState.ACTIVE);
        states.put("brewer/resistance_adept", PowerupState.NOT_OWNED);
        states.put("brewer/resistance_expert", PowerupState.NOT_OWNED);

        assertEquals(18.0f, reducedDamage(p, damageSource(DamageTypes.WITHER), 20.0f), 0.001f);
    }

    @Test
    void resistanceDoesNotAffectFireFallOrProjectile() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/resistance_basic", PowerupState.ACTIVE);
        states.put("brewer/resistance_adept", PowerupState.ACTIVE);
        states.put("brewer/resistance_expert", PowerupState.ACTIVE);

        assertEquals(20.0f, reducedDamage(p, damageSource(DamageTypes.IN_FIRE), 20.0f), 0.001f);
        assertEquals(20.0f, reducedDamage(p, damageSource(DamageTypes.FALL), 20.0f), 0.001f);
        assertEquals(20.0f, reducedDamage(p, damageSource(DamageTypes.MOB_PROJECTILE), 20.0f), 0.001f);
        assertEquals(20.0f, reducedDamage(p, damageSource(DamageTypes.PLAYER_ATTACK), 20.0f), 0.001f);
    }

    @Test
    void noResistanceTierLeavesDamageUnchanged() {
        assertEquals(20.0f, reducedDamage(player, damageSource(DamageTypes.MAGIC), 20.0f), 0.001f);
    }

    @Test
    void nonFiniteDamageFailsClosedAndIsNotMultiplied() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/resistance_basic", PowerupState.ACTIVE);
        states.put("brewer/resistance_adept", PowerupState.ACTIVE);
        states.put("brewer/resistance_expert", PowerupState.ACTIVE);

        // NaN / +inf / -inf must be left untouched (never multiplied).
        assertTrue(Float.isNaN(reducedDamage(p, damageSource(DamageTypes.MAGIC), Float.NaN)));
        assertEquals(Float.POSITIVE_INFINITY,
                reducedDamage(p, damageSource(DamageTypes.MAGIC), Float.POSITIVE_INFINITY), 0.0f);
        assertEquals(Float.NEGATIVE_INFINITY,
                reducedDamage(p, damageSource(DamageTypes.MAGIC), Float.NEGATIVE_INFINITY), 0.0f);
    }

    @Test
    void brewingRouteAppliesHighestTierExactlyOncePerEvent() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        states.put("brewer/brewing_adept", PowerupState.ACTIVE);
        states.put("brewer/brewing_expert", PowerupState.ACTIVE);

        capturedEffects.clear();
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.T2, false));
        // Exactly one Speed + one Luck (tier III package), never duplicated.
        long speeds = capturedEffects.stream().filter(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED).count();
        long lucks = capturedEffects.stream().filter(e -> e.getEffect() == MobEffects.LUCK).count();
        assertEquals(1, speeds, "exactly one Speed application per event");
        assertEquals(1, lucks, "exactly one Luck application per event");
        assertEquals(2, capturedEffects.size(), "tier III grants exactly the two-effect package");
    }

    // ---- config gating (all fail closed) ----

    @Test
    void masterOffDisablesAllRoutes() {
        BrewerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> false, () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.COMMON, false));
        assertTrue(capturedEffects.isEmpty(), "master switch off disables the brewing route");
    }

    @Test
    void singleRouteSwitchOnlyAffectsItsRoute() {
        BrewerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true, () -> false, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.COMMON, false));
        assertTrue(capturedEffects.isEmpty(), "brewing switch off disables the brewing route");
    }

    @Test
    void configFailureFailsClosedForEveryRoute() {
        BrewerAbilityModule.setConfigSuppliersForTesting(
                () -> { throw new IllegalStateException("boom"); }, () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = brewerPlayerWithStates(states);
        states.put("brewer/brewing_basic", PowerupState.ACTIVE);
        BrewerAbilityModule.onBeveragePrepared(event(p, BeverageTier.COMMON, false));
        assertTrue(capturedEffects.isEmpty(), "config failure must fail closed for brewing");

        // Resistance route also fails closed.
        assertEquals(20.0f, reducedDamage(p, damageSource(DamageTypes.MAGIC), 20.0f), 0.001f);
    }
}
