package com.tanrunn.tcth.impl.compat.farmersdelight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Unit tests for {@link FarmersDelightDishAdapter} field mapping.
 */
class FarmersDelightDishAdapterTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private CookingPotBlockEntity pot;
    private AtomicReference<DishCookedEvent> captured;

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
        pot = Mockito.mock(CookingPotBlockEntity.class);
        Mockito.when(pot.getLevel()).thenReturn(level);
        Mockito.when(pot.getBlockPos()).thenReturn(new BlockPos(10, 64, 20));
        captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
    }

    @AfterEach
    void tearDown() {
        DishCookedEventDispatcher.resetForTesting();
    }

    @Test
    void mapsPlayerDevicePositionQualityAndRecipe() {
        RecipeHolder<?> recipe = Mockito.mock(RecipeHolder.class);
        Mockito.when(recipe.id()).thenReturn(ResourceLocation.parse("farmersdelight:cooking/cooked_rice"));
        ItemStack result = new ItemStack(Items.COOKED_BEEF, 2);

        FarmersDelightDishAdapter.onDishTaken(player, result, recipe, pot, level);

        DishCookedEvent event = captured.get();
        assertEquals(CookingDevice.FARMERS_DELIGHT_COOKING_POT, event.getDevice());
        assertEquals(player, event.getPlayer());
        assertFalse(event.isAutomated(), "player take-out must not be automated");
        assertEquals(ResourceLocation.parse("farmersdelight:cooking/cooked_rice"), event.getRecipeId());
        assertEquals(new BlockPos(10, 64, 20), event.getPosition());
        assertEquals(DishQuality.UNKNOWN, event.getQuality(), "FD has no quality system");
        assertEquals(2, event.getResult().getCount());
    }

    @Test
    void nullRecipeProducesNullRecipeId() {
        ItemStack result = new ItemStack(Items.COOKED_BEEF);
        FarmersDelightDishAdapter.onDishTaken(player, result, null, pot, level);

        assertNull(captured.get().getRecipeId());
    }

    @Test
    void nullPlayerIsAutomated() {
        ItemStack result = new ItemStack(Items.COOKED_BEEF);
        FarmersDelightDishAdapter.onDishTaken(null, result, null, pot, level);

        assertNull(captured.get().getPlayer());
        assertTrue(captured.get().isAutomated(), "non-player actor must be flagged automated");
    }
}
