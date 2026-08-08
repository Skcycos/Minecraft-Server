package com.tanrunn.tcth.mixin.dungeonsdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotBlockEntity;

/**
 * Accessor for {@link MonsterPotBlockEntity#usedRecipeTracker}.
 *
 * <p>Loaded only via {@code dungeonsdelight_compat.mixins.json}
 * ({@code requiredMods=["dungeonsdelight"]}).
 */
@Mixin(MonsterPotBlockEntity.class)
public interface MonsterPotBlockEntityAccessor {

    @Accessor("usedRecipeTracker")
    Object2IntOpenHashMap<ResourceLocation> tcth$getUsedRecipeTracker();
}
