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

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Unit tests for {@link FarmersDelightDishAdapter} field mapping and
 * {@link FarmersDelightRecipeIds} tracker resolution.
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
    void mapsPlayerDevicePositionRecipeAndResultStack() {
        ResourceLocation recipeId = ResourceLocation.parse("farmersdelight:cooking/cooked_rice");
        ItemStack result = new ItemStack(Items.COOKED_BEEF, 2);

        FarmersDelightDishAdapter.onDishTaken(player, result, recipeId, pot, level);

        DishCookedEvent event = captured.get();
        assertEquals(CookingDevice.FARMERS_DELIGHT_COOKING_POT, event.getDevice());
        assertEquals(player, event.getPlayer());
        assertFalse(event.isAutomated(), "player take-out must not be automated");
        assertEquals(recipeId, event.getRecipeId());
        assertEquals(new BlockPos(10, 64, 20), event.getPosition());
        assertEquals(DishQuality.UNKNOWN, event.getQuality(), "FD has no quality system");
        assertEquals(2, event.getResult().getCount());
        assertEquals(Items.COOKED_BEEF, event.getResult().getItem(), "event result must be the onTake stack");
    }

    @Test
    void eventResultIsTheOnTakeStackNotTheHeldItem() {
        ItemStack held = new ItemStack(Items.BOWL);
        ItemStack onTakeStack = new ItemStack(Items.COOKED_BEEF, 1);
        FarmersDelightDishAdapter.onDishTaken(player, onTakeStack, null, pot, level);

        assertTrue(!captured.get().getResult().is(held.getItem()),
                "the held bowl must never become the event result");
        assertEquals(Items.COOKED_BEEF, captured.get().getResult().getItem());
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

    @Test
    void excludedItemIsNotPublished() {
        // Any item in tcth:not_dishes must be skipped by the FD adapter too.
        net.minecraft.core.Holder<Item> holder = Mockito.mock(net.minecraft.core.Holder.class);
        ItemStack excluded = Mockito.mock(ItemStack.class);
        Mockito.when(excluded.getItemHolder()).thenReturn(holder);
        Mockito.when(excluded.isEmpty()).thenReturn(false);
        Mockito.when(holder.is(Mockito.any(net.minecraft.tags.TagKey.class))).thenReturn(false);
        Mockito.when(holder.is(com.tanrunn.tcth.impl.classifier.DishClassifier.NOT_DISHES_TAG)).thenReturn(true);

        FarmersDelightDishAdapter.onDishTaken(player, excluded, null, pot, level);

        assertNull(captured.get(), "not_dishes items must not be published");
    }

    // ---- FarmersDelightRecipeIds (used-recipe tracker resolution) ----

    @Test
    void singleTrackerRecipeIdIsResolved() {
        Object2IntOpenHashMap<ResourceLocation> tracker = new Object2IntOpenHashMap<>();
        tracker.put(ResourceLocation.parse("farmersdelight:cooking/stew"), 1);

        assertEquals(ResourceLocation.parse("farmersdelight:cooking/stew"),
                FarmersDelightRecipeIds.resolveRecipeId(tracker));
    }

    @Test
    void emptyTrackerResolvesToNull() {
        assertNull(FarmersDelightRecipeIds.resolveRecipeId(new Object2IntOpenHashMap<>()));
        assertNull(FarmersDelightRecipeIds.resolveRecipeId(null));
    }

    @Test
    void ambiguousMultipleTrackerEntriesResolveToNull() {
        Object2IntOpenHashMap<ResourceLocation> tracker = new Object2IntOpenHashMap<>();
        tracker.put(ResourceLocation.parse("farmersdelight:cooking/stew"), 1);
        tracker.put(ResourceLocation.parse("farmersdelight:cooking/soup"), 2);

        assertNull(FarmersDelightRecipeIds.resolveRecipeId(tracker),
                "multiple candidates cannot be reliably matched -> null");
    }

    @Test
    void resolvedRecipeIdSurvivesTrackerClearing() {
        Object2IntOpenHashMap<ResourceLocation> tracker = new Object2IntOpenHashMap<>();
        ResourceLocation recipeId = ResourceLocation.parse("farmersdelight:cooking/stew");
        tracker.put(recipeId, 1);

        ResourceLocation capturedId = FarmersDelightRecipeIds.resolveRecipeId(tracker);
        tracker.clear(); // what awardUsedRecipes does after onTake

        assertEquals(recipeId, capturedId, "HEAD-captured id must survive tracker clearing");
    }
}
