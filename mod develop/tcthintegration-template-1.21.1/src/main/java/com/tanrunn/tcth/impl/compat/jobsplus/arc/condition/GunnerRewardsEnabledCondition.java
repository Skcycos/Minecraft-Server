package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * TCTH condition {@code tcth:gunner_rewards_enabled} — the data-driven mirror
 * of the gunner reward pipeline switches (phase 5A.1).
 *
 * <p>Matches only when <em>all</em> of the following are true:
 * <ul>
 *   <li>TCTH framework switch {@code Config.ENABLED};</li>
 *   <li>gunner integration switch {@code Config.GUNNER_INTEGRATION_ENABLED};</li>
 *   <li>gunner reward switch {@code Config.GUNNER_REWARDS_ENABLED}.</li>
 * </ul>
 *
 * <p>Any {@link RuntimeException} or {@link LinkageError} while reading the
 * config fails the condition closed (no match). Repeated config failures are
 * logged at WARN with a throttled rate so an attack or broken config cannot
 * spam the log on every action.
 */
public class GunnerRewardsEnabledCondition extends AbstractCondition {

    /** Throttle window for repeated config-failure WARNs (millis). */
    private static final long WARN_THROTTLE_MS = 60_000L;
    private static long lastWarnAt = 0L;

    /** Switch suppliers, injectable for tests; production reads the config. */
    public static java.util.function.BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    public static java.util.function.BooleanSupplier integrationEnabledSupplier =
            () -> Config.GUNNER_INTEGRATION_ENABLED.get();
    public static java.util.function.BooleanSupplier rewardsEnabledSupplier =
            () -> Config.GUNNER_REWARDS_ENABLED.get();

    public GunnerRewardsEnabledCondition(boolean inverted) {
        super(inverted);
    }

    @Override
    public boolean isMet(ActionData data) {
        try {
            boolean matches = frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && rewardsEnabledSupplier.getAsBoolean();
            return isInverted() != matches;
        } catch (RuntimeException | LinkageError e) {
            // Config read failure: fail-closed — the condition NEVER matches,
            // regardless of inverted (an inverted "rewards disabled" gate must
            // not grant rewards when the config is broken). Log throttled.
            warnThrottled("[TCTH] tcth:gunner_rewards_enabled config read failed: {}",
                    e.toString());
            return false;
        }
    }

    private static void warnThrottled(String message, Object arg) {
        long now = System.currentTimeMillis();
        synchronized (GunnerRewardsEnabledCondition.class) {
            if (now - lastWarnAt < WARN_THROTTLE_MS) {
                return;
            }
            lastWarnAt = now;
        }
        TCTHIntegration.LOGGER.warn(message, arg);
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.GUNNER_REWARDS_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.gunner_rewards_enabled.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.gunner_rewards_enabled.desc");
    }

    public static final class Serializer implements IConditionSerializer<GunnerRewardsEnabledCondition> {

        @Override
        public GunnerRewardsEnabledCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new GunnerRewardsEnabledCondition(inverted);
        }

        @Override
        public GunnerRewardsEnabledCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new GunnerRewardsEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    public static void resetThrottleForTesting() {
        synchronized (GunnerRewardsEnabledCondition.class) {
            lastWarnAt = 0L;
        }
    }

    public static void resetSuppliersForTesting() {
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        integrationEnabledSupplier = () -> Config.GUNNER_INTEGRATION_ENABLED.get();
        rewardsEnabledSupplier = () -> Config.GUNNER_REWARDS_ENABLED.get();
    }
}
