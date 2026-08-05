package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;

/**
 * TCTH condition {@code tcth:fire_damage} — matches when the incoming damage
 * source is tagged {@code minecraft:is_fire} (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:fire_damage" }
 * }</pre>
 *
 * <p>Tag-based, never name-based: covers on-fire, fireball, lava and any other
 * damage type that vanilla/NeoForge marks with {@link DamageTypeTags#IS_FIRE}.
 * Attached to the hearth-route {@code arc:on_get_hurt} actions. Supports
 * {@code inverted}.
 */
public class FireDamageCondition extends AbstractCondition {

    public FireDamageCondition(boolean inverted) {
        super(inverted);
    }

    @Override
    public boolean isMet(ActionData data) {
        DamageSource source = data.getData(ActionDataType.DAMAGE_SOURCE);
        boolean matches = source != null && isFireTagged(source);
        return isInverted() != matches;
    }

    private boolean isFireTagged(DamageSource source) {
        try {
            return source.is(DamageTypeTags.IS_FIRE);
        } catch (RuntimeException e) {
            // A broken damage-type registry must not block the damage pipeline;
            // treat as non-fire so the original damage value is preserved.
            return false;
        }
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.FIRE_DAMAGE_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.fire_damage.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.fire_damage.desc");
    }

    public static final class Serializer implements IConditionSerializer<FireDamageCondition> {

        @Override
        public FireDamageCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new FireDamageCondition(inverted);
        }

        @Override
        public FireDamageCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new FireDamageCondition(inverted);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, FireDamageCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
        }
    }
}
