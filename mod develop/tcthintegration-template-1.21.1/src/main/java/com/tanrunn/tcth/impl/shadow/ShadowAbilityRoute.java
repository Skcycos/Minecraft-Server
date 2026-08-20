package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * The four {@code tcth:shadow_thief} ability routes (phase 8E).
 *
 * <p>Each route is a strict chain of three powerup nodes. Only the highest
 * <em>active</em> node of a route applies; lower nodes are excluded. The four
 * routes are independent and may all be active at the same time. The node ids
 * are fixed by the phase contract and must never be renamed.
 */
public enum ShadowAbilityRoute {

    /** 妙手路线 — sleight_of_hand_i → sleight_of_hand_ii → sleight_of_hand_iii. */
    SLEIGHT("sleight_of_hand_i", "sleight_of_hand_ii", "sleight_of_hand_iii"),

    /** 夺生路线 — life_siphon_i → life_siphon_ii → life_siphon_iii. */
    LIFE_SIPHON("life_siphon_i", "life_siphon_ii", "life_siphon_iii"),

    /** 窃法路线 — spell_theft_i → spell_theft_ii → spell_theft_iii. */
    SPELL_THEFT("spell_theft_i", "spell_theft_ii", "spell_theft_iii"),

    /** 潜影路线 — shadow_escape_i → shadow_escape_ii → shadow_escape_iii. */
    SHADOW_ESCAPE("shadow_escape_i", "shadow_escape_ii", "shadow_escape_iii");

    private final String nodeI;
    private final String nodeII;
    private final String nodeIII;

    ShadowAbilityRoute(String nodeI, String nodeII, String nodeIII) {
        this.nodeI = nodeI;
        this.nodeII = nodeII;
        this.nodeIII = nodeIII;
    }

    /** @return the id of the first (lowest) node of the route */
    public String nodeI() {
        return nodeI;
    }

    /** @return the id of the second node of the route */
    public String nodeII() {
        return nodeII;
    }

    /** @return the id of the third (highest) node of the route */
    public String nodeIII() {
        return nodeIII;
    }

    /** @return the id of the node at the given tier (or {@code null} for
     *          {@link ShadowAbilityTier#NONE}) */
    public String nodeId(ShadowAbilityTier tier) {
        Objects.requireNonNull(tier, "tier");
        return switch (tier) {
            case I -> nodeI;
            case II -> nodeII;
            case III -> nodeIII;
            case NONE -> null;
        };
    }

    /**
     * Full powerup location of a route node, e.g.
     * {@code tcth:shadow_thief/sleight_of_hand_i}.
     */
    public ResourceLocation nodeLocation(String node) {
        Objects.requireNonNull(node, "node");
        return ResourceLocation.fromNamespaceAndPath("tcth", "shadow_thief/" + node);
    }
}
