package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import net.minecraft.resources.ResourceLocation;

/**
 * The four tcth:gunner ability routes (phase 5B).
 *
 * <p>Each route is a strict chain of three powerup nodes. Only the highest
 * <em>active</em> node of a route applies; lower nodes are excluded. The four
 * routes are independent and may all be active at the same time.
 */
public enum GunnerAbilityRoute {

    /** 枪术路线 — marksmanship_basic → marksmanship_adept → marksmanship_expert. */
    MARKSMANSHIP("marksmanship_basic", "marksmanship_adept", "marksmanship_expert"),

    /** 弹药管理路线 — ammo_saver_basic → ammo_saver_adept → ammo_saver_expert. */
    AMMO_SAVER("ammo_saver_basic", "ammo_saver_adept", "ammo_saver_expert"),

    /** 战地防护路线 — battlefield_defense_basic → _adept → _expert. */
    DEFENSE("battlefield_defense_basic", "battlefield_defense_adept", "battlefield_defense_expert"),

    /** 实战研修路线 — gunner_experience_i → gunner_experience_ii → gunner_experience_iii. */
    STUDY("gunner_experience_i", "gunner_experience_ii", "gunner_experience_iii");

    private final String nodeI;
    private final String nodeII;
    private final String nodeIII;

    GunnerAbilityRoute(String nodeI, String nodeII, String nodeIII) {
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
     * Full powerup location of a route node, e.g. {@code tcth:gunner/marksmanship_basic}.
     */
    public ResourceLocation nodeLocation(String node) {
        return ResourceLocation.fromNamespaceAndPath("tcth", "gunner/" + node);
    }
}
