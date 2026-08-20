package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:shadow_theft_type} — matches the drawn
 * {@code theft_type} of a shadow theft SUCCESS event (phase 8E).
 *
 * <pre>{@code
 * { "type": "tcth:shadow_theft_type", "value": "ITEM" }
 * }</pre>
 *
 * <p>Supports {@code inverted}.
 */
public class ShadowTheftTypeCondition extends AbstractCondition {

    private final String value;

    public ShadowTheftTypeCondition(boolean inverted, String value) {
        super(inverted);
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean isMet(ActionData data) {
        String actual = data.getData(TcthArcRegistrar.SHADOW_THEFT_TYPE);
        boolean matches = actual != null && actual.equals(value);
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.SHADOW_THEFT_TYPE_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.shadow_theft_type.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.shadow_theft_type.desc", value);
    }

    public static final class Serializer implements IConditionSerializer<ShadowTheftTypeCondition> {

        @Override
        public ShadowTheftTypeCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new ShadowTheftTypeCondition(inverted, GsonHelper.getAsString(json, "value"));
        }

        @Override
        public ShadowTheftTypeCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                                    boolean inverted) {
            return new ShadowTheftTypeCondition(inverted, buf.readUtf());
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, ShadowTheftTypeCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeUtf(condition.value);
        }
    }
}
