package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Base class for the four TCTH config-toggle conditions (phase 3D).
 *
 * <p>Each subclass gates one chef ability route: {@code tcth:chef_abilities_enabled}
 * (master), {@code tcth:tasting_effects_enabled}, {@code tcth:fire_resistance_enabled},
 * {@code tcth:knife_durability_enabled}. The conditions carry no JSON payload;
 * they read the config through an injectable supplier so unit tests can drive
 * them without a live mod-config container.
 *
 * <p>The master condition must read <em>both</em> the framework switch and the
 * {@code chefAbilitiesEnabled} switch; every route condition must read the
 * framework switch, the {@code chefAbilitiesEnabled} master switch AND its own
 * route switch, so {@code chefAbilitiesEnabled = false} stops all four routes
 * even though the study multipliers are driven by Arc data.
 *
 * <p><strong>Fail-closed:</strong> if the config supplier throws, the condition
 * evaluates to <em>disabled</em> (never enabled) so corrupt config can never
 * turn an ability on. Failures are logged at most once per
 * {@link #LOG_THROTTLE_NS} to avoid log spam on every action evaluation.
 */
abstract class RouteEnabledCondition extends AbstractCondition {

    /** Minimum interval between repeated config-failure warnings. */
    private static final long LOG_THROTTLE_NS = 5_000_000_000L; // 5 s

    private static final AtomicLong LAST_CONFIG_WARN_NANOS = new AtomicLong(0);

    private final BooleanSupplier enabledSupplier;

    RouteEnabledCondition(boolean inverted, BooleanSupplier enabledSupplier) {
        super(inverted);
        this.enabledSupplier = enabledSupplier;
    }

    @Override
    public boolean isMet(ActionData data) {
        boolean enabled;
        try {
            enabled = enabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            // Fail-closed: a broken config read must never enable an ability.
            warnThrottled(e);
            enabled = false;
        }
        return isInverted() != enabled;
    }

    private static void warnThrottled(Throwable e) {
        long now = System.nanoTime();
        long last = LAST_CONFIG_WARN_NANOS.get();
        if (now - last >= LOG_THROTTLE_NS && LAST_CONFIG_WARN_NANOS.compareAndSet(last, now)) {
            TCTHIntegration.LOGGER.warn("[TCTH] Chef ability toggle config read failed; abilities fail-closed (disabled): {}",
                    e.toString());
        }
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.route_enabled.desc", getName());
    }

    /**
     * Shared payload-less serializer for all four toggle conditions.
     */
    abstract static class Serializer<T extends RouteEnabledCondition> implements IConditionSerializer<T> {

        @Override
        public T fromJson(ResourceLocation location, com.google.gson.JsonObject json, boolean inverted) {
            return create(inverted);
        }

        @Override
        public T fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return create(inverted);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, T condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
        }

        protected abstract T create(boolean inverted);
    }
}
