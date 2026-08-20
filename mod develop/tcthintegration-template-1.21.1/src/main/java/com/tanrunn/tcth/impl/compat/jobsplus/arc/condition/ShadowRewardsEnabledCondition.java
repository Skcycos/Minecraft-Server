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
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.shadow.ShadowLogThrottle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * TCTH condition {@code tcth:shadow_rewards_enabled} — the data-driven mirror
 * of the shadow thief reward pipeline switches (phase 8E).
 *
 * <p>Matches only when <em>all</em> of the following are true:
 * <ul>
 *   <li>TCTH framework switch {@code Config.ENABLED};</li>
 *   <li>shadow thief integration switch {@code shadowThiefIntegrationEnabled};</li>
 *   <li>shadow reward switch {@code shadowRewardsEnabled}.</li>
 * </ul>
 *
 * <p><strong>Fail-closed, unconditionally:</strong> a {@link RuntimeException}
 * or {@link LinkageError} while reading the config returns {@code false}
 * directly — never flipped by {@code inverted}, so a broken config can never
 * grant rewards. Repeated failures are logged at most once per 60 s via
 * {@link ShadowLogThrottle}.
 */
public class ShadowRewardsEnabledCondition extends AbstractCondition {

    /** Switch suppliers, injectable for tests; production reads the config. */
    public static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
    public static BooleanSupplier integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
    public static BooleanSupplier rewardsEnabledSupplier = Config.SHADOW_REWARDS_ENABLED::get;

    public ShadowRewardsEnabledCondition(boolean inverted) {
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
            // regardless of inverted. Throttled at 60 s via ShadowLogThrottle.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] tcth:shadow_rewards_enabled config read failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.SHADOW_REWARDS_ENABLED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.shadow_rewards_enabled.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.shadow_rewards_enabled.desc");
    }

    public static final class Serializer implements IConditionSerializer<ShadowRewardsEnabledCondition> {

        @Override
        public ShadowRewardsEnabledCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new ShadowRewardsEnabledCondition(inverted);
        }

        @Override
        public ShadowRewardsEnabledCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                                         boolean inverted) {
            return new ShadowRewardsEnabledCondition(inverted);
        }
    }

    // ---- test hooks ----

    public static void resetSuppliersForTesting() {
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        integrationEnabledSupplier = Config.SHADOW_THIEF_INTEGRATION_ENABLED::get;
        rewardsEnabledSupplier = Config.SHADOW_REWARDS_ENABLED::get;
    }
}
