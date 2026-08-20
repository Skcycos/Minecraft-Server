package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:shadow_life_siphon_abilities_enabled} — the 夺生
 * route switch (phase 8E).
 */
public class ShadowLifeSiphonAbilitiesEnabledCondition extends ShadowRouteEnabledCondition {

    public static BooleanSupplier routeEnabledSupplier = Config.SHADOW_LIFE_SIPHON_ABILITIES_ENABLED::get;

    public ShadowLifeSiphonAbilitiesEnabledCondition(boolean inverted) {
        super(inverted, routeEnabledSupplier);
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.SHADOW_LIFE_SIPHON_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.shadow_life_siphon_abilities_enabled.name");
    }

    public static final class Serializer
            extends ShadowRouteEnabledCondition.Serializer<ShadowLifeSiphonAbilitiesEnabledCondition> {

        @Override
        protected ShadowLifeSiphonAbilitiesEnabledCondition create(boolean inverted) {
            return new ShadowLifeSiphonAbilitiesEnabledCondition(inverted);
        }
    }

    public static void resetSuppliersForTesting() {
        routeEnabledSupplier = Config.SHADOW_LIFE_SIPHON_ABILITIES_ENABLED::get;
    }
}
