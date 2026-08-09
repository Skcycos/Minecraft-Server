package com.tanrunn.tcth.impl.compat.brewer.arc;

import com.daqem.arc.api.action.data.type.IActionDataType;
import com.daqem.arc.api.action.type.ActionType;
import com.daqem.arc.api.condition.type.ConditionType;
import com.daqem.arc.registry.ArcRegistry;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.brewer.arc.condition.BeverageTierCondition;
import com.tanrunn.tcth.impl.compat.brewer.arc.condition.BrewerRewardsEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/**
 * Registration of TCTH's brewer Arc action type and condition types
 * (phase 7C / 7C.1).
 *
 * <p>This registrar only adds:
 * <ul>
 *   <li>{@code tcth:on_beverage_prepared} — the custom {@link ActionType};</li>
 *   <li>condition types: {@code tcth:beverage_tier},
 *       {@code tcth:brewer_rewards_enabled}.</li>
 * </ul>
 *
 * <p>All six action data types ({@code result_item_id}, {@code count},
 * {@code recipe_id}, {@code device}, {@code tier}, {@code automated}) are the
 * <em>shared</em> objects registered once in {@link TcthArcRegistrar} — the
 * brewer side never re-registers them (7C.1: duplicate registration of the
 * same Arc data type is removed).
 *
 * <p>After registration, {@link #verifyRegistrations()} explicitly checks the
 * ids against Arc's registries and logs a DEBUG line per id. This class lives
 * in the {@code brewer} compat module and is loaded only when Jobs+ (hence
 * Arc) is installed.
 */
public final class BrewerArcRegistrar {

    // ---- action type (only new registration) ----
    public static final ActionType<BeveragePreparedAction> ON_BEVERAGE_PREPARED =
            ActionType.register(id("on_beverage_prepared"), new BeveragePreparedAction.Serializer());

    // ---- condition types (only new registrations) ----
    public static final ConditionType<BeverageTierCondition> BEVERAGE_TIER =
            ConditionType.register(id("beverage_tier"), new BeverageTierCondition.Serializer());
    public static final ConditionType<BrewerRewardsEnabledCondition> BREWER_REWARDS_ENABLED =
            ConditionType.register(id("brewer_rewards_enabled"), new BrewerRewardsEnabledCondition.Serializer());

    private BrewerArcRegistrar() {
    }

    /** Verifies the three new registrations; the six shared data types are
     * verified by {@link TcthArcRegistrar#verifyRegistrations()}. */
    public static void verifyRegistrations() {
        checkInRegistry(ArcRegistry.ACTION, id("on_beverage_prepared"), "action type");
        checkInRegistry(ArcRegistry.CONDITION, id("beverage_tier"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("brewer_rewards_enabled"), "condition type");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("tcth", path);
    }

    /**
     * Returns the SHARED data type object for the given name — the same
     * object registered once in {@link TcthArcRegistrar}. Used by tests to
     * assertSame against the brewer-side usage.
     */
    static IActionDataType<?> sharedDataType(String name) {
        return switch (name) {
            case "result_item_id" -> TcthArcRegistrar.RESULT_ITEM_ID;
            case "count" -> TcthArcRegistrar.COUNT;
            case "recipe_id" -> TcthArcRegistrar.RECIPE_ID;
            case "device" -> TcthArcRegistrar.DEVICE;
            case "tier" -> TcthArcRegistrar.TIER;
            case "automated" -> TcthArcRegistrar.AUTOMATED;
            default -> throw new IllegalArgumentException("Unknown shared data type: " + name);
        };
    }

    private static void checkInRegistry(Registry<?> registry, ResourceLocation id, String kind) {
        boolean present = registry.containsKey(id);
        TCTHIntegration.LOGGER.debug("[TCTH] brewer arc {} '{}' present={}", kind, id, present);
    }

}
