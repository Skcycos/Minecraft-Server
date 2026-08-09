package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link BrewingStatsTracker} event filtering and counting
 * (phase 7D).
 */
class BrewingStatsTrackerTest {

    private ServerLevel level;
    private ServerPlayer player;
    private BrewingStatsData data;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        BrewingStatsTracker.resetForTesting();
        BrewingStatsTracker.setFrameworkEnabledSupplierForTesting(() -> true);
        BrewingStatsTracker.setEnabledSupplierForTesting(() -> true);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        data = new BrewingStatsData();
        BrewingStatsTracker.setDataProviderForTesting(l -> data);
    }

    private BeveragePreparedEvent event(boolean automated, BeverageTier tier, ItemStack result) {
        return new BeveragePreparedEvent(UUID.randomUUID(), automated ? null : player, null, result,
                BeverageDevice.KEG, tier, automated, level, null);
    }

    @Test
    void gradedRealEventIsCounted() {
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        PlayerBrewingStats stats = data.get(player.getUUID());
        assertEquals(1, stats.getTotalBrewingEvents());
        assertEquals(1, stats.getTotalBeveragesPrepared());
        assertEquals(1, stats.getUniqueBeverageCount());
    }

    @Test
    void automatedEventsAreNotCounted() {
        BrewingStatsTracker.onBeveragePrepared(
                event(true, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void nullPlayerEventsAreNotCounted() {
        BrewingStatsTracker.onBeveragePrepared(
                event(true, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void unknownTierIsNotCounted() {
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.UNKNOWN, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void t3IsNotCounted() {
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.T3, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void duplicateEventIdIsNotCountedTwice() {
        BeveragePreparedEvent e = event(false, BeverageTier.T2, new ItemStack(Items.POTION));
        BrewingStatsTracker.onBeveragePrepared(e);
        BrewingStatsTracker.onBeveragePrepared(e);
        BrewingStatsTracker.onBeveragePrepared(e);
        assertEquals(1, data.get(player.getUUID()).getTotalBrewingEvents());
    }

    @Test
    void disabledModuleCountsNothing() {
        BrewingStatsTracker.setEnabledSupplierForTesting(() -> false);
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void disabledFrameworkCountsNothing() {
        BrewingStatsTracker.setFrameworkEnabledSupplierForTesting(() -> false);
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void configReadExceptionFailsClosedForStats() {
        BrewingStatsTracker.setEnabledSupplierForTesting(() -> {
            throw new IllegalStateException("config boom");
        });
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertTrue(data.getPlayers().isEmpty(),
                "a config read exception must fail closed (no stats recorded)");
    }

    @Test
    void expiredEventIdsAllowRetry() {
        BeveragePreparedEvent e = event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE));
        BrewingStatsTracker.onBeveragePrepared(e);
        assertEquals(1, data.get(player.getUUID()).getTotalBrewingEvents());

        // Advance past the 40-tick expiry; a genuinely new delivery is allowed.
        for (int i = 0; i <= BrewingStatsTracker.EVENT_ID_EXPIRY_TICKS_FOR_TESTING; i++) {
            BrewingStatsTracker.tickForTesting();
        }
        assertEquals(0, BrewingStatsTracker.trackedEventIdCountForTesting());

        BrewingStatsTracker.onBeveragePrepared(e);
        assertEquals(2, data.get(player.getUUID()).getTotalBrewingEvents(),
                "an expired id no longer blocks a genuine new event");
    }

    @Test
    void stopClearsEventIdCache() {
        BrewingStatsTracker.onBeveragePrepared(
                event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        assertEquals(1, BrewingStatsTracker.trackedEventIdCountForTesting());

        BrewingStatsTracker.stopForTesting();
        assertEquals(0, BrewingStatsTracker.trackedEventIdCountForTesting());
    }

    @Test
    void eventIdCacheRespectsHardCap() {
        for (int i = 0; i < BrewingStatsTracker.MAX_TRACKED_EVENT_IDS_FOR_TESTING + 50; i++) {
            BrewingStatsTracker.onBeveragePrepared(
                    event(false, BeverageTier.COMMON, new ItemStack(Items.HONEY_BOTTLE)));
        }
        assertTrue(BrewingStatsTracker.trackedEventIdCountForTesting()
                <= BrewingStatsTracker.MAX_TRACKED_EVENT_IDS_FOR_TESTING,
                "the event-id cache must never exceed the hard cap");
    }
}
