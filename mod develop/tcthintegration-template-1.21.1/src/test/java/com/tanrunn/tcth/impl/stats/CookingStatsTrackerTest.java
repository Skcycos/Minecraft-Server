package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link CookingStatsTracker} event filtering and counting.
 */
class CookingStatsTrackerTest {

    private ServerLevel level;
    private ServerPlayer player;
    private CookingStatsData data;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        CookingStatsTracker.resetForTesting();
        CookingStatsTracker.setFrameworkEnabledSupplierForTesting(() -> true);
        CookingStatsTracker.setEnabledSupplierForTesting(() -> true);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        data = new CookingStatsData();
        CookingStatsTracker.setDataProviderForTesting(l -> data);
    }

    private DishCookedEvent event(boolean automated, ItemStack result) {
        return new DishCookedEvent(UUID.randomUUID(), automated ? null : player, null, result,
                CookingDevice.FURNACE, DishQuality.UNKNOWN, automated, level, null);
    }

    @Test
    void automatedEventsAreNotCounted() {
        CookingStatsTracker.onDishCooked(event(true, new ItemStack(Items.COOKED_BEEF)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void nullPlayerEventsAreNotCounted() {
        CookingStatsTracker.onDishCooked(event(true, new ItemStack(Items.COOKED_BEEF)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void nonDishItemsAreNotCounted() {
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.DIRT)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void duplicateEventIdIsNotCountedTwice() {
        DishCookedEvent e = event(false, new ItemStack(Items.COOKED_BEEF));
        CookingStatsTracker.onDishCooked(e);
        CookingStatsTracker.onDishCooked(e);
        CookingStatsTracker.onDishCooked(e);
        assertEquals(1, data.get(player.getUUID()).getTotalDishesCooked());
    }

    @Test
    void disabledModuleCountsNothing() {
        CookingStatsTracker.setEnabledSupplierForTesting(() -> false);
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF)));
        assertTrue(data.getPlayers().isEmpty());
    }

    @Test
    void twoPlayersAreIsolated() {
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF)));
        ServerPlayer other = Mockito.mock(ServerPlayer.class);
        Mockito.when(other.getUUID()).thenReturn(UUID.randomUUID());
        DishCookedEvent e2 = new DishCookedEvent(UUID.randomUUID(), other, null,
                new ItemStack(Items.COOKED_PORKCHOP), CookingDevice.SMOKER, DishQuality.SUPERB, false, level, null);
        CookingStatsTracker.onDishCooked(e2);

        assertEquals(2, data.getPlayers().size());
        assertEquals(1, data.get(player.getUUID()).getTotalDishesCooked());
        assertEquals(1, data.get(other.getUUID()).getTotalDishesCooked());
    }

    @Test
    void countIsAccumulatedAndZeroCountFiltered() {
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF, 3)));
        assertEquals(3, data.get(player.getUUID()).getTotalDishesCooked(), "count accumulates");
        assertEquals(1, data.get(player.getUUID()).getTotalCookingEvents());
    }

    @Test
    void zeroCountEventIsNotCounted() {
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF, 0)));
        assertTrue(data.getPlayers().isEmpty(), "zero-count stacks are skipped");
    }

    @Test
    void dishClassifierCheckIsApplied() {
        // cooked beef passes; the tracker must have counted it
        CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF)));
        assertTrue(DishClassifier.isDish(new ItemStack(Items.COOKED_BEEF)));
        assertEquals(1, data.get(player.getUUID()).getTotalDishesCooked());
    }

    @Test
    void eventIdCacheIsBounded() {
        for (int i = 0; i < CookingStatsTracker.MAX_TRACKED_EVENT_IDS_FOR_TESTING + 50; i++) {
            CookingStatsTracker.onDishCooked(event(false, new ItemStack(Items.COOKED_BEEF)));
        }
        assertTrue(CookingStatsTracker.trackedEventIdCountForTesting() <= CookingStatsTracker.MAX_TRACKED_EVENT_IDS_FOR_TESTING);
        assertFalse(data.getPlayers().isEmpty(), "statistics still recorded");
    }

    @Test
    void tierDataReloadRegisteredByTcthDataReloads() {
        // Reload listeners moved out of CookingStatsTracker (7B.1): the
        // brewer/cook tier data reload is registered by TcthDataReloads and is
        // independent of the cooking stats tracker.
        net.neoforged.neoforge.event.AddReloadListenerEvent evt =
                Mockito.mock(net.neoforged.neoforge.event.AddReloadListenerEvent.class);
        com.tanrunn.tcth.impl.brewing.TcthDataReloads.onAddReloadListeners(evt);
        // dish tiers + brewer tiers + shadow_loot (8D.1 §3).
        Mockito.verify(evt, Mockito.times(3)).addListener(Mockito.any());
    }

    @Test
    void statsDataDoesNotExistUntilFirstRecord() {
        assertNull(data.get(player.getUUID()));
    }
}
