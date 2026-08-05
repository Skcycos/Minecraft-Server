package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The four parallel chef ability routes of the tcth:chef job (phase 3D).
 *
 * <p>Each route is a chain of three powerup nodes. Only the highest
 * <em>active</em> node of a route applies; lower nodes are excluded. The four
 * routes are independent and may all be active at the same time.
 */
public enum ChefAbilityRoute {

    /** 刀工路线 — knife_beginner → knife_adept → knife_expert. */
    KNIFE("knife_basic", "knife_adept", "knife_expert"),

    /** 炉火路线 — hearth_basic → hearth_master → hearth_expert. */
    HEARTH("hearth_basic", "hearth_master", "hearth_expert"),

    /** 品鉴路线 — tasting_basic → tasting_nourishing → tasting_feast. */
    TASTING("tasting_basic", "tasting_nourishing", "tasting_feast"),

    /** 研修路线 — culinary_experience_i → culinary_experience_ii → culinary_experience_iii. */
    STUDY("culinary_experience_i", "culinary_experience_ii", "culinary_experience_iii");

    private final String nodeI;
    private final String nodeII;
    private final String nodeIII;

    ChefAbilityRoute(String nodeI, String nodeII, String nodeIII) {
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
     * Full powerup location of a route node, e.g. {@code tcth:chef/knife_adept}.
     */
    public ResourceLocation nodeLocation(String node) {
        return ResourceLocation.fromNamespaceAndPath("tcth", "chef/" + node);
    }
}
