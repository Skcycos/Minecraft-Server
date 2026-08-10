package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;

/**
 * The four tcth:farmer ability routes (phase 4B).
 *
 * <p>Each route is a strict chain of three powerup nodes. Only the highest
 * <em>active</em> node of a route applies; lower nodes are excluded. The four
 * routes are independent and may all be active at the same time.
 */
public enum FarmerAbilityRoute {

    /** 耕作路线 — tilling_basic → tilling_adept → tilling_expert. */
    TILLING("tilling_basic", "tilling_adept", "tilling_expert"),

    /** 丰收路线 — harvest_basic → harvest_adept → harvest_expert. */
    HARVEST("harvest_basic", "harvest_adept", "harvest_expert"),

    /** 畜牧路线 — livestock_basic → livestock_adept → livestock_expert. */
    LIVESTOCK("livestock_basic", "livestock_adept", "livestock_expert"),

    /** 研修路线 — study_i → study_ii → study_iii. */
    STUDY("study_i", "study_ii", "study_iii");

    private final String nodeI;
    private final String nodeII;
    private final String nodeIII;

    FarmerAbilityRoute(String nodeI, String nodeII, String nodeIII) {
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
     * Full powerup location of a route node, e.g. {@code tcth:farmer/tilling_basic}.
     */
    public ResourceLocation nodeLocation(String node) {
        return ResourceLocation.fromNamespaceAndPath("tcth", "farmer/" + node);
    }
}
