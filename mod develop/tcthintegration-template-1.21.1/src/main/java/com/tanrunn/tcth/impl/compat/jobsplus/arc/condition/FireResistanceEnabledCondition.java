package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:fire_resistance_enabled} — hearth-route toggle
 * (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:fire_resistance_enabled" }
 * }</pre>
 *
 * <p>Reads {@code enabled} and {@code fireResistanceAbilitiesEnabled}.
 */
public class FireResistanceEnabledCondition extends RouteEnabledCondition {

    private static BooleanSupplier masterSupplier = FireResistanceEnabledCondition::defaultMaster;
    private static BooleanSupplier routeSupplier = FireResistanceEnabledCondition::defaultRoute;

    public FireResistanceEnabledCondition(boolean inverted) {
        super(inverted, FireResistanceEnabledCondition::defaultEnabled);
    }

    private static boolean defaultMaster() {
        return Config.ENABLED.get() && Config.CHEF_ABILITIES_ENABLED.get();
    }

    private static boolean defaultRoute() {
        return Config.FIRE_RESISTANCE_ABILITIES_ENABLED.get();
    }

    private static boolean defaultEnabled() {
        return masterSupplier.getAsBoolean() && routeSupplier.getAsBoolean();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.FIRE_RESISTANCE_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.fire_resistance_enabled.name");
    }

    public static final class Serializer extends RouteEnabledCondition.Serializer<FireResistanceEnabledCondition> {

        @Override
        protected FireResistanceEnabledCondition create(boolean inverted) {
            return new FireResistanceEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    static void setMasterSupplierForTesting(BooleanSupplier supplier) {
        masterSupplier = supplier;
    }

    static void setRouteSupplierForTesting(BooleanSupplier supplier) {
        routeSupplier = supplier;
    }

    static void resetForTesting() {
        masterSupplier = FireResistanceEnabledCondition::defaultMaster;
        routeSupplier = FireResistanceEnabledCondition::defaultRoute;
    }
}
