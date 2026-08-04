package com.tanrunn.tcth.impl.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Unit tests for {@link VanillaCookingDetector}.
 */
class VanillaCookingDetectorTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private AtomicInteger received;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        DishCookedEventDispatcher.resetForTesting();
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        bus = BusBuilder.builder().build();
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.serverLevel()).thenReturn(level);
        received = new AtomicInteger();
        bus.addListener(DishCookedEvent.class, dish -> received.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        DishCookedEventDispatcher.resetForTesting();
    }

    private void setOpenMenu(Player p, net.minecraft.world.inventory.AbstractContainerMenu menu) throws Exception {
        Player.class.getField("containerMenu").set(p, menu);
    }

    @Test
    void craftedFoodPublishesOnceWithCorrectData() throws Exception {
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
        ItemStack result = new ItemStack(Items.COOKED_BEEF, 3);
        PlayerEvent.ItemCraftedEvent event = new PlayerEvent.ItemCraftedEvent(player, result,
                Mockito.mock(Container.class));

        VanillaCookingDetector.onItemCrafted(event);

        assertEquals(1, received.get(), "exactly one event per take");
        assertEquals(CookingDevice.CRAFTING, captured.get().getDevice());
        assertEquals(player, captured.get().getPlayer());
        assertFalse(captured.get().isAutomated());
        assertEquals(3, captured.get().getResult().getCount(), "bulk count must be carried by the stack");
        assertEquals(DishQuality.UNKNOWN, captured.get().getQuality());
        assertNull(captured.get().getRecipeId());
        assertNull(captured.get().getPosition());
    }

    @Test
    void craftedNonDishDoesNotPublish() {
        PlayerEvent.ItemCraftedEvent event = new PlayerEvent.ItemCraftedEvent(player,
                new ItemStack(Items.DIRT), Mockito.mock(Container.class));

        VanillaCookingDetector.onItemCrafted(event);

        assertEquals(0, received.get(), "building blocks must not produce cooking events");
    }

    @Test
    void smeltedFoodInFurnaceMenuIsFurnace() throws Exception {
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
        setOpenMenu(player, Mockito.mock(FurnaceMenu.class));
        PlayerEvent.ItemSmeltedEvent event = new PlayerEvent.ItemSmeltedEvent(player,
                new ItemStack(Items.COOKED_BEEF));

        VanillaCookingDetector.onItemSmelted(event);

        assertEquals(1, received.get());
        assertEquals(CookingDevice.FURNACE, captured.get().getDevice());
        assertFalse(captured.get().isAutomated());
    }

    @Test
    void smeltedFoodInSmokerMenuIsSmoker() throws Exception {
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
        setOpenMenu(player, Mockito.mock(SmokerMenu.class));
        PlayerEvent.ItemSmeltedEvent event = new PlayerEvent.ItemSmeltedEvent(player,
                new ItemStack(Items.COOKED_PORKCHOP));

        VanillaCookingDetector.onItemSmelted(event);

        assertEquals(1, received.get());
        assertEquals(CookingDevice.SMOKER, captured.get().getDevice());
    }

    @Test
    void smeltedWithoutMatchingMenuFallsBackToFurnace() throws Exception {
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
        PlayerEvent.ItemSmeltedEvent event = new PlayerEvent.ItemSmeltedEvent(player,
                new ItemStack(Items.COOKED_BEEF));

        VanillaCookingDetector.onItemSmelted(event);

        assertEquals(1, received.get());
        assertEquals(CookingDevice.FURNACE, captured.get().getDevice(),
                "unmatched menu must fall back to FURNACE as documented");
    }

    @Test
    void smeltedNonDishDoesNotPublish() throws Exception {
        setOpenMenu(player, Mockito.mock(FurnaceMenu.class));
        PlayerEvent.ItemSmeltedEvent event = new PlayerEvent.ItemSmeltedEvent(player,
                new ItemStack(Items.IRON_INGOT));

        VanillaCookingDetector.onItemSmelted(event);

        assertEquals(0, received.get(), "smelting iron must not be a cooking event");
    }
}
