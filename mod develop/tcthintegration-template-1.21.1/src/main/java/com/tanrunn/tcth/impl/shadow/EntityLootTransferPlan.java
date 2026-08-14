package com.tanrunn.tcth.impl.shadow;

import net.minecraft.resources.ResourceLocation;

/**
 * Concrete entity-loot transfer plan (8D.1 §5) — deliberately independent of
 * the player-victim {@link ItemPlan}: it carries only the looted item id and
 * count, and the delivery is a single full stack into the thief's inventory.
 */
public record EntityLootTransferPlan(ResourceLocation itemId, int count) {

    public EntityLootTransferPlan {
        if (itemId == null || count <= 0 || count > 64) {
            throw new IllegalArgumentException("invalid entity loot plan");
        }
    }

    public static EntityLootTransferPlan from(ShadowLootDefinition.ShadowLootEntry entry, int count) {
        return new EntityLootTransferPlan(entry.itemId(), count);
    }
}
