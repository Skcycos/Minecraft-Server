package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.player.ArcPlayer;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link DishActionDispatcher} action-data mapping.
 */
class DishActionDispatcherTest {

    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void actionDataFieldsAreCompleteAndCorrect() {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player,
                ResourceLocation.parse("minecraft:cooked_beef"), new ItemStack(Items.COOKED_BEEF, 3),
                CookingDevice.SMOKER, DishQuality.EXCELLENT, false, level, null);
        ArcPlayer arcPlayer = Mockito.mock(ArcPlayer.class);

        var data = DishActionDispatcher.buildActionData(arcPlayer, e, DishTier.T3);

        // Arc-native item data: the stack is the dish (not the carrier/tool).
        ItemStack stack = data.getData(ActionDataType.ITEM_STACK);
        assertEquals(Items.COOKED_BEEF, stack.getItem(), "ITEM_STACK must be the dish result");
        assertEquals(3, stack.getCount());
        assertEquals(Items.COOKED_BEEF, data.getData(ActionDataType.ITEM), "ITEM must be the dish item");
        // Stable namespace:path id (not Item.toString()).
        assertEquals("minecraft:cooked_beef", data.getData(TcthArcRegistrar.RESULT_ITEM_ID));
        assertEquals(3, data.getData(TcthArcRegistrar.COUNT));
        assertEquals("minecraft:cooked_beef", data.getData(TcthArcRegistrar.RECIPE_ID));
        assertEquals("SMOKER", data.getData(TcthArcRegistrar.DEVICE));
        assertEquals("EXCELLENT", data.getData(TcthArcRegistrar.QUALITY));
        assertEquals("T3", data.getData(TcthArcRegistrar.TIER));
        assertEquals(false, data.getData(TcthArcRegistrar.AUTOMATED));
    }

    @Test
    void itemStackDataIsDefensiveCopyNotCarrier() {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        ItemStack dish = new ItemStack(Items.COOKED_BEEF, 1);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player, null, dish,
                CookingDevice.FURNACE, DishQuality.UNKNOWN, false, level, null);
        var data = DishActionDispatcher.buildActionData(Mockito.mock(ArcPlayer.class), e, DishTier.COMMON);

        ItemStack stack = data.getData(ActionDataType.ITEM_STACK);
        assertNotSame(dish, stack, "ITEM_STACK must be a defensive copy");
        assertEquals(Items.COOKED_BEEF, stack.getItem(), "must be the dish, never a carrier/tool");
    }

    @Test
    void nullRecipeIdOmitsRecipeField() {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player, null,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, DishQuality.UNKNOWN, false, level, null);
        var data = DishActionDispatcher.buildActionData(Mockito.mock(ArcPlayer.class), e, DishTier.COMMON);

        assertNull(data.getData(TcthArcRegistrar.RECIPE_ID));
    }

    @Test
    void sendFailureIsSwallowedAndReturnsNull() {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getGameProfile()).thenReturn(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "tester"));
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player, null,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, DishQuality.UNKNOWN, false, level, null);

        // A plain mock ServerPlayer is not an ArcServerPlayer -> ClassCastException
        // inside the try, which must be swallowed and return null.
        assertNull(DishActionDispatcher.sendDishAction(player, e, DishTier.COMMON));
    }
}
