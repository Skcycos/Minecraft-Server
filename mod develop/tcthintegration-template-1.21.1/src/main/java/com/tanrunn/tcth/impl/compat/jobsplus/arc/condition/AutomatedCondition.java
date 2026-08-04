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
 * TCTH condition {@code tcth:automated} — matches the {@code automated} flag
 * of a dish event.
 *
 * <pre>{@code
 * { "type": "tcth:automated", "value": false }
 * }</pre>
 *
 * <p>Supports {@code inverted}.
 */
public class AutomatedCondition extends AbstractCondition {

    private final boolean value;

    public AutomatedCondition(boolean inverted, boolean value) {
        super(inverted);
        this.value = value;
    }

    public boolean value() {
        return value;
    }

    @Override
    public boolean isMet(ActionData data) {
        Boolean actual = data.getData(TcthArcRegistrar.AUTOMATED);
        boolean matches = actual != null && actual == value;
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.AUTOMATED_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.automated.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.automated.desc", value);
    }

    public static final class Serializer implements IConditionSerializer<AutomatedCondition> {

        @Override
        public AutomatedCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new AutomatedCondition(inverted, GsonHelper.getAsBoolean(json, "value"));
        }

        @Override
        public AutomatedCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new AutomatedCondition(inverted, buf.readBoolean());
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, AutomatedCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeBoolean(condition.value);
        }
    }
}
