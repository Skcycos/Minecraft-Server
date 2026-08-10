package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.chat.Component;

/**
 * TCTH condition {@code tcth:farmer_livestock_abilities_enabled} —
 * livestock-route toggle (phase 4B).
 *
 * <pre>{@code
 * { "type": "tcth:farmer_livestock_abilities_enabled" }
 * }</pre>
 *
 * <p>Reads {@code enabled}, {@code farmerIntegrationEnabled},
 * {@code farmerAbilitiesEnabled} and {@code farmerLivestockAbilitiesEnabled}.
 *
 * <p><strong>Fail-closed, unconditionally:</strong> a config-read failure
 * makes the condition return {@code false} directly — never flipped by
 * {@code inverted}, so a broken config can never grant livestock effects.
 */
public class FarmerLivestockAbilitiesEnabledCondition extends RouteEnabledCondition {

    private static final long WARN_THROTTLE_NS = 60_000_000_000L; // 60 s
    private static final AtomicLong LAST_WARN_NANOS = new AtomicLong(0);

    private static BooleanSupplier frameworkSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultFramework;
    private static BooleanSupplier integrationSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultIntegration;
    private static BooleanSupplier masterSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultMaster;
    private static BooleanSupplier routeSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultRoute;

    public FarmerLivestockAbilitiesEnabledCondition(boolean inverted) {
        super(inverted, FarmerLivestockAbilitiesEnabledCondition::defaultEnabled);
    }

    @Override
    public boolean isMet(ActionData data) {
        try {
            boolean matches = frameworkSupplier.getAsBoolean()
                    && integrationSupplier.getAsBoolean()
                    && masterSupplier.getAsBoolean()
                    && routeSupplier.getAsBoolean();
            return isInverted() != matches;
        } catch (RuntimeException | LinkageError e) {
            warnThrottled(e);
            return false; // fail-closed, regardless of inverted
        }
    }

    private static void warnThrottled(Throwable e) {
        long now = System.nanoTime();
        long last = LAST_WARN_NANOS.get();
        if (now - last >= WARN_THROTTLE_NS && LAST_WARN_NANOS.compareAndSet(last, now)) {
            TCTHIntegration.LOGGER.warn("[TCTH] Livestock toggle config read failed; abilities fail-closed (disabled): {}",
                    e.toString());
        }
    }

    private static boolean defaultFramework() {
        return Config.ENABLED.get();
    }

    private static boolean defaultIntegration() {
        return Config.FARMER_INTEGRATION_ENABLED.get();
    }

    private static boolean defaultMaster() {
        return Config.FARMER_ABILITIES_ENABLED.get();
    }

    private static boolean defaultRoute() {
        return Config.FARMER_LIVESTOCK_ABILITIES_ENABLED.get();
    }

    private static boolean defaultEnabled() {
        return frameworkSupplier.getAsBoolean()
                && integrationSupplier.getAsBoolean()
                && masterSupplier.getAsBoolean()
                && routeSupplier.getAsBoolean();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.FARMER_LIVESTOCK_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.farmer_livestock_abilities_enabled.name");
    }

    public static final class Serializer extends RouteEnabledCondition.Serializer<FarmerLivestockAbilitiesEnabledCondition> {

        @Override
        protected FarmerLivestockAbilitiesEnabledCondition create(boolean inverted) {
            return new FarmerLivestockAbilitiesEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    static void setFrameworkSupplierForTesting(BooleanSupplier supplier) {
        frameworkSupplier = supplier;
    }

    static void setIntegrationSupplierForTesting(BooleanSupplier supplier) {
        integrationSupplier = supplier;
    }

    static void setMasterSupplierForTesting(BooleanSupplier supplier) {
        masterSupplier = supplier;
    }

    static void setRouteSupplierForTesting(BooleanSupplier supplier) {
        routeSupplier = supplier;
    }

    static void resetForTesting() {
        frameworkSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultFramework;
        integrationSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultIntegration;
        masterSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultMaster;
        routeSupplier = FarmerLivestockAbilitiesEnabledCondition::defaultRoute;
        LAST_WARN_NANOS.set(0L);
    }
}
