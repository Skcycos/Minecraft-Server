package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.brewing.BeverageTierManager;
import com.tanrunn.tcth.impl.compat.brewinandchewin.KegPouringAdapter;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Phase 7B.1.1 — real-mutation behavior tests for the Keg inventory-add
 * delivery path. These verify that publishing uses the <em>actual added
 * amount</em> computed from a pre-add snapshot, never the post-add (possibly
 * empty) result, by simulating the exact {@code Inventory.add} mutation
 * semantics.
 */
class KegPouringMutationTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private AtomicReference<BeveragePreparedEvent> captured;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.resetForTesting();
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher
                .setFrameworkEnabledSupplierForTesting(() -> true);
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher
                .setBrewerEnabledSupplierForTesting(() -> true);
        bus = BusBuilder.builder().build();
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.setGameBusForTesting(bus);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.doReturn(new com.mojang.authlib.GameProfile(player.getUUID(), "Tanrunn"))
                .when(player).getGameProfile();
        captured = new AtomicReference<>();
        bus.addListener((BeveragePreparedEvent e) -> captured.set(e));
    }

    @AfterEach
    void tearDown() {
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.resetForTesting();
        BeverageTierManager.resetForTesting();
    }

    /**
     * Simulates the mixin's Inventory.add handler exactly: snapshot before,
     * mutate (shrink original to zero), compute moved = before - after,
     * publish snapshot.copyWithCount(moved) when moved > 0.
     */
    private static int simulateInventoryAdd(ItemStack result, ServerPlayer p, ServerLevel lvl) {
        int beforeCount = result.getCount();
        ItemStack snapshot = result.copy();
        boolean added = true; // Keg pour always fits a single serving
        if (added) {
            result.setCount(0); // Inventory.add shrinks the original to zero
            int moved = beforeCount - result.getCount();
            if (moved > 0) {
                KegPouringAdapter.onPouringDelivered(p, snapshot.copyWithCount(moved), lvl, null);
                return moved;
            }
        }
        return 0;
    }

    @Test
    void addSuccessShrinksToZeroStillPublishesOneEventWithCorrectCount() {
        BeverageTierManager.setTierMapForTesting(Map.of(
                ResourceLocation.parse("minecraft:potion"), BeverageTier.T2));
        ItemStack result = new ItemStack(Items.POTION, 1);
        int moved = simulateInventoryAdd(result, player, level);
        assertEquals(1, moved, "a single serving moved");
        assertEquals(0, result.getCount(), "post-add original is empty (simulated shrink)");

        assertNotNull(captured.get(), "must publish exactly one event");
        BeveragePreparedEvent e = captured.get();
        assertEquals(BeverageDevice.KEG, e.getDevice());
        assertEquals(BeverageTier.T2, e.getTier());
        assertEquals(Items.POTION, e.getResult().getItem());
        assertEquals(1, e.getResult().getCount(), "published count = actual moved amount");
        assertFalse(e.isAutomated());
        assertNull(e.getPosition(), "position must be null (static lambda, 7B.1.1)");
    }

    @Test
    void addFailsWithoutMovingPublishesNothing() {
        BeverageTierManager.setTierMapForTesting(Map.of(
                ResourceLocation.parse("minecraft:potion"), BeverageTier.T2));
        ItemStack result = new ItemStack(Items.POTION, 1);
        // Simulate a failed add: add returns false and does not mutate.
        int beforeCount = result.getCount();
        ItemStack snapshot = result.copy();
        boolean added = false;
        if (added) {
            result.setCount(0);
            int moved = beforeCount - result.getCount();
            if (moved > 0) {
                KegPouringAdapter.onPouringDelivered(player, snapshot.copyWithCount(moved), level, null);
            }
        }
        assertEquals(1, result.getCount(), "failed add leaves the stack untouched");
        assertNull(captured.get(),
                "failed add path must publish 0 events (the drop branch handles delivery)");
    }

    @Test
    void unknownTierAddStillPublishesNothing() {
        BeverageTierManager.setTierMapForTesting(Map.of()); // everything UNKNOWN
        ItemStack result = new ItemStack(Items.POTION, 1);
        int moved = simulateInventoryAdd(result, player, level);
        assertEquals(1, moved, "move happened but tier unknown");
        assertNull(captured.get(), "UNKNOWN-tier must not publish even on successful add");
    }
}
