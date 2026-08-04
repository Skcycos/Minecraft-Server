package com.tanrunn.tcth.impl.classifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link DishClassifier} dish classification rules.
 */
class DishClassifierTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void foodItemsAreDishes() {
        assertTrue(DishClassifier.isDish(new ItemStack(Items.COOKED_BEEF)),
                "cooked beef carries the food component");
        assertTrue(DishClassifier.isDish(new ItemStack(Items.COOKED_PORKCHOP, 3)));
        assertTrue(DishClassifier.isDish(new ItemStack(Items.BAKED_POTATO)));
    }

    @Test
    void buildingBlocksToolsAndDecorationsAreNotDishes() {
        assertFalse(DishClassifier.isDish(new ItemStack(Items.DIRT)), "building block must not be a dish");
        assertFalse(DishClassifier.isDish(new ItemStack(Items.IRON_BLOCK)), "machine block must not be a dish");
        assertFalse(DishClassifier.isDish(new ItemStack(Items.IRON_PICKAXE)), "tool must not be a dish");
        assertFalse(DishClassifier.isDish(new ItemStack(Items.CRAFTING_TABLE)), "decoration/utility must not be a dish");
        assertFalse(DishClassifier.isDish(ItemStack.EMPTY), "empty stack is never a dish");
    }

    @Test
    void dishesTagCanExtendRecognition() {
        Holder<Item> holder = Mockito.mock(Holder.class);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getItemHolder()).thenReturn(holder);
        Mockito.when(stack.has(DataComponents.FOOD)).thenReturn(false);
        Mockito.when(holder.is(Mockito.any(net.minecraft.tags.TagKey.class))).thenReturn(false);
        Mockito.when(holder.is(DishClassifier.DISHES_TAG)).thenReturn(true);

        assertTrue(DishClassifier.isDish(stack), "tcth:dishes tag must extend recognition");
    }

    @Test
    void notDishesTagOverridesEverything() {
        Holder<Item> holder = Mockito.mock(Holder.class);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getItemHolder()).thenReturn(holder);
        Mockito.when(stack.has(DataComponents.FOOD)).thenReturn(true);
        Mockito.when(holder.is(Mockito.any(net.minecraft.tags.TagKey.class))).thenReturn(false);
        Mockito.when(holder.is(DishClassifier.NOT_DISHES_TAG)).thenReturn(true);

        assertFalse(DishClassifier.isDish(stack),
                "tcth:not_dishes must win even over the food component");
    }

    @Test
    void notDishesWinsOverDishesTag() {
        Holder<Item> holder = Mockito.mock(Holder.class);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getItemHolder()).thenReturn(holder);
        Mockito.when(stack.has(DataComponents.FOOD)).thenReturn(false);
        Mockito.when(holder.is(Mockito.any(net.minecraft.tags.TagKey.class))).thenReturn(false);
        Mockito.when(holder.is(DishClassifier.NOT_DISHES_TAG)).thenReturn(true);
        Mockito.when(holder.is(DishClassifier.DISHES_TAG)).thenReturn(true);

        assertFalse(DishClassifier.isDish(stack), "exclusion tag has priority over the dishes tag");
    }
}
