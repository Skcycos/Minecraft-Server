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
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * TCTH condition {@code tcth:cooking_device} — matches when the producing
 * device is one of the listed values.
 *
 * <pre>{@code
 * { "type": "tcth:cooking_device", "devices": ["FARMERS_DELIGHT_COOKING_POT", "KALEIDOSCOPE_STEAMER"] }
 * }</pre>
 *
 * <p>Device names are case-normalized; an unknown device fails data loading
 * with a clear error. Supports {@code inverted}.
 */
public class CookingDeviceCondition extends AbstractCondition {

    private final List<String> devices;

    public CookingDeviceCondition(boolean inverted, List<String> devices) {
        super(inverted);
        this.devices = List.copyOf(devices);
    }

    public List<String> devices() {
        return devices;
    }

    @Override
    public boolean isMet(ActionData data) {
        String actual = data.getData(TcthArcRegistrar.DEVICE);
        boolean matches = actual != null && devices.contains(actual);
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.COOKING_DEVICE;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.cooking_device.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.cooking_device.desc", String.join(", ", devices));
    }

    public static final class Serializer implements IConditionSerializer<CookingDeviceCondition> {

        @Override
        public CookingDeviceCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            JsonArray array = GsonHelper.getAsJsonArray(json, "devices");
            List<String> parsed = new ArrayList<>();
            for (com.google.gson.JsonElement element : array) {
                String device = element.getAsString().toUpperCase(Locale.ROOT);
                try {
                    CookingDevice.valueOf(device);
                } catch (IllegalArgumentException e) {
                    throw new JsonSyntaxException("Unknown cooking device '" + device + "' in tcth:cooking_device condition");
                }
                parsed.add(device);
            }
            if (parsed.isEmpty()) {
                throw new JsonSyntaxException("tcth:cooking_device condition requires at least one device");
            }
            return new CookingDeviceCondition(inverted, parsed);
        }

        @Override
        public CookingDeviceCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            int size = buf.readVarInt();
            List<String> parsed = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                parsed.add(buf.readUtf());
            }
            return new CookingDeviceCondition(inverted, parsed);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, CookingDeviceCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeVarInt(condition.devices.size());
            condition.devices.forEach(buf::writeUtf);
        }
    }
}
