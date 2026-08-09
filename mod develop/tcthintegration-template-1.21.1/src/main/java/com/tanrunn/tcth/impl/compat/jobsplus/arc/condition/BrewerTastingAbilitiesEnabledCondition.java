package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * TCTH condition {@code tcth:brewer_tasting_abilities_enabled} — the brewer
 * tasting-route switch (phase 7E).
 *
 * <pre>{@code
 * { "type": "tcth:brewer_tasting_abilities_enabled" }
 * }</pre>
 *
 * <p>Matches only when TCTH framework switch, brewer integration switch,
 * brewer-abilities master switch and the tasting-route switch are all true.
 * Attached to the three tasting-route Arc actions
 * ({@code arc:on_drink} + {@code tcth:tasting_effects}) so the config switches
 * can actually stop them.
 *
 * <p><strong>Fail-closed, unconditionally:</strong> a {@link RuntimeException}
 * or {@link LinkageError} while reading the config makes the condition return
 * {@code false} directly — never flipped by {@code inverted}, so a broken
 * config can never grant tasting effects. Repeated failures are logged at most
 * once per {@link #WARN_THROTTLE_NS} window.
 */
public class BrewerTastingAbilitiesEnabledCondition extends AbstractCondition {

    private static final long WARN_THROTTLE_NS = 5_000_000_000L; // 5 s
    private static final AtomicLong LAST_WARN_NANOS = new AtomicLong(0);

    /** Config suppliers, injectable for tests. */
    public static BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    public static BooleanSupplier integrationEnabledSupplier = Config.BREWER_INTEGRATION_ENABLED::get;
    public static BooleanSupplier abilitiesMasterSupplier = Config.BREWER_ABILITIES_ENABLED::get;
    public static BooleanSupplier routeEnabledSupplier = Config.BREWER_TASTING_ABILITIES_ENABLED::get;

    public BrewerTastingAbilitiesEnabledCondition(boolean inverted) {
        super(inverted);
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
            warnThrottled(e);
            return false; // fail-closed, regardless of inverted
        }
    }

    private static void warnThrottled(Throwable e) {
        long now = System.nanoTime();
        long last = LAST_WARN_NANOS.get();
        if (now - last >= WARN_THROTTLE_NS && LAST_WARN_NANOS.compareAndSet(last, now)) {
            TCTHIntegration.LOGGER.warn(
                    "[TCTH] brewer_tasting_abilities_enabled config read failed; fail-closed (disabled): {}",
                    e.toString());
        }
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.BREWER_TASTING_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.brewer_tasting_abilities_enabled.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.brewer_tasting_abilities_enabled.desc");
    }

    public static final class Serializer implements IConditionSerializer<BrewerTastingAbilitiesEnabledCondition> {

        @Override
        public BrewerTastingAbilitiesEnabledCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new BrewerTastingAbilitiesEnabledCondition(inverted);
        }

        @Override
        public BrewerTastingAbilitiesEnabledCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new BrewerTastingAbilitiesEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    public static void resetSuppliersForTesting() {
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.BREWER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.BREWER_ABILITIES_ENABLED::get;
        routeEnabledSupplier = Config.BREWER_TASTING_ABILITIES_ENABLED::get;
        LAST_WARN_NANOS.set(0);
    }
}
