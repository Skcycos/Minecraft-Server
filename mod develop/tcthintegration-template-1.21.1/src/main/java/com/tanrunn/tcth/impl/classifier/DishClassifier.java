package com.tanrunn.tcth.impl.classifier;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Classifies whether an {@link ItemStack} counts as a "dish" for TCTH cooking
 * events.
 *
 * <p>Rules, in priority order:
 * <ol>
 *   <li>{@code tcth:not_dishes} — any item in this tag is <em>never</em> a
 *       dish, regardless of the other rules (exclusion wins);</li>
 *   <li>an item carrying the {@code minecraft:food} data component is a
 *       dish;</li>
 *   <li>{@code tcth:dishes} — any item explicitly tagged here is a dish
 *       (data-pack extension point for items without a food component).</li>
 * </ol>
 *
 * <p>This keeps vanilla building blocks, tools, machines and decorations from
 * producing cooking events while allowing server packs to extend the set via
 * data tags (no code change required).
 */
public final class DishClassifier {

    public static final TagKey<Item> DISHES_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("tcth", "dishes"));
    public static final TagKey<Item> NOT_DISHES_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("tcth", "not_dishes"));

    private DishClassifier() {
    }

    /**
     * @param stack the result item to classify
     * @return {@code true} if the stack is a dish according to the rules above
     */
    public static boolean isDish(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isDish(stack, DISHES_TAG, NOT_DISHES_TAG);
    }

    /**
     * Rule implementation with injectable tags (package-private for tests).
     */
    static boolean isDish(ItemStack stack, TagKey<Item> dishesTag, TagKey<Item> notDishesTag) {
        Holder<Item> holder = stack.getItemHolder();
        if (holder.is(notDishesTag)) {
            return false;
        }
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
            return true;
        }
        return holder.is(dishesTag);
    }
}
