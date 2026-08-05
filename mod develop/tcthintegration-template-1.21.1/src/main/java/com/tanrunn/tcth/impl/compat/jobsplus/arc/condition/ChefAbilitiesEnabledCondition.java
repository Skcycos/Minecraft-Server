package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:chef_abilities_enabled} — the chef ability tree
 * master switch (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:chef_abilities_enabled" }
 * }</pre>
 *
 * <p>Reads {@code enabled} and {@code chefAbilitiesEnabled}. Attached to the
 * study-route Arc actions (whose multipliers are otherwise purely
 * Arc-data-driven) so that the master switch can actually stop them.
 */
public class ChefAbilitiesEnabledCondition extends RouteEnabledCondition {

    private static BooleanSupplier masterSupplier = ChefAbilitiesEnabledCondition::defaultMaster;

    public ChefAbilitiesEnabledCondition(boolean inverted) {
        super(inverted, ChefAbilitiesEnabledCondition::defaultEnabled);
    }

    private static boolean defaultMaster() {
        return Config.ENABLED.get() && Config.CHEF_ABILITIES_ENABLED.get();
    }

    private static boolean defaultEnabled() {
        return masterSupplier.getAsBoolean();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.CHEF_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.chef_abilities_enabled.name");
    }

    public static final class Serializer extends RouteEnabledCondition.Serializer<ChefAbilitiesEnabledCondition> {

        @Override
        protected ChefAbilitiesEnabledCondition create(boolean inverted) {
            return new ChefAbilitiesEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    static void setMasterSupplierForTesting(BooleanSupplier supplier) {
        masterSupplier = supplier;
    }

    static void resetForTesting() {
        masterSupplier = ChefAbilitiesEnabledCondition::defaultMaster;
    }
}
