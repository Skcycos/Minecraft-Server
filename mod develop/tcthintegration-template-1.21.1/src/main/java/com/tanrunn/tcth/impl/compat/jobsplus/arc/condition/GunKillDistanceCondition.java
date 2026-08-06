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
 * TCTH condition {@code tcth:gun_kill_distance} — matches the distance of a
 * gun-kill event.
 *
 * <pre>{@code
 * { "type": "tcth:gun_kill_distance", "min": 0, "max": 100 }
 * }</pre>
 *
 * <p>When {@code min} is omitted it defaults to 0; when {@code max} is omitted
 * it defaults to {@code Float.MAX_VALUE}. The condition matches when
 * {@code min <= distance <= max}. Unknown/illegal values in JSON produce a
 * clear error at data load time. NaN / ±Infinity in JSON or network data are
 * rejected; a non-finite event distance never matches. Supports
 * {@code inverted}.
 */
public class GunKillDistanceCondition extends AbstractCondition {

    private final float min;
    private final float max;

    public GunKillDistanceCondition(boolean inverted, float min, float max) {
        super(inverted);
        if (!Float.isFinite(min)) {
            throw new IllegalArgumentException("tcth:gun_kill_distance: min must be finite");
        }
        if (!Float.isFinite(max)) {
            throw new IllegalArgumentException("tcth:gun_kill_distance: max must be finite");
        }
        if (min < 0.0f) {
            throw new IllegalArgumentException("tcth:gun_kill_distance: min must be >= 0");
        }
        if (max < min) {
            throw new IllegalArgumentException("tcth:gun_kill_distance: max must be >= min");
        }
        this.min = min;
        this.max = max;
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }

    @Override
    public boolean isMet(ActionData data) {
        Float distance = data.getData(TcthArcRegistrar.GUN_KILL_DISTANCE);
        // A missing or non-finite event distance never matches.
        boolean matches = distance != null && Float.isFinite(distance)
                && distance >= min && distance <= max;
        return isInverted() != matches;
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.GUN_KILL_DISTANCE_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.gun_kill_distance.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.gun_kill_distance.desc", min, max);
    }

    public static final class Serializer implements IConditionSerializer<GunKillDistanceCondition> {

        @Override
        public GunKillDistanceCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            float min = json.has("min") ? GsonHelper.getAsFloat(json, "min") : 0.0f;
            float max = json.has("max") ? GsonHelper.getAsFloat(json, "max") : Float.MAX_VALUE;
            return new GunKillDistanceCondition(inverted, min, max);
        }

        @Override
        public GunKillDistanceCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            float min = buf.readFloat();
            float max = buf.readFloat();
            return new GunKillDistanceCondition(inverted, min, max);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, GunKillDistanceCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
            buf.writeFloat(condition.min);
            buf.writeFloat(condition.max);
        }
    }
}
