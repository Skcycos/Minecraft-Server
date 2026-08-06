package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:gun_target_tier} — matches the {@code target_tier}
 * of a gun-kill event.
 *
 * <pre>{@code
 * { "type": "tcth:gun_target_tier", "tier": "COMMON" }
 * }</pre>
 *
 * <p>Unknown tier values in JSON produce a clear error at data load time.
 * Supports {@code inverted}.
 */
public class GunTargetTierCondition extends AbstractCondition {

    private final GunTargetTier tier;

    public GunTargetTierCondition(boolean inverted, GunTargetTier tier) {
        super(inverted);
        this.tier = tier;
    }

    public GunTargetTier tier() {
        return tier;
    }

    @Override
    public boolean isMet(ActionData data) {
        String actual = data.getData(TcthArcRegistrar.TARGET_TIER);
        boolean matches = actual != null && actual.equals(tier.name());
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.GUN_TARGET_TIER;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.gun_target_tier.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.gun_target_tier.desc", tier.name());
    }

    public static final class Serializer implements IConditionSerializer<GunTargetTierCondition> {

        @Override
        public GunTargetTierCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            String tierName = GsonHelper.getAsString(json, "tier");
            GunTargetTier tier = parseTier(tierName);
            return new GunTargetTierCondition(inverted, tier);
        }

        @Override
        public GunTargetTierCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            String tierName = buf.readUtf();
            GunTargetTier tier = parseTier(tierName);
            return new GunTargetTierCondition(inverted, tier);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, GunTargetTierCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeUtf(condition.tier.name());
        }

        private static GunTargetTier parseTier(String tierName) {
            if (tierName == null || tierName.isBlank()) {
                throw new IllegalArgumentException(
                        "tcth:gun_target_tier: 'tier' must not be empty. Valid values: COMMON, ELITE, HEAVY, BOSS");
            }
            try {
                return GunTargetTier.valueOf(tierName.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "tcth:gun_target_tier: unknown tier '" + tierName + "'. Valid values: COMMON, ELITE, HEAVY, BOSS", e);
            }
        }
    }
}
