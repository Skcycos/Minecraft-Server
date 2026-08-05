package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * TCTH condition {@code tcth:tasting_cooldown} — anti-farm cooldown for the
 * tasting route (phase 3D).
 *
 * <pre>{@code
 * { "type": "tcth:tasting_cooldown" }
 * }</pre>
 *
 * <p>Passes when the player is <em>not</em> inside the shared tasting cooldown
 * window; blocks the action while the cooldown is active. The cooldown itself
 * is committed by the {@code tcth:tasting_effects} reward only after effects
 * were actually granted (success-driven), and is shared by all three tasting
 * nodes.
 */
public class TastingCooldownCondition extends AbstractCondition {

    public TastingCooldownCondition(boolean inverted) {
        super(inverted);
    }

    @Override
    public boolean isMet(ActionData data) {
        Player player = data.getPlayer().arc$getPlayer();
        if (player instanceof ServerPlayer serverPlayer) {
            boolean onCooldown = ChefTastingCooldown.instance().isOnCooldown(serverPlayer.getUUID(), serverPlayer);
            // Pass when NOT on cooldown (inverted flips it: pass when on cooldown).
            return isInverted() == onCooldown;
        }
        // Non-ServerPlayer cannot receive tasting effects; do not block.
        return !isInverted();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.TASTING_COOLDOWN_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.tasting_cooldown.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.tasting_cooldown.desc");
    }

    public static final class Serializer implements IConditionSerializer<TastingCooldownCondition> {

        @Override
        public TastingCooldownCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new TastingCooldownCondition(inverted);
        }

        @Override
        public TastingCooldownCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new TastingCooldownCondition(inverted);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, TastingCooldownCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
        }
    }
}
