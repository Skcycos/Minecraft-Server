package com.tanrunn.tcth.impl.compat.brewer.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.tanrunn.tcth.impl.compat.brewer.arc.BrewerArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:beverage_tier} — matches the beverage tier of a
 * {@code tcth:on_beverage_prepared} action (phase 7C).
 *
 * <pre>{@code
 * { "type": "tcth:beverage_tier", "tier": "T2" }
 * }</pre>
 *
 * <p>Tier names are case-normalized; an unknown tier fails data loading with a
 * clear error instead of silently never matching. Supports {@code inverted}.
 * Only runtime tiers {@code UNKNOWN/COMMON/T2/T3} are valid (7A.1).
 */
public class BeverageTierCondition extends AbstractCondition {

    private final String tier;

    public BeverageTierCondition(boolean inverted, String tier) {
        super(inverted);
        this.tier = tier;
    }

    public String tier() {
        return tier;
    }

    @Override
    public boolean isMet(ActionData data) {
        String actual = data.getData(TcthArcRegistrar.TIER);
        boolean matches = tier.equals(actual);
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return BrewerArcRegistrar.BEVERAGE_TIER;
    }

    @Override
    public Component getDescription() {
        return Component.literal("beverage tier is " + tier);
    }

    public static final class Serializer implements IConditionSerializer<BeverageTierCondition> {

        @Override
        public BeverageTierCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            String raw = GsonHelper.getAsString(json, "tier");
            String tier = raw.toUpperCase(java.util.Locale.ROOT);
            switch (tier) {
                case "UNKNOWN", "COMMON", "T2", "T3" -> {
                    // valid runtime tiers only
                }
                default -> throw new JsonSyntaxException("Unknown beverage tier '" + raw + "'");
            }
            return new BeverageTierCondition(inverted, tier);
        }

        @Override
        public BeverageTierCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new BeverageTierCondition(inverted, buf.readUtf());
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, BeverageTierCondition condition) {
            buf.writeUtf(condition.tier);
        }
    }
}
