package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.function.BooleanSupplier;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.shadow.ShadowLogThrottle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Base of the four {@code tcth:shadow_*_abilities_enabled} route conditions
 * (phase 8E) — the data-driven mirrors of the shadow thief ability-route
 * switches, attached to the twelve per-node preset actions.
 *
 * <p>Matches only when <em>all</em> of these are true: TCTH framework switch
 * ({@code Config.ENABLED}), shadow thief integration switch, the
 * {@code shadowAbilitiesEnabled} master switch AND the route's own switch.
 *
 * <p><strong>Fail-closed, unconditionally:</strong> a {@link RuntimeException}
 * or {@link LinkageError} while reading the config makes the condition return
 * {@code false} directly — never flipped by {@code inverted}. Repeated
 * failures are logged at most once per 60 s via {@link ShadowLogThrottle}.
 */
abstract class ShadowRouteEnabledCondition extends AbstractCondition {

    /** Base switch suppliers, injectable for tests; production reads the
     *  real config. */
    public static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    public static BooleanSupplier integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
    public static BooleanSupplier abilitiesMasterSupplier = Config.SHADOW_ABILITIES_ENABLED::get;

    private final BooleanSupplier routeEnabledSupplier;

    ShadowRouteEnabledCondition(boolean inverted, BooleanSupplier routeEnabledSupplier) {
        super(inverted);
        this.routeEnabledSupplier = routeEnabledSupplier;
    }

    @Override
    public boolean isMet(ActionData data) {
        try {
            boolean matches = frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && abilitiesMasterSupplier.getAsBoolean()
                    && routeEnabledSupplier.getAsBoolean();
            return isInverted() != matches;
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] {} config read failed; shadow abilities fail-closed (disabled): {}",
                    getType().getLocation(), e.toString());
            return false;
        }
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.route_enabled.desc", getName());
    }

    /** Test hook: restores the three base suppliers. */
    static void resetBaseSuppliersForTesting() {
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.SHADOW_ABILITIES_ENABLED::get;
    }

    /**
     * Shared payload-less serializer for the four shadow route conditions.
     */
    abstract static class Serializer<T extends ShadowRouteEnabledCondition>
            implements IConditionSerializer<T> {

        @Override
        public T fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return create(inverted);
        }

        @Override
        public T fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return create(inverted);
        }

        protected abstract T create(boolean inverted);
    }
}
