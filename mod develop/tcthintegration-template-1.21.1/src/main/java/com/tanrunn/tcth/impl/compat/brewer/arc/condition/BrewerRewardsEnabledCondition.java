package com.tanrunn.tcth.impl.compat.brewer.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.brewer.arc.BrewerArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * TCTH condition {@code tcth:brewer_rewards_enabled} — the data-driven mirror
 * of the brewer reward pipeline switches (phase 7C).
 *
 * <p>Matches only when <em>all</em> of the following are true:
 * <ul>
 *   <li>TCTH framework switch {@code Config.ENABLED};</li>
 *   <li>brewer integration switch {@code Config.BREWER_INTEGRATION_ENABLED};</li>
 *   <li>brewer reward switch {@code Config.BREWER_REWARDS_ENABLED}.</li>
 * </ul>
 *
 * <p>Any {@link RuntimeException} or {@link LinkageError} while reading the
 * config fails the condition closed (no match), regardless of {@code inverted}
 * — an inverted "rewards disabled" gate must never grant rewards when the
 * config is broken.
 */
public class BrewerRewardsEnabledCondition extends AbstractCondition {

    /** Switch suppliers, injectable for tests; production reads the config. */
    public static java.util.function.BooleanSupplier frameworkEnabledSupplier =
            () -> Config.ENABLED.get();
    public static java.util.function.BooleanSupplier integrationEnabledSupplier =
            () -> Config.BREWER_INTEGRATION_ENABLED.get();
    public static java.util.function.BooleanSupplier rewardsEnabledSupplier =
            () -> Config.BREWER_REWARDS_ENABLED.get();

    public BrewerRewardsEnabledCondition(boolean inverted) {
        super(inverted);
    }

    /** Throttle window for repeated config-failure WARNs (millis). */
    private static final long WARN_THROTTLE_MS = 60_000L;
    private static long lastWarnAt = 0L;

    @Override
    public boolean isMet(ActionData data) {
        try {
            boolean matches = frameworkEnabledSupplier.getAsBoolean()
                    && integrationEnabledSupplier.getAsBoolean()
                    && rewardsEnabledSupplier.getAsBoolean();
            return isInverted() != matches;
        } catch (RuntimeException | LinkageError e) {
            // Config read failure: fail-closed — the condition NEVER matches,
            // regardless of inverted. Log at WARN with a 60s throttle so a
            // broken config cannot spam the log on every action.
            warnThrottled("[TCTH] tcth:brewer_rewards_enabled config read failed: {}", e.toString());
            return false;
        }
    }

    private static void warnThrottled(String message, Object arg) {
        long now = System.currentTimeMillis();
        synchronized (BrewerRewardsEnabledCondition.class) {
            if (now - lastWarnAt < WARN_THROTTLE_MS) {
                return;
            }
            lastWarnAt = now;
        }
        TCTHIntegration.LOGGER.warn(message, arg);
    }

    /** Test hook: clears the throttle state. */
    public static void resetThrottleForTesting() {
        synchronized (BrewerRewardsEnabledCondition.class) {
            lastWarnAt = 0L;
        }
    }

    @Override
    public IConditionType<?> getType() {
        return BrewerArcRegistrar.BREWER_REWARDS_ENABLED;
    }

    @Override
    public Component getDescription() {
        return Component.literal("brewer rewards enabled");
    }

    public static final class Serializer implements IConditionSerializer<BrewerRewardsEnabledCondition> {

        @Override
        public BrewerRewardsEnabledCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new BrewerRewardsEnabledCondition(inverted);
        }

        @Override
        public BrewerRewardsEnabledCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new BrewerRewardsEnabledCondition(inverted);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, BrewerRewardsEnabledCondition condition) {
        }
    }
}
