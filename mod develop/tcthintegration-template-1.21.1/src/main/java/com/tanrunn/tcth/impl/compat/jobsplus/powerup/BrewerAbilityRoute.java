package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;

/**
 * The four tcth:brewer ability routes (phase 7E).
 *
 * <p>Each route is a strict chain of three powerup nodes. Only the highest
 * <em>active</em> node of a route applies; lower nodes are excluded. The four
 * routes are independent and may all be active at the same time.
 */
public enum BrewerAbilityRoute {

    /** 调饮路线 — brewing_basic → brewing_adept → brewing_expert. */
    BREWING("brewing_basic", "brewing_adept", "brewing_expert"),

    /** 品鉴路线 — tasting_basic → tasting_adept → tasting_expert. */
    TASTING("tasting_basic", "tasting_adept", "tasting_expert"),

    /** 魔酿耐受路线 — resistance_basic → resistance_adept → resistance_expert. */
    RESISTANCE("resistance_basic", "resistance_adept", "resistance_expert"),

    /** 研修路线 — study_i → study_ii → study_iii. */
    STUDY("study_i", "study_ii", "study_iii");

    private final String nodeI;
    private final String nodeII;
    private final String nodeIII;

    BrewerAbilityRoute(String nodeI, String nodeII, String nodeIII) {
        this.nodeI = nodeI;
        this.nodeII = nodeII;
        this.nodeIII = nodeIII;
    }

    public String nodeI() {
        return nodeI;
    }

    public String nodeII() {
        return nodeII;
    }

    public String nodeIII() {
        return nodeIII;
    }

    /**
     * Full powerup location of a route node, e.g. {@code tcth:brewer/brewing_basic}.
     */
    public ResourceLocation nodeLocation(String node) {
        return ResourceLocation.fromNamespaceAndPath("tcth", "brewer/" + node);
    }
}
