package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:shadow_spell_theft_abilities_enabled} — the 窃法
 * route switch (phase 8E).
 */
public class ShadowSpellTheftAbilitiesEnabledCondition extends ShadowRouteEnabledCondition {

    public static BooleanSupplier routeEnabledSupplier = Config.SHADOW_SPELL_THEFT_ABILITIES_ENABLED::get;

    public ShadowSpellTheftAbilitiesEnabledCondition(boolean inverted) {
        super(inverted, routeEnabledSupplier);
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.SHADOW_SPELL_THEFT_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.shadow_spell_theft_abilities_enabled.name");
    }

    public static final class Serializer
            extends ShadowRouteEnabledCondition.Serializer<ShadowSpellTheftAbilitiesEnabledCondition> {

        @Override
        protected ShadowSpellTheftAbilitiesEnabledCondition create(boolean inverted) {
            return new ShadowSpellTheftAbilitiesEnabledCondition(inverted);
        }
    }

    public static void resetSuppliersForTesting() {
        routeEnabledSupplier = Config.SHADOW_SPELL_THEFT_ABILITIES_ENABLED::get;
    }
}
