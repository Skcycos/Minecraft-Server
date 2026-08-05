package com.tanrunn.tcth.impl.compat.jobsplus.arc.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.player.ArcPlayer;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Phase 3D: the tcth:tasting_effects reward — one complete effect package per
 * tier from a single action, level-I effects only, success-driven cooldown
 * commit.
 */
class TastingEffectsRewardTest {

    private final AtomicLong now = new AtomicLong(0);
    private ServerPlayer player;
    private ActionData data;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ChefTastingCooldown.resetForTesting();
        ChefTastingCooldown.setTickSourceForTesting(now::get);
        ChefTastingCooldown.setCooldownTicksForTesting(() -> 400);

        MinecraftServer server = Mockito.mock(MinecraftServer.class);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.getServer()).thenReturn(server);
        Mockito.when(server.getTickCount()).thenAnswer(invocation -> now.get());

        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.serverLevel()).thenReturn(level);
        Mockito.when(player.getUUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

        ArcPlayer arcPlayer = Mockito.mock(ArcPlayer.class);
        Mockito.when(arcPlayer.arc$getPlayer()).thenReturn(player);
        data = Mockito.mock(ActionData.class);
        Mockito.when(data.getPlayer()).thenReturn(arcPlayer);
    }

    @AfterEach
    void tearDown() {
        ChefTastingCooldown.resetForTesting();
    }

    private TastingEffectsReward reward(int tier) {
        return new TastingEffectsReward(100, 1, tier);
    }

    private List<MobEffectInstance> grantedEffects(int tier) {
        Mockito.when(player.addEffect(Mockito.any())).thenReturn(true);
        reward(tier).apply(data);
        ArgumentCaptor<MobEffectInstance> captor = ArgumentCaptor.forClass(MobEffectInstance.class);
        Mockito.verify(player, Mockito.atMost(3)).addEffect(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void tier1GrantsOnlyRegenerationI5s() {
        List<MobEffectInstance> effects = grantedEffects(1);
        assertEquals(1, effects.size());
        MobEffectInstance regeneration = effects.get(0);
        assertEquals(MobEffects.REGENERATION, regeneration.getEffect());
        assertEquals(100, regeneration.getDuration(), "Regeneration I must last 5 s = 100 ticks");
        assertEquals(0, regeneration.getAmplifier(), "must be level I");
        Mockito.verify(player, Mockito.never()).addEffect(Mockito.argThat(e ->
                e.getEffect() == MobEffects.DAMAGE_RESISTANCE || e.getEffect() == MobEffects.MOVEMENT_SPEED));
    }

    @Test
    void tier2GrantsFullTwoEffectPackageOnly() {
        List<MobEffectInstance> effects = grantedEffects(2);
        assertEquals(2, effects.size(), "tier 2 must grant exactly the two-effect package");
        boolean hasRegen = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.REGENERATION && e.getDuration() == 100 && e.getAmplifier() == 0);
        boolean hasResistance = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.DAMAGE_RESISTANCE && e.getDuration() == 160 && e.getAmplifier() == 0);
        assertTrue(hasRegen, "tier 2 must include Regeneration I 5 s");
        assertTrue(hasResistance, "tier 2 must include Resistance I 8 s");
        Mockito.verify(player, Mockito.never()).addEffect(Mockito.argThat(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED));
    }

    @Test
    void tier3GrantsFullThreeEffectPackageOnly() {
        List<MobEffectInstance> effects = grantedEffects(3);
        assertEquals(3, effects.size(), "tier 3 must grant exactly the three-effect package");
        boolean hasRegen = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.REGENERATION && e.getDuration() == 100 && e.getAmplifier() == 0);
        boolean hasResistance = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.DAMAGE_RESISTANCE && e.getDuration() == 160 && e.getAmplifier() == 0);
        boolean hasSpeed = effects.stream().anyMatch(e -> e.getEffect() == MobEffects.MOVEMENT_SPEED && e.getDuration() == 300 && e.getAmplifier() == 0);
        assertTrue(hasRegen);
        assertTrue(hasResistance);
        assertTrue(hasSpeed);
    }

    @Test
    void cooldownCommitsOnlyAfterSuccessfulGrant() {
        // All addEffect calls fail -> no cooldown committed.
        Mockito.when(player.addEffect(Mockito.any())).thenReturn(false);
        reward(3).apply(data);
        assertTrue(ChefTastingCooldown.snapshotForTesting().isEmpty(),
                "failed grants must NOT commit the cooldown");

        // At least one success -> cooldown committed.
        Mockito.when(player.addEffect(Mockito.any())).thenReturn(true);
        reward(3).apply(data);
        assertEquals(1, ChefTastingCooldown.snapshotForTesting().size());
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(player.getUUID(), player));
    }

    @Test
    void noEffectsForNonServerPlayer() {
        ArcPlayer arcPlayer = Mockito.mock(ArcPlayer.class);
        Mockito.when(arcPlayer.arc$getPlayer()).thenReturn(Mockito.mock(net.minecraft.world.entity.player.Player.class));
        ActionData nonServerData = Mockito.mock(ActionData.class);
        Mockito.when(nonServerData.getPlayer()).thenReturn(arcPlayer);
        reward(3).apply(nonServerData);
        Mockito.verify(player, Mockito.never()).addEffect(Mockito.any());
        assertTrue(ChefTastingCooldown.snapshotForTesting().isEmpty());
    }

    @Test
    void invalidTierIsRejectedAtConstruction() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new TastingEffectsReward(100, 1, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new TastingEffectsReward(100, 1, 4));
    }
}
