package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Unit tests for {@link DishCookedEventDispatcher} (central publishing entry).
 */
class DishCookedEventDispatcherTest {

    private IEventBus bus;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        // ServerLevel static initializers require the Minecraft registries;
        // bootstrap them once in this bare JUnit environment.
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        DishCookedEventDispatcher.resetForTesting();
        bus = BusBuilder.builder().build();
        level = Mockito.mock(ServerLevel.class); // isClientSide() -> false by default
    }

    @AfterEach
    void tearDown() {
        DishCookedEventDispatcher.resetForTesting();
    }

    private DishCookedEventDispatcher.Result publishDefault() {
        return DishCookedEventDispatcher.publish(null, null, new ItemStack(Items.COOKED_BEEF),
                CookingDevice.FURNACE, DishQuality.STANDARD, false, level, null);
    }

    @Test
    void frameworkDisabledDoesNotPublish() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> false);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicInteger received = new AtomicInteger();
        bus.addListener(DishCookedEvent.class, dish -> received.incrementAndGet());

        assertEquals(DishCookedEventDispatcher.Result.FRAMEWORK_DISABLED, publishDefault());
        assertEquals(0, received.get(), "no event may be posted when the framework is disabled");
    }

    @Test
    void enabledPublishesExactlyOnce() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicInteger received = new AtomicInteger();
        bus.addListener(DishCookedEvent.class, dish -> received.incrementAndGet());

        assertEquals(DishCookedEventDispatcher.Result.POSTED, publishDefault());
        assertEquals(1, received.get(), "exactly one DishCookedEvent must be posted");
    }

    @Test
    void eventIdIsNonEmptyAndUniquePerPublish() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicReference<DishCookedEvent> first = new AtomicReference<>();
        AtomicReference<DishCookedEvent> second = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, dish -> {
            if (first.get() == null) {
                first.set(dish);
            } else {
                second.set(dish);
            }
        });

        publishDefault();
        publishDefault();

        assertTrue(first.get().getEventId() != null);
        assertFalse(first.get().getEventId().equals(new java.util.UUID(0, 0)));
        assertEquals(first.get().getEventId(), first.get().getEventId(), "eventId stays constant within one event");
        assertFalse(first.get().getEventId().equals(second.get().getEventId()),
                "a new dish gets a new event id");
    }

    @Test
    void nullablePlayerRecipeIdAndPositionPublishSafely() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, dish -> captured.set(dish));

        assertEquals(DishCookedEventDispatcher.Result.POSTED, publishDefault());
        assertNull(captured.get().getPlayer());
        assertNull(captured.get().getRecipeId());
        assertNull(captured.get().getPosition());
    }

    @Test
    void invalidContextIsRejected() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicInteger received = new AtomicInteger();
        bus.addListener(DishCookedEvent.class, dish -> received.incrementAndGet());

        // Client-side level.
        ServerLevel clientLevel = Mockito.mock(ServerLevel.class);
        Mockito.when(clientLevel.isClientSide()).thenReturn(true);
        assertEquals(DishCookedEventDispatcher.Result.INVALID_CONTEXT,
                DishCookedEventDispatcher.publish(null, null, new ItemStack(Items.COOKED_BEEF),
                        CookingDevice.FURNACE, DishQuality.STANDARD, false, clientLevel, null));

        // Null level.
        assertEquals(DishCookedEventDispatcher.Result.INVALID_CONTEXT,
                DishCookedEventDispatcher.publish(null, null, new ItemStack(Items.COOKED_BEEF),
                        CookingDevice.FURNACE, DishQuality.STANDARD, false, null, null));

        assertEquals(0, received.get(), "no event may be posted outside a server context");
    }

    @Test
    void dispatcherPublishesDefensiveCopyWithEventResult() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        AtomicReference<DishCookedEvent> captured = new AtomicReference<>();
        bus.addListener(DishCookedEvent.class, dish -> captured.set(dish));

        ItemStack original = new ItemStack(Items.COOKED_BEEF, 2);
        DishCookedEventDispatcher.publish(null, null, original, CookingDevice.FURNACE,
                DishQuality.STANDARD, false, level, null);

        assertNotSame(original, captured.get().getResult());
        original.setCount(5);
        assertEquals(2, captured.get().getResult().getCount());
    }

    @Test
    void repeatedInitDoesNotDuplicateListeners() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.setGameBusForTesting(bus);

        DishCookedEventDispatcher.init();
        DishCookedEventDispatcher.init();

        AtomicInteger received = new AtomicInteger();
        bus.addListener(DishCookedEvent.class, dish -> received.incrementAndGet());
        publishDefault();
        publishDefault();

        assertEquals(2, received.get(), "repeated init must not register extra listeners");
    }

    @Test
    void publicApiDoesNotReferenceOptionalModClasses() throws Exception {
        List<String> classes = List.of(
                "com/tanrunn/tcth/api/cooking/DishQuality.class",
                "com/tanrunn/tcth/api/cooking/CookingDevice.class",
                "com/tanrunn/tcth/api/cooking/DishCookedEvent.class");
        List<String> forbidden = List.of(
                "jobsplus", "daqem", "ysbbbbbb", "vectorwing", "kaleidoscope",
                "farmersdelight", "bountiful", "ordertocook", "lightman", "ejekta");

        for (String resource : classes) {
            String className = resource.replace('/', '.').replace(".class", "");
            Class<?> anchor = Class.forName(className);
            Path classFile = Path.of(anchor.getClassLoader().getResource(resource).toURI());
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            for (String needle : forbidden) {
                assertFalse(bytes.contains(needle),
                        resource + " must not reference optional mod type: " + needle);
            }
        }
    }
}
