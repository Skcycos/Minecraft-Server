package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
import com.daqem.jobsplus.player.job.powerup.PowerupType;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for the farmer ability module (phase 4B): tier query semantics,
 * harvest-route effects and cooldown gating, config gating and isolation.
 */
class FarmerAbilityModuleTest {

    private ServerLevel level;
    private ServerPlayer player;
    private final java.util.List<MobEffectInstance> capturedEffects = new java.util.ArrayList<>();

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        FarmerAbilityModule.resetForTesting();
        FarmerAbilityModule.setConfigSuppliersForTesting(
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
        FarmerAbilityModule.resetForTesting();
    }

    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:farmer"), null,
                new ItemStack(Items.STONE_HOE), 5, 5, PowerupType.BASIC);
    }

    private ServerPlayer farmerPlayerWithStates(Map<String, PowerupState> states) {
        ServerPlayer sp = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        Mockito.when(sp.getUUID()).thenReturn(java.util.UUID.randomUUID());
        net.minecraft.world.entity.player.Abilities abilities = Mockito.mock(net.minecraft.world.entity.player.Abilities.class);
        Mockito.when(sp.getAbilities()).thenReturn(abilities);
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) sp;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        Mockito.when(job.getPowerupManager()).thenReturn(manager);
        Mockito.when(jobsPlayer.jobsplus$getJob(com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance.of(FarmerPowerupAccess.FARMER_JOB)))
                .thenReturn(job);
        FarmerAbilityModule.setPowerupResolverForTesting(FarmerAbilityModuleTest::instanceOf);
        Mockito.when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            return Optional.ofNullable(states.get(instance.getLocation().getPath()))
                    .map(state -> {
                        Powerup p = Mockito.mock(Powerup.class);
                        Mockito.when(p.getState()).thenReturn(state);
                        return p;
                    });
        });
        Mockito.when(sp.addEffect(Mockito.any(MobEffectInstance.class))).thenAnswer(invocation -> {
            capturedEffects.add(invocation.getArgument(0));
            return true;
        });
        return sp;
    }

    private java.util.List<MobEffectInstance> captureEffects() {
        return capturedEffects;
    }

    private CropHarvestedEvent harvestEvent(ServerPlayer p, boolean automated) {
        return new CropHarvestedEvent(java.util.UUID.randomUUID(),
                automated ? null : p, ResourceLocation.parse("minecraft:wheat"),
                net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, level,
                com.tanrunn.tcth.api.farming.HarvestMethod.BREAK, true, automated);
    }

    // ---- tier query ----

    @Test
    void nonJobsServerPlayerReturnsNone() {
        assertEquals(FarmerPowerupTier.NONE,
                FarmerAbilityModule.instance().highestActiveTier(Mockito.mock(ServerPlayer.class),
                        FarmerAbilityRoute.TILLING));
    }

    @Test
    void highestActiveTierReflectsStates() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_basic", PowerupState.ACTIVE);
        states.put("farmer/harvest_adept", PowerupState.NOT_OWNED);
        states.put("farmer/harvest_expert", PowerupState.LOCKED);
        assertEquals(FarmerPowerupTier.I,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.HARVEST));

        states.put("farmer/harvest_adept", PowerupState.ACTIVE);
        assertEquals(FarmerPowerupTier.II,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.HARVEST));

        states.put("farmer/harvest_expert", PowerupState.ACTIVE);
        assertEquals(FarmerPowerupTier.III,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.HARVEST));
    }

    @Test
    void highestActiveIsRouteIndependent() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/tilling_basic", PowerupState.ACTIVE);
        states.put("farmer/livestock_expert", PowerupState.ACTIVE);
        assertEquals(FarmerPowerupTier.I,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.TILLING));
        assertEquals(FarmerPowerupTier.III,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.LIVESTOCK));
    }

    @Test
    void brokenQueryReturnsNoneWithoutThrowing() {
        ServerPlayer p = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        Mockito.when(((JobsServerPlayer) p).jobsplus$getJob(
                        com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance.of(FarmerPowerupAccess.FARMER_JOB)))
                .thenThrow(new IllegalStateException("corrupt"));
        assertEquals(FarmerPowerupTier.NONE,
                FarmerAbilityModule.instance().highestActiveTier(p, FarmerAbilityRoute.STUDY));
    }

    // ---- harvest route effects ----

    @Test
    void harvestTickConstantsMatchSpec() {
        assertEquals(100, FarmerAbilityModule.harvestHasteTicks(FarmerPowerupTier.I));
        assertEquals(0, FarmerAbilityModule.harvestSpeedTicks(FarmerPowerupTier.I));
        assertEquals(160, FarmerAbilityModule.harvestHasteTicks(FarmerPowerupTier.II));
        assertEquals(160, FarmerAbilityModule.harvestSpeedTicks(FarmerPowerupTier.II));
        assertEquals(240, FarmerAbilityModule.harvestHasteTicks(FarmerPowerupTier.III));
        assertEquals(240, FarmerAbilityModule.harvestSpeedTicks(FarmerPowerupTier.III));
        assertEquals(0, FarmerAbilityModule.harvestHasteTicks(FarmerPowerupTier.NONE));
    }

    @Test
    void realHarvestGrantsHasteAndSpeedByTier() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_basic", PowerupState.ACTIVE);
        states.put("farmer/harvest_adept", PowerupState.ACTIVE);
        states.put("farmer/harvest_expert", PowerupState.NOT_OWNED);

        java.util.List<MobEffectInstance> effects = captureEffects();
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        // Haste I 160 ticks + Speed I 160 ticks, exactly once each.
        long haste = effects.stream().filter(e -> e.getEffect() == MobEffects.DIG_SPEED).count();
        long speed = effects.stream().filter(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED).count();
        assertEquals(1, haste);
        assertEquals(1, speed);
        assertTrue(effects.stream().anyMatch(e -> e.getEffect() == MobEffects.DIG_SPEED && e.getDuration() == 160));
        assertTrue(effects.stream().anyMatch(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED && e.getDuration() == 160));
    }

    @Test
    void automatedHarvestNeverGrants() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_expert", PowerupState.ACTIVE);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, true));
        assertTrue(captureEffects().isEmpty());
    }

    @Test
    void noTierGrantsNothingAndCommitsNothing() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertTrue(captureEffects().isEmpty());
        assertFalse(FarmerHarvestCooldown.instance().isOnCooldown(p.getUUID(), p));
    }

    @Test
    void harvestCooldownBlocksSecondGrantWithinWindow() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_basic", PowerupState.ACTIVE);
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1000L);
        FarmerHarvestCooldown.setCooldownTicksForTesting(() -> 200);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        int afterFirst = captureEffects().size();
        // Second harvest at tick 1100: still inside the 200-tick window.
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1100L);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertEquals(afterFirst, captureEffects().size(), "second grant must be blocked by cooldown");
        // At tick 1200 exactly: window expired (>= 200), grant again.
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1200L);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertEquals(afterFirst + 1, captureEffects().size(), "grant must resume after cooldown expiry");
        FarmerHarvestCooldown.resetForTesting();
    }

    // ---- config gating ----

    @Test
    void masterSwitchOffBlocksHarvest() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_expert", PowerupState.ACTIVE);
        FarmerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> false, () -> true, () -> true, () -> true, () -> true);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertTrue(captureEffects().isEmpty());
    }

    @Test
    void routeSwitchOffBlocksHarvest() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_expert", PowerupState.ACTIVE);
        FarmerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true, () -> true, () -> false, () -> true, () -> true);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertTrue(captureEffects().isEmpty());
    }

    @Test
    void brokenConfigGateFailsClosed() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/harvest_expert", PowerupState.ACTIVE);
        FarmerAbilityModule.setConfigSuppliersForTesting(
                () -> { throw new IllegalStateException("broken"); },
                () -> true, () -> true, () -> true, () -> true, () -> true, () -> true);
        FarmerAbilityModule.onCropHarvested(harvestEvent(p, false));
        assertTrue(captureEffects().isEmpty(), "broken config must never grant effects");
    }

    // ---- study multipliers ----

    @Test
    void studyMultipliersMatchSpec() {
        assertEquals(1.15f, FarmerAbilityModule.experienceMultiplier(FarmerPowerupTier.I));
        assertEquals(1.35f, FarmerAbilityModule.experienceMultiplier(FarmerPowerupTier.II));
        assertEquals(1.60f, FarmerAbilityModule.experienceMultiplier(FarmerPowerupTier.III));
        assertEquals(1.0f, FarmerAbilityModule.experienceMultiplier(FarmerPowerupTier.NONE));
    }

    // ---- tilling route (Java-driven durability skip) ----

    @Test
    void tillingChancePctMatchesSpec() {
        assertEquals(10, FarmerAbilityModule.tillingChancePct(FarmerPowerupTier.I));
        assertEquals(20, FarmerAbilityModule.tillingChancePct(FarmerPowerupTier.II));
        assertEquals(35, FarmerAbilityModule.tillingChancePct(FarmerPowerupTier.III));
        assertEquals(0, FarmerAbilityModule.tillingChancePct(FarmerPowerupTier.NONE));
    }

    @Test
    void tillingTagIsMinecraftHoes() {
        assertEquals("minecraft", FarmerAbilityModule.HOES_TAG.location().getNamespace());
        assertEquals("hoes", FarmerAbilityModule.HOES_TAG.location().getPath());
    }

    @Test
    void hoeSkipsOnlyOnTaggedToolsAndActiveTier() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/tilling_basic", PowerupState.ACTIVE);
        FarmerAbilityModule.setRandomPctForTesting(() -> 0); // always hit

        // Test env has no tag data; stub the tag check via mock.
        ItemStack hoe = Mockito.mock(ItemStack.class);
        Mockito.when(hoe.isEmpty()).thenReturn(false);
        Mockito.when(hoe.is(FarmerAbilityModule.HOES_TAG)).thenReturn(true);
        assertTrue(FarmerAbilityModule.shouldSkipHoeDurability(p, hoe),
                "hoe (in #minecraft:hoes) with active tier must skip");

        ItemStack pickaxe = Mockito.mock(ItemStack.class);
        Mockito.when(pickaxe.isEmpty()).thenReturn(false);
        Mockito.when(pickaxe.is(FarmerAbilityModule.HOES_TAG)).thenReturn(false);
        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(p, pickaxe),
                "non-hoe tools must never skip");

        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(p, ItemStack.EMPTY));
        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(null, hoe));
        FarmerAbilityModule.resetForTesting();
    }

    @Test
    void hoeSkipsOnlyWithinChanceWindow() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        states.put("farmer/tilling_expert", PowerupState.ACTIVE);
        ItemStack hoe = Mockito.mock(ItemStack.class);
        Mockito.when(hoe.isEmpty()).thenReturn(false);
        Mockito.when(hoe.is(FarmerAbilityModule.HOES_TAG)).thenReturn(true);
        // 35%: 0..34 skip, 35..99 don't.
        FarmerAbilityModule.setRandomPctForTesting(() -> 34);
        assertTrue(FarmerAbilityModule.shouldSkipHoeDurability(p, hoe));
        FarmerAbilityModule.setRandomPctForTesting(() -> 35);
        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(p, hoe));
        FarmerAbilityModule.resetForTesting();
    }

    @Test
    void hoeNeverSkipsWhenNoTierOrGatesClosed() {
        Map<String, PowerupState> states = new HashMap<>();
        ServerPlayer p = farmerPlayerWithStates(states);
        ItemStack hoe = Mockito.mock(ItemStack.class);
        Mockito.when(hoe.isEmpty()).thenReturn(false);
        Mockito.when(hoe.is(FarmerAbilityModule.HOES_TAG)).thenReturn(true);
        FarmerAbilityModule.setRandomPctForTesting(() -> 0);
        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(p, hoe),
                "no active tilling node must never skip");
        // Master gate closed.
        states.put("farmer/tilling_basic", PowerupState.ACTIVE);
        FarmerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> false, () -> true, () -> true, () -> true, () -> true);
        assertFalse(FarmerAbilityModule.shouldSkipHoeDurability(p, hoe),
                "closed master gate must never skip");
        FarmerAbilityModule.resetForTesting();
    }
}
