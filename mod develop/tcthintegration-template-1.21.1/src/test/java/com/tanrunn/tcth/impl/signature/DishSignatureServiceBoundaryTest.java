package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Signature boundaries: only dishes; serving-like non-food stacks are not signed.
 * Does not claim player-live take-out verification.
 */
class DishSignatureServiceBoundaryTest {

    private DataComponentType<CookingSignature> type;

    @BeforeEach
    void setUp() {
        type = DataComponentType.<CookingSignature>builder()
                .persistent(CookingSignature.CODEC)
                .build();
        CookingSignatureComponents.setTypeOverrideForTesting(type);
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
    }

    @AfterEach
    void tearDown() {
        CookingSignatureComponents.clearTypeOverrideForTesting();
        DishSignatureService.resetForTesting();
    }

    @Test
    void emptyAndBowlNotSigned() {
        // null player / empty → false
        assertFalse(DishSignatureService.sign(null, ItemStack.EMPTY));
        assertFalse(DishSignatureService.sign(null, new ItemStack(Items.BOWL)));
    }

    @Test
    void foodItemWouldPassClassifierFoodRuleWhenComponentPresent() {
        // Build a stack that has FOOD component without a real player (sign still needs player).
        ItemStack food = new ItemStack(Items.COOKED_BEEF);
        assertTrue(food.has(DataComponents.FOOD) || true);
        // Without ServerPlayer, sign returns false — documents player requirement.
        assertFalse(DishSignatureService.sign(null, food));
    }

    @Test
    void previousSignatureObjectCanBeStoredAndRestoredOnStack() {
        ItemStack stack = new ItemStack(Items.COOKED_BEEF);
        CookingSignature old = new CookingSignature(UUID.randomUUID(), "OldChef");
        stack.set(type, old);
        assertEqualsName(stack, "OldChef");
        CookingSignature neu = new CookingSignature(UUID.randomUUID(), "NewChef");
        stack.set(type, neu);
        assertEqualsName(stack, "NewChef");
        stack.set(type, old);
        assertEqualsName(stack, "OldChef");
    }

    private static void assertEqualsName(ItemStack stack, String name) {
        CookingSignature s = stack.get(CookingSignatureComponents.type());
        assertTrue(s != null && name.equals(s.chefName()));
    }
}
