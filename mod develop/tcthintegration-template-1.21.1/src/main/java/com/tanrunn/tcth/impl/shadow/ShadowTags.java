package com.tanrunn.tcth.impl.shadow;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;

/**
 * Single source of the shadow thief item / effect tags (stage 8A design).
 *
 * <p>The tags live in {@code data/tcth/tags/...} and are server-admin data:
 * <ul>
 *   <li>{@code #tcth:unstealable_items} — ITEM candidates are never drawn
 *       from stacks in this tag (admin blacklist; the code-side container
 *       component checks are a separate, unconditional layer);</li>
 *   <li>{@code #tcth:high_value_stealable_items} — reserved for the success
 *       layer's high-value modifier (phase 8C+);</li>
 *   <li>{@code #tcth:stealable_effects} — whitelist for EFFECT candidates
 *       (fail-closed: an empty whitelist yields no EFFECT candidates);</li>
 *   <li>{@code #tcth:unstealable_effects} — blacklist overlay on the
 *       whitelist (blacklist wins).</li>
 * </ul>
 */
public final class ShadowTags {

    public static final TagKey<Item> UNSTEALABLE_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("tcth", "unstealable_items"));
    public static final TagKey<Item> HIGH_VALUE_STEALABLE_ITEMS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("tcth", "high_value_stealable_items"));
    public static final TagKey<MobEffect> STEALABLE_EFFECTS =
            TagKey.create(Registries.MOB_EFFECT, ResourceLocation.fromNamespaceAndPath("tcth", "stealable_effects"));
    public static final TagKey<MobEffect> UNSTEALABLE_EFFECTS =
            TagKey.create(Registries.MOB_EFFECT, ResourceLocation.fromNamespaceAndPath("tcth", "unstealable_effects"));

    private ShadowTags() {
    }
}
