package com.tanrunn.tcth.mixin.farmersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Accessor for {@link CookingPotBlockEntity}'s {@code usedRecipeTracker}.
 *
 * <p>Farmer's Delight 1.3.2's {@code getRecipeUsed()} always returns
 * {@code null}, so the last-used recipe ids must be read from the internal
 * tracker instead. This accessor is only ever loaded (and applied) when the
 * {@code farmersdelight} mixin config is active.
 */
@Mixin(CookingPotBlockEntity.class)
public interface CookingPotBlockEntityAccessor {

    /**
     * @return the internal used-recipe tracker (never modified by this mod)
     */
    @Accessor("usedRecipeTracker")
    Object2IntOpenHashMap<ResourceLocation> tcth$getUsedRecipeTracker();
}
