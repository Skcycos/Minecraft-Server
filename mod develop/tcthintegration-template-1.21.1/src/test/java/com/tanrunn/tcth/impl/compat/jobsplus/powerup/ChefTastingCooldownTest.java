package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * Phase 3D: per-player tasting cooldown (bounded in-memory map, 400 ticks,
 * success-driven commit, logout/stop cleanup, shared by all tasting nodes).
 */
class ChefTastingCooldownTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AtomicLong now = new AtomicLong(0);
    private ServerPlayer player;

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
        Mockito.when(player.getUUID()).thenReturn(PLAYER);
    }

    @AfterEach
    void tearDown() {
        ChefTastingCooldown.resetForTesting();
    }

    @Test
    void notOnCooldownInitially() {
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
    }

    @Test
    void commitThenInsideCooldown() {
        now.set(1000);
        ChefTastingCooldown.instance().commit(PLAYER, player);
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player), "within 400 ticks must be on cooldown");
        now.set(1399);
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player), "399 ticks later must still be on cooldown");
    }

    @Test
    void cooldownExpiresAfterWindow() {
        now.set(1000);
        ChefTastingCooldown.instance().commit(PLAYER, player);
        now.set(1400);
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player), "400 ticks later must be off cooldown");
        now.set(2000);
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
    }

    @Test
    void canRetriggerAfterCooldown() {
        now.set(0);
        ChefTastingCooldown.instance().commit(PLAYER, player);
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
        now.set(500);
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
        ChefTastingCooldown.instance().commit(PLAYER, player);
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player), "second commit re-arms the cooldown");
    }

    @Test
    void sharedByAllTastingNodes() {
        // All tasting nodes use the same cooldown store — commit once, every
        // node's check is blocked.
        ChefTastingCooldown.instance().commit(PLAYER, player);
        assertTrue(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
        // A different player is not blocked.
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(other, player));
    }

    @Test
    void noCommitMeansNoCooldown() {
        // The reward only commits after a successful grant; nothing in the
        // cooldown store must be created by merely checking.
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
        assertEquals(0, ChefTastingCooldown.snapshotForTesting().size());
    }

    @Test
    void logoutClearsPlayerEntry() {
        ChefTastingCooldown.instance().commit(PLAYER, player);
        assertEquals(1, ChefTastingCooldown.snapshotForTesting().size());
        ChefTastingCooldown.instance().clearPlayer(PLAYER);
        assertEquals(0, ChefTastingCooldown.snapshotForTesting().size());
        assertFalse(ChefTastingCooldown.instance().isOnCooldown(PLAYER, player));
    }

    @Test
    void serverStopClearsAllEntries() {
        ChefTastingCooldown.instance().commit(PLAYER, player);
        ChefTastingCooldown.instance().commit(UUID.fromString("00000000-0000-0000-0000-000000000003"), player);
        assertEquals(2, ChefTastingCooldown.snapshotForTesting().size());
        ChefTastingCooldown.instance().clearAll();
        assertEquals(0, ChefTastingCooldown.snapshotForTesting().size());
    }

    @Test
    void registerLifecycleIsIdempotent() {
        // Each registerLifecycle attaches exactly two listeners (logout + stop);
        // a second call must be a no-op instead of duplicating them.
        net.neoforged.bus.api.IEventBus bus = Mockito.mock(net.neoforged.bus.api.IEventBus.class);
        ChefTastingCooldown.instance().registerLifecycle(bus);
        ChefTastingCooldown.instance().registerLifecycle(bus);
        ChefTastingCooldown.instance().registerLifecycle(bus);
        Mockito.verify(bus, Mockito.times(2)).addListener(Mockito.any());
    }
}
