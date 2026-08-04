package com.tanrunn.tcth.api.cooking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link DishCookedEvent} construction rules and defensive
 * copying.
 */
class DishCookedEventTest {

    private final ServerLevel level = Mockito.mock(ServerLevel.class);

    @BeforeAll
    static void bootstrapMinecraft() {
        // ServerLevel/ItemStack static initializers require the Minecraft
        // registries; bootstrap them once in this bare JUnit environment.
        MinecraftTestBootstrap.bootStrap();
    }

    private DishCookedEvent createEvent(ItemStack result) {
        return new DishCookedEvent(UUID.randomUUID(), null, null, result, CookingDevice.FURNACE,
                DishQuality.STANDARD, false, level, null);
    }

    @Test
    void constructorRejectsNullMandatoryArguments() {
        ItemStack result = new ItemStack(Items.COOKED_BEEF);
        assertThrows(NullPointerException.class,
                () -> new DishCookedEvent(null, null, null, result, CookingDevice.FURNACE, DishQuality.STANDARD, false, level, null));
        assertThrows(NullPointerException.class,
                () -> new DishCookedEvent(UUID.randomUUID(), null, null, null, CookingDevice.FURNACE, DishQuality.STANDARD, false, level, null));
        assertThrows(NullPointerException.class,
                () -> new DishCookedEvent(UUID.randomUUID(), null, null, result, null, DishQuality.STANDARD, false, level, null));
        assertThrows(NullPointerException.class,
                () -> new DishCookedEvent(UUID.randomUUID(), null, null, result, CookingDevice.FURNACE, null, false, level, null));
        assertThrows(NullPointerException.class,
                () -> new DishCookedEvent(UUID.randomUUID(), null, null, result, CookingDevice.FURNACE, DishQuality.STANDARD, false, null, null));
    }

    @Test
    void itemStackIsDefensivelyCopied() {
        ItemStack original = new ItemStack(Items.COOKED_BEEF, 1);
        DishCookedEvent event = createEvent(original);

        assertNotSame(original, event.getResult(), "event must not expose the caller's ItemStack instance");

        // Mutating the caller's stack must not affect the event.
        original.setCount(64);
        assertEquals(1, event.getResult().getCount());

        // Each getter call returns a fresh copy.
        ItemStack first = event.getResult();
        ItemStack second = event.getResult();
        assertNotSame(first, second, "getResult must return a fresh copy per access");
        first.setCount(99);
        assertEquals(1, event.getResult().getCount());
    }

    @Test
    void nullablePlayerRecipeIdAndPositionAreHandled() {
        DishCookedEvent event = new DishCookedEvent(UUID.randomUUID(), null, null,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, DishQuality.STANDARD, false, level, null);

        assertNull(event.getPlayer());
        assertNull(event.getRecipeId());
        assertNull(event.getPosition());
        assertSame(level, event.getLevel());
    }

    @Test
    void gettersReturnStoredValues() {
        UUID eventId = UUID.randomUUID();
        ResourceLocation recipeId = ResourceLocation.parse("minecraft:cooked_beef");
        BlockPos position = new BlockPos(1, 2, 3);
        DishCookedEvent event = new DishCookedEvent(eventId, null, recipeId,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.SMOKER, DishQuality.EXCELLENT, true, level, position);

        assertEquals(eventId, event.getEventId());
        assertEquals(recipeId, event.getRecipeId());
        assertEquals(CookingDevice.SMOKER, event.getDevice());
        assertEquals(DishQuality.EXCELLENT, event.getQuality());
        assertTrue(event.isAutomated());
        assertEquals(position, event.getPosition());
        assertEquals(Items.COOKED_BEEF, event.getResult().getItem());
    }

    @Test
    void eventIdIsStableForEventLifetime() {
        DishCookedEvent event = createEvent(new ItemStack(Items.COOKED_BEEF));
        assertFalse(event.getEventId().equals(new UUID(0, 0)));
        assertEquals(event.getEventId(), event.getEventId(), "eventId must be constant");
    }
}
