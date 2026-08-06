package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.result.ActionResult;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link GunnerRewardModule} (phase 5A).
 *
 * <p>Covers: reward switch, framework switch, idempotency, rate limit, BOSS
 * cooldown, stop cleanup.
 */
class GunnerRewardModuleTest {

    private static final ResourceLocation WEAPON_ID = ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol");
    private static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private GunnerRewardModule.GunnerActionSender successSender;
    private GunnerRewardModule.GunnerActionSender failSender;
    private ServerPlayer player;
    private UUID playerId;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        GunnerRewardModule.resetForTesting();
        GunnerRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        GunnerRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        GunnerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 10);
        GunnerRewardModule.setBossCooldownSupplierForTesting(() -> 1200);
        playerId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        level = mock(ServerLevel.class);
        successSender = (p, event) -> mock(ActionResult.class);
        failSender = (p, event) -> null;
    }

    @AfterEach
    void tearDown() {
        GunnerRewardModule.resetForTesting();
    }

    @Test
    void rewardsDisabledDoesNotSend() {
        GunnerRewardModule.setRewardsEnabledSupplierForTesting(() -> false);
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(0, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void frameworkDisabledDoesNotSend() {
        GunnerRewardModule.setFrameworkEnabledSupplierForTesting(() -> false);
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(0, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void successfulSendRecordsEventId() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunKillEvent event = newEvent(GunTargetTier.COMMON);
        GunnerRewardModule.onGunKill(event);
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
        assertTrue(GunnerRewardModule.isEventIdTracked(event.getEventId()));
    }

    @Test
    void failedSendDoesNotRecordEventId() {
        GunnerRewardModule.setActionSenderForTesting(failSender);
        GunKillEvent event = newEvent(GunTargetTier.COMMON);
        GunnerRewardModule.onGunKill(event);
        assertEquals(0, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void duplicateEventIdIsNotRecordedTwice() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunKillEvent event = newEvent(GunTargetTier.COMMON);
        GunnerRewardModule.onGunKill(event);
        GunnerRewardModule.onGunKill(event);
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void automatedEventIsNotRecorded() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON, true));
        assertEquals(0, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void rateLimitStopsFurtherActionsThisTick() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 2);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(2, GunnerRewardModule.trackedEventCountForTesting(),
                "per-tick rate limit must cap successful actions");
    }

    @Test
    void rateLimitResetsNextTick() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 1);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
        GunnerRewardModule.onServerTick(null);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(2, GunnerRewardModule.trackedEventCountForTesting(),
                "the rate limit window must reset each tick");
    }

    @Test
    void bossCooldownPreventsDuplicateBossKills() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.BOSS));
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
        // Second BOSS kill within cooldown should be dropped.
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.BOSS));
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void bossCooldownDoesNotAffectCommonKills() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(2, GunnerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void stopCleanupClearsState() {
        GunnerRewardModule.setActionSenderForTesting(successSender);
        GunnerRewardModule.onGunKill(newEvent(GunTargetTier.COMMON));
        assertEquals(1, GunnerRewardModule.trackedEventCountForTesting());
        GunnerRewardModule.onServerStopping(null);
        assertEquals(0, GunnerRewardModule.trackedEventCountForTesting());
    }

    private GunKillEvent newEvent(GunTargetTier tier) {
        return newEvent(tier, false);
    }

    private GunKillEvent newEvent(GunTargetTier tier, boolean automated) {
        return new GunKillEvent(UUID.randomUUID(), player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                TARGET_ID, UUID.randomUUID(), tier, 10.0f, automated, level, BlockPos.ZERO);
    }
}
