package com.tanrunn.tcth.impl.compat.kaleidoscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Unit tests for {@link KaleidoscopeDishAdapter} quality mapping and take-out
 * mapping.
 */
class KaleidoscopeDishAdapterTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
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
        captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);
    }

    @AfterEach
    void tearDown() {
        DishCookedEventDispatcher.resetForTesting();
    }

    private ItemStack anyStack() {
        return new ItemStack(Items.COOKED_BEEF);
    }

    @Test
    void mapsKaleidoscopeQualities() {
        ItemStack stack = anyStack();
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(stack)).thenReturn(true);
            mocked.when(() -> QualityUtils.getQuality(stack)).thenReturn(Quality.SUPERB);
            assertEquals(DishQuality.SUPERB, KaleidoscopeDishAdapter.mapQuality(stack));
            mocked.when(() -> QualityUtils.getQuality(stack)).thenReturn(Quality.EXCELLENT);
            assertEquals(DishQuality.EXCELLENT, KaleidoscopeDishAdapter.mapQuality(stack));
            mocked.when(() -> QualityUtils.getQuality(stack)).thenReturn(Quality.STANDARD);
            assertEquals(DishQuality.STANDARD, KaleidoscopeDishAdapter.mapQuality(stack));
            mocked.when(() -> QualityUtils.getQuality(stack)).thenReturn(Quality.POOR);
            assertEquals(DishQuality.POOR, KaleidoscopeDishAdapter.mapQuality(stack));
        }
    }

    @Test
    void unknownQualityWhenNoQualityPresent() {
        ItemStack stack = anyStack();
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(stack)).thenReturn(false);
            assertEquals(DishQuality.UNKNOWN, KaleidoscopeDishAdapter.mapQuality(stack));
        }
    }

    @Test
    void playerTakeOutMapsDeviceAndAutomation() {
        ItemStack stack = anyStack();
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(Mockito.any())).thenReturn(false);
            KaleidoscopeDishAdapter.onDishTaken(player, stack, CookingDevice.KALEIDOSCOPE_STOCKPOT,
                    level, new BlockPos(1, 2, 3));
        }

        DishCookedEvent event = captured.get();
        assertEquals(CookingDevice.KALEIDOSCOPE_STOCKPOT, event.getDevice());
        assertEquals(player, event.getPlayer());
        assertTrue(!event.isAutomated());
        assertEquals(new BlockPos(1, 2, 3), event.getPosition());
        assertNull(event.getRecipeId(), "KC does not expose a recipe id through the public API");
        assertEquals(DishQuality.UNKNOWN, event.getQuality());
    }

    @Test
    void automatedTakeOutMapsNullPlayerAndAutomatedTrue() {
        ItemStack stack = anyStack();
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(Mockito.any())).thenReturn(false);
            KaleidoscopeDishAdapter.onDishTaken(null, stack, CookingDevice.KALEIDOSCOPE_COOKING_POT,
                    level, new BlockPos(0, 0, 0));
        }

        DishCookedEvent event = captured.get();
        assertNull(event.getPlayer());
        assertTrue(event.isAutomated(), "non-player actor must be flagged automated");
        assertEquals(CookingDevice.KALEIDOSCOPE_COOKING_POT, event.getDevice());
    }

    @Test
    void heldCarrierIsNeverPublishedAsResult() {
        // takeOutProduct's third parameter is the held shovel/carrier, never
        // the dish; the adapter must publish the dish result, not the carrier.
        ItemStack carrier = new ItemStack(Items.BOWL);
        ItemStack dish = new ItemStack(Items.COOKED_BEEF, 2);
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(Mockito.any())).thenReturn(false);
            // The adapter API has no carrier parameter: only the dish is passed.
            KaleidoscopeDishAdapter.onDishTaken(player, dish, CookingDevice.KALEIDOSCOPE_COOKING_POT,
                    level, new BlockPos(5, 6, 7));
        }

        DishCookedEvent event = captured.get();
        assertEquals(Items.COOKED_BEEF, event.getResult().getItem(), "event result must be the dish");
        assertTrue(!event.getResult().is(carrier.getItem()), "held/carrier must never be the event result");
        assertEquals(2, event.getResult().getCount(), "pot result keeps its real count");
    }

    @Test
    void qualityIsReadFromDishResultNotFromCarrier() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        try (MockedStatic<QualityUtils> mocked = Mockito.mockStatic(QualityUtils.class)) {
            mocked.when(() -> QualityUtils.hasQuality(Mockito.any())).thenReturn(true);
            mocked.when(() -> QualityUtils.getQuality(Mockito.any())).thenReturn(Quality.SUPERB);
            KaleidoscopeDishAdapter.onDishTaken(player, dish, CookingDevice.KALEIDOSCOPE_STOCKPOT,
                    level, new BlockPos(0, 0, 0));
        }

        assertEquals(DishQuality.SUPERB, captured.get().getQuality(),
                "quality must be mapped from the dish result stack");
    }

    @Test
    void notDishesItemIsNotPublished() {
        // raw_dough (tcth:not_dishes) taken from a steamer must not produce an
        // event, even though it carries a food component.
        net.minecraft.core.Holder<Item> holder = Mockito.mock(net.minecraft.core.Holder.class);
        ItemStack rawDough = Mockito.mock(ItemStack.class);
        Mockito.when(rawDough.getItemHolder()).thenReturn(holder);
        Mockito.when(rawDough.isEmpty()).thenReturn(false);
        Mockito.when(holder.is(Mockito.any(net.minecraft.tags.TagKey.class))).thenReturn(false);
        Mockito.when(holder.is(com.tanrunn.tcth.impl.classifier.DishClassifier.NOT_DISHES_TAG)).thenReturn(true);

        KaleidoscopeDishAdapter.onDishTaken(player, rawDough, CookingDevice.KALEIDOSCOPE_STEAMER,
                level, new BlockPos(0, 0, 0));

        assertNull(captured.get(), "not_dishes items must not be published");
    }
}
