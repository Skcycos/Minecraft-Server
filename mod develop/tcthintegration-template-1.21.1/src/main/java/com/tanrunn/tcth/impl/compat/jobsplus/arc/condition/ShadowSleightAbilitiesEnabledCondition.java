package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:shadow_sleight_abilities_enabled} — the 妙手
 * route switch (phase 8E).
 */
public class ShadowSleightAbilitiesEnabledCondition extends ShadowRouteEnabledCondition {

    public static BooleanSupplier routeEnabledSupplier = Config.SHADOW_SLEIGHT_ABILITIES_ENABLED::get;

    public ShadowSleightAbilitiesEnabledCondition(boolean inverted) {
        super(inverted, routeEnabledSupplier);
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.SHADOW_SLEIGHT_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.shadow_sleight_abilities_enabled.name");
    }

    public static final class Serializer
            extends ShadowRouteEnabledCondition.Serializer<ShadowSleightAbilitiesEnabledCondition> {

        @Override
        protected ShadowSleightAbilitiesEnabledCondition create(boolean inverted) {
            return new ShadowSleightAbilitiesEnabledCondition(inverted);
        }
    }

    public static void resetSuppliersForTesting() {
        routeEnabledSupplier = Config.SHADOW_SLEIGHT_ABILITIES_ENABLED::get;
    }
}
