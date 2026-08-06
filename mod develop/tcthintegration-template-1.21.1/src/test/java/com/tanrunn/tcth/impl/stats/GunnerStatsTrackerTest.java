package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Unit tests for {@link GunnerStatsTracker} (phase 5A.1).
 *
 * <p>Covers: switch gating (stats / integration / framework), automated
 * rejection, duplicate rejection, TTL expiry, stop cleanup, and the commit
 * order — the event id is only recorded AFTER a successful stats write, and a
 * failing write leaves the id free for retry without breaking the event bus.
 */
class GunnerStatsTrackerTest {

    private static final ResourceLocation WEAPON_ID = ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol");
    private static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private GunnerStatsData mockData;
    private ServerPlayer player;
    private UUID playerId;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        GunnerStatsTracker.resetForTesting();
        GunnerStatsTracker.setEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setFrameworkEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setIntegrationEnabledSupplierForTesting(() -> true);
        mockData = new GunnerStatsData();
        GunnerStatsTracker.setDataProviderForTesting(lvl -> mockData);
        playerId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        level = mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
        GunnerStatsTracker.resetForTesting();
    }

    @Test
    void statsDisabledDoesNotRecord() {
        GunnerStatsTracker.setEnabledSupplierForTesting(() -> false);
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        assertNull(mockData.get(playerId));
    }

    @Test
    void integrationDisabledDoesNotRecord() {
        GunnerStatsTracker.setIntegrationEnabledSupplierForTesting(() -> false);
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        assertNull(mockData.get(playerId));
    }

    @Test
    void frameworkDisabledDoesNotRecord() {
        GunnerStatsTracker.setFrameworkEnabledSupplierForTesting(() -> false);
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        assertNull(mockData.get(playerId));
    }

    @Test
    void automatedEventIsNotRecorded() {
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, true));
        assertNull(mockData.get(playerId));
    }

    @Test
    void manualEventIsRecorded() {
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        PlayerGunnerStats stats = mockData.get(playerId);
        assertNotNull(stats);
        assertEquals(1, stats.getTotalGunKills());
        assertEquals(1, GunnerStatsTracker.trackedEventIdCountForTesting(),
                "the event id must be committed after a successful write");
    }

    @Test
    void duplicateEventIdIsNotRecordedTwice() {
        GunKillEvent event = newEvent(GunTargetTier.COMMON, false);
        GunnerStatsTracker.onGunKill(event);
        GunnerStatsTracker.onGunKill(event);
        PlayerGunnerStats stats = mockData.get(playerId);
        assertNotNull(stats);
        assertEquals(1, stats.getTotalGunKills());
    }

    @Test
    void multipleDistinctEventsAreRecorded() {
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.ELITE, false));
        PlayerGunnerStats stats = mockData.get(playerId);
        assertNotNull(stats);
        assertEquals(2, stats.getTotalGunKills());
        assertEquals(1, stats.getCommonKills());
        assertEquals(1, stats.getEliteKills());
    }

    @Test
    void failedStatsWriteDoesNotCommitEventId() {
        // The stats write throws: the event id must stay free (safe retry) and
        // the exception must NOT propagate to the event bus.
        GunnerStatsTracker.setDataProviderForTesting(lvl -> {
            throw new RuntimeException("disk full");
        });
        GunKillEvent event = newEvent(GunTargetTier.COMMON, false);
        GunnerStatsTracker.onGunKill(event); // must not throw
        assertEquals(0, GunnerStatsTracker.trackedEventIdCountForTesting(),
                "a failed write must not commit the event id");
        // Retrying with a healthy store succeeds.
        GunnerStatsTracker.setDataProviderForTesting(lvl -> mockData);
        GunnerStatsTracker.onGunKill(event);
        assertEquals(1, GunnerStatsTracker.trackedEventIdCountForTesting());
        assertNotNull(mockData.get(playerId));
    }

    @Test
    void linkageErrorIsIsolated() {
        GunnerStatsTracker.setDataProviderForTesting(lvl -> {
            throw new LinkageError("bad bytecode");
        });
        GunKillEvent event = newEvent(GunTargetTier.COMMON, false);
        GunnerStatsTracker.onGunKill(event); // must not throw
        assertEquals(0, GunnerStatsTracker.trackedEventIdCountForTesting());
    }

    @Test
    void ttlExpiryAllowsRepost() {
        GunKillEvent event = newEvent(GunTargetTier.COMMON, false);
        GunnerStatsTracker.onGunKill(event);
        assertEquals(1, mockData.get(playerId).getTotalGunKills());
        for (int i = 0; i <= GunnerStatsTracker.EVENT_ID_EXPIRY_TICKS_FOR_TESTING; i++) {
            GunnerStatsTracker.onServerTick(null);
        }
        GunnerStatsTracker.onGunKill(event);
        assertEquals(2, mockData.get(playerId).getTotalGunKills(),
                "after TTL expiry the same event id may be counted again");
    }

    @Test
    void stopCleanupClearsCache() {
        GunnerStatsTracker.onGunKill(newEvent(GunTargetTier.COMMON, false));
        assertEquals(1, GunnerStatsTracker.trackedEventIdCountForTesting());
        GunnerStatsTracker.onServerStopping(null);
        assertEquals(0, GunnerStatsTracker.trackedEventIdCountForTesting());
    }

    private GunKillEvent newEvent(GunTargetTier tier, boolean automated) {
        return new GunKillEvent(UUID.randomUUID(), player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                TARGET_ID, UUID.randomUUID(), tier, 10.0f, automated, level, BlockPos.ZERO);
    }
}
