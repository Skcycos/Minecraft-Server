package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:dish_quality} — matches when the dish quality is
 * one of the listed values.
 *
 * <pre>{@code
 * { "type": "tcth:dish_quality", "quality": ["EXCELLENT", "SUPERB"] }
 * }</pre>
 *
 * <p>Quality names are case-normalized; an unknown quality fails data loading
 * with a clear error. Supports {@code inverted}.
 */
public class DishQualityCondition extends AbstractCondition {

    private final List<String> qualities;

    public DishQualityCondition(boolean inverted, List<String> qualities) {
        super(inverted);
        this.qualities = List.copyOf(qualities);
    }

    public List<String> qualities() {
        return qualities;
    }

    @Override
    public boolean isMet(ActionData data) {
        String actual = data.getData(TcthArcRegistrar.QUALITY);
        boolean matches = actual != null && qualities.contains(actual);
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.DISH_QUALITY;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.dish_quality.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.dish_quality.desc", String.join(", ", qualities));
    }

    public static final class Serializer implements IConditionSerializer<DishQualityCondition> {

        @Override
        public DishQualityCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            JsonArray array = GsonHelper.getAsJsonArray(json, "quality");
            List<String> parsed = new ArrayList<>();
            for (com.google.gson.JsonElement element : array) {
                String quality = element.getAsString().toUpperCase(Locale.ROOT);
                try {
                    DishQuality.valueOf(quality);
                } catch (IllegalArgumentException e) {
                    throw new JsonSyntaxException("Unknown dish quality '" + quality + "' in tcth:dish_quality condition");
                }
                parsed.add(quality);
            }
            if (parsed.isEmpty()) {
                throw new JsonSyntaxException("tcth:dish_quality condition requires at least one quality");
            }
            return new DishQualityCondition(inverted, parsed);
        }

        @Override
        public DishQualityCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            int size = buf.readVarInt();
            List<String> parsed = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                parsed.add(buf.readUtf());
            }
            return new DishQualityCondition(inverted, parsed);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, DishQualityCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeVarInt(condition.qualities.size());
            condition.qualities.forEach(buf::writeUtf);
        }
    }
}
