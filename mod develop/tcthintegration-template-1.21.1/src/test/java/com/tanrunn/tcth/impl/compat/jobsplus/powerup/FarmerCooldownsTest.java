package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerPlayer;

/**
 * Unit tests for the farmer harvest (10 s) and livestock (20 s) cooldowns
 * (phase 4B): window boundaries, success-driven commit and lifecycle cleanup.
 */
class FarmerCooldownsTest {

    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        FarmerHarvestCooldown.resetForTesting();
        FarmerLivestockCooldown.resetForTesting();
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1000L);
        FarmerHarvestCooldown.setCooldownTicksForTesting(() -> 200);
        FarmerLivestockCooldown.setTickSourceForTesting(() -> 5000L);
        FarmerLivestockCooldown.setCooldownTicksForTesting(() -> 400);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        FarmerHarvestCooldown.resetForTesting();
        FarmerLivestockCooldown.resetForTesting();
    }

    @Test
    void harvestWindowBoundaries() {
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1000L);
        FarmerHarvestCooldown.setCooldownTicksForTesting(() -> 200);
        UUID id = player.getUUID();

        assertFalse(FarmerHarvestCooldown.instance().isOnCooldown(id, player), "no entry yet");
        FarmerHarvestCooldown.instance().commit(id, player);
        assertTrue(FarmerHarvestCooldown.instance().isOnCooldown(id, player), "inside window");

        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1199L);
        assertTrue(FarmerHarvestCooldown.instance().isOnCooldown(id, player), "tick 199 still cooling");
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 1200L);
        assertFalse(FarmerHarvestCooldown.instance().isOnCooldown(id, player), "tick 200 exactly expired");
    }

    @Test
    void livestockWindowBoundaries() {
        FarmerLivestockCooldown.setTickSourceForTesting(() -> 5000L);
        FarmerLivestockCooldown.setCooldownTicksForTesting(() -> 400);
        UUID id = player.getUUID();

        assertFalse(FarmerLivestockCooldown.instance().isOnCooldown(id, player));
        FarmerLivestockCooldown.instance().commit(id, player);
        assertTrue(FarmerLivestockCooldown.instance().isOnCooldown(id, player));

        FarmerLivestockCooldown.setTickSourceForTesting(() -> 5399L);
        assertTrue(FarmerLivestockCooldown.instance().isOnCooldown(id, player));
        FarmerLivestockCooldown.setTickSourceForTesting(() -> 5400L);
        assertFalse(FarmerLivestockCooldown.instance().isOnCooldown(id, player));
    }

    @Test
    void harvestCooldownIsPerPlayer() {
        FarmerHarvestCooldown.setTickSourceForTesting(() -> 100L);
        FarmerHarvestCooldown.setCooldownTicksForTesting(() -> 200);
        ServerPlayer other = Mockito.mock(ServerPlayer.class);
        Mockito.when(other.getUUID()).thenReturn(UUID.randomUUID());
        FarmerHarvestCooldown.instance().commit(player.getUUID(), player);
        assertTrue(FarmerHarvestCooldown.instance().isOnCooldown(player.getUUID(), player));
        assertFalse(FarmerHarvestCooldown.instance().isOnCooldown(other.getUUID(), other),
                "other player must not share the cooldown");
    }

    @Test
    void clearPlayerDropsEntry() {
        FarmerHarvestCooldown.instance().commit(player.getUUID(), player);
        assertTrue(FarmerHarvestCooldown.instance().isOnCooldown(player.getUUID(), player));
        FarmerHarvestCooldown.instance().clearPlayer(player.getUUID());
        assertFalse(FarmerHarvestCooldown.instance().isOnCooldown(player.getUUID(), player));
    }

    @Test
    void clearAllDropsEveryEntry() {
        FarmerLivestockCooldown.instance().commit(player.getUUID(), player);
        FarmerLivestockCooldown.instance().clearAll();
        assertEquals(0, FarmerLivestockCooldown.snapshotForTesting().size());
    }
}
