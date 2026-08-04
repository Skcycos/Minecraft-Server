package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.Locale;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:dish_tier} — matches the dish tier of a
 * {@code tcth:on_dish_cooked} action.
 *
 * <pre>{@code
 * { "type": "tcth:dish_tier", "tier": "T3" }
 * }</pre>
 *
 * <p>Tier names are case-normalized; an unknown tier fails data loading with a
 * clear error instead of silently never matching. Supports {@code inverted}.
 */
public class DishTierCondition extends AbstractCondition {

    private final String tier;

    public DishTierCondition(boolean inverted, String tier) {
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
        return TcthArcRegistrar.DISH_TIER;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.dish_tier.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.dish_tier.desc", tier);
    }

    public static final class Serializer implements IConditionSerializer<DishTierCondition> {

        @Override
        public DishTierCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            String tier = GsonHelper.getAsString(json, "tier").toUpperCase(Locale.ROOT);
            try {
                DishTier.valueOf(tier);
            } catch (IllegalArgumentException e) {
                throw new JsonSyntaxException("Unknown dish tier '" + tier + "' in tcth:dish_tier condition");
            }
            return new DishTierCondition(inverted, tier);
        }

        @Override
        public DishTierCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new DishTierCondition(inverted, buf.readUtf());
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, DishTierCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeUtf(condition.tier);
        }
    }
}
