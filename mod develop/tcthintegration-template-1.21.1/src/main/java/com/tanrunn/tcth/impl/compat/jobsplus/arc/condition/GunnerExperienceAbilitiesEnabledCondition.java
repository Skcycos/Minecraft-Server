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
 * TCTH condition {@code tcth:gunner_experience_abilities_enabled} — the gunner
 * study-route switch (phase 5B).
 *
 * <pre>{@code
 * { "type": "tcth:gunner_experience_abilities_enabled" }
 * }</pre>
 *
 * <p>Matches only when <em>all</em> of these are true: TCTH framework switch,
 * gunner integration switch, gunner-abilities master switch and the study-route
 * switch. Attached to the three study-route Arc actions
 * ({@code jobsplus:on_job_exp} + {@code jobsplus:job_exp_multiplier} ×1.15 /
 * ×1.35 / ×1.60) so the config switches can actually stop them.
 *
 * <p><strong>Fail-closed, unconditionally:</strong> a {@link RuntimeException}
 * or {@link LinkageError} while reading the config makes the condition return
 * {@code false} directly — never flipped by {@code inverted}, so a broken
 * config can never grant the multiplier. Repeated failures are logged at most
 * once per {@link #WARN_THROTTLE_NS} window.
 */
public class GunnerExperienceAbilitiesEnabledCondition extends AbstractCondition {

    private static final long WARN_THROTTLE_NS = 5_000_000_000L; // 5 s
    private static final AtomicLong LAST_WARN_NANOS = new AtomicLong(0);

    /** Config suppliers, injectable for tests. */
    public static BooleanSupplier frameworkEnabledSupplier = Config.ENABLED::get;
    public static BooleanSupplier integrationEnabledSupplier = Config.GUNNER_INTEGRATION_ENABLED::get;
    public static BooleanSupplier abilitiesMasterSupplier = Config.GUNNER_ABILITIES_ENABLED::get;
    public static BooleanSupplier routeEnabledSupplier = Config.GUN_EXPERIENCE_ABILITIES_ENABLED::get;

    public GunnerExperienceAbilitiesEnabledCondition(boolean inverted) {
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
                    "[TCTH] gunner_experience_abilities_enabled config read failed; fail-closed (disabled): {}",
                    e.toString());
        }
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.GUNNER_EXPERIENCE_ABILITIES_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.gunner_experience_abilities_enabled.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.gunner_experience_abilities_enabled.desc");
    }

    public static final class Serializer implements IConditionSerializer<GunnerExperienceAbilitiesEnabledCondition> {

        @Override
        public GunnerExperienceAbilitiesEnabledCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new GunnerExperienceAbilitiesEnabledCondition(inverted);
        }

        @Override
        public GunnerExperienceAbilitiesEnabledCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new GunnerExperienceAbilitiesEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    public static void resetSuppliersForTesting() {
        frameworkEnabledSupplier = Config.ENABLED::get;
        integrationEnabledSupplier = Config.GUNNER_INTEGRATION_ENABLED::get;
        abilitiesMasterSupplier = Config.GUNNER_ABILITIES_ENABLED::get;
        routeEnabledSupplier = Config.GUN_EXPERIENCE_ABILITIES_ENABLED::get;
        LAST_WARN_NANOS.set(0);
    }
}
