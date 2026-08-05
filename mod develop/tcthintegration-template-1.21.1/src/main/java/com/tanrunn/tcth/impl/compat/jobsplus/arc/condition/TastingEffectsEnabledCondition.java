package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:tasting_effects_enabled} — tasting-route toggle
 * (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:tasting_effects_enabled" }
 * }</pre>
 *
 * <p>Reads {@code enabled} and {@code tastingEffectsEnabled}. Attached to the
 * three tasting Arc actions so the route can be disabled without removing
 * purchased nodes.
 */
public class TastingEffectsEnabledCondition extends RouteEnabledCondition {

    private static BooleanSupplier masterSupplier = TastingEffectsEnabledCondition::defaultMaster;
    private static BooleanSupplier routeSupplier = TastingEffectsEnabledCondition::defaultRoute;

    public TastingEffectsEnabledCondition(boolean inverted) {
        super(inverted, TastingEffectsEnabledCondition::defaultEnabled);
    }

    private static boolean defaultMaster() {
        return Config.ENABLED.get() && Config.CHEF_ABILITIES_ENABLED.get();
    }

    private static boolean defaultRoute() {
        return Config.TASTING_EFFECTS_ENABLED.get();
    }

    private static boolean defaultEnabled() {
        return masterSupplier.getAsBoolean() && routeSupplier.getAsBoolean();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.TASTING_EFFECTS_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.tasting_effects_enabled.name");
    }

    public static final class Serializer extends RouteEnabledCondition.Serializer<TastingEffectsEnabledCondition> {

        @Override
        protected TastingEffectsEnabledCondition create(boolean inverted) {
            return new TastingEffectsEnabledCondition(inverted);
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
        masterSupplier = TastingEffectsEnabledCondition::defaultMaster;
        routeSupplier = TastingEffectsEnabledCondition::defaultRoute;
    }
}
