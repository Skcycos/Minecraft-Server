package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:knife_durability_enabled} — knife-route toggle
 * (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:knife_durability_enabled" }
 * }</pre>
 *
 * <p>Reads {@code enabled} and {@code knifeDurabilityAbilitiesEnabled}.
 *
 * @deprecated since 4C — kept registered for data compatibility only. The
 * knife route is Java-driven via {@code ItemStackDurabilityMixin}
 * ({@code arc:on_hurt_item} never fires on NeoForge 21.1.247); no current
 * datapack action references this condition. Do not use in new data.
 */
@Deprecated
public class KnifeDurabilityEnabledCondition extends RouteEnabledCondition {

    private static BooleanSupplier masterSupplier = KnifeDurabilityEnabledCondition::defaultMaster;
    private static BooleanSupplier routeSupplier = KnifeDurabilityEnabledCondition::defaultRoute;

    public KnifeDurabilityEnabledCondition(boolean inverted) {
        super(inverted, KnifeDurabilityEnabledCondition::defaultEnabled);
    }

    private static boolean defaultMaster() {
        return Config.ENABLED.get() && Config.CHEF_ABILITIES_ENABLED.get();
    }

    private static boolean defaultRoute() {
        return Config.KNIFE_DURABILITY_ABILITIES_ENABLED.get();
    }

    private static boolean defaultEnabled() {
        return masterSupplier.getAsBoolean() && routeSupplier.getAsBoolean();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.KNIFE_DURABILITY_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.knife_durability_enabled.name");
    }

    public static final class Serializer extends RouteEnabledCondition.Serializer<KnifeDurabilityEnabledCondition> {

        @Override
        protected KnifeDurabilityEnabledCondition create(boolean inverted) {
            return new KnifeDurabilityEnabledCondition(inverted);
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
        masterSupplier = KnifeDurabilityEnabledCondition::defaultMaster;
        routeSupplier = KnifeDurabilityEnabledCondition::defaultRoute;
    }
}
