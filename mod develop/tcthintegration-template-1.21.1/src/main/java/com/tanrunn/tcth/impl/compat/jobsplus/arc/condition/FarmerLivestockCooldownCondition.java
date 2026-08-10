package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.condition.AbstractCondition;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.daqem.arc.api.condition.type.IConditionType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.FarmerLivestockCooldown;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * TCTH condition {@code tcth:farmer_livestock_cooldown} — anti-farm cooldown
 * for the farmer livestock route (phase 4B).
 *
 * <pre>{@code
 * { "type": "tcth:farmer_livestock_cooldown" }
 * }</pre>
 *
 * <p>Passes when the player is <em>not</em> inside the shared livestock
 * cooldown window; blocks the action while the cooldown is active. The
 * cooldown itself is committed by the {@code tcth:farmer_livestock_effects}
 * reward only after effects were actually granted (success-driven), and is
 * shared by all three livestock nodes.
 */
public class FarmerLivestockCooldownCondition extends AbstractCondition {

    public FarmerLivestockCooldownCondition(boolean inverted) {
        super(inverted);
    }

    @Override
    public boolean isMet(ActionData data) {
        Player player = data.getPlayer().arc$getPlayer();
        if (player instanceof ServerPlayer serverPlayer) {
            boolean onCooldown = FarmerLivestockCooldown.instance().isOnCooldown(serverPlayer.getUUID(), serverPlayer);
            // Pass when NOT on cooldown (inverted flips it: pass when on cooldown).
            return isInverted() == onCooldown;
        }
        // Non-ServerPlayer cannot receive livestock effects; do not block.
        return !isInverted();
    }

    @Override
    public IConditionType<?> getType() {
        return TcthArcRegistrar.FARMER_LIVESTOCK_COOLDOWN_CONDITION;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.condition.farmer_livestock_cooldown.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.condition.farmer_livestock_cooldown.desc");
    }

    public static final class Serializer implements IConditionSerializer<FarmerLivestockCooldownCondition> {

        @Override
        public FarmerLivestockCooldownCondition fromJson(ResourceLocation location, JsonObject json, boolean inverted) {
            return new FarmerLivestockCooldownCondition(inverted);
        }

        @Override
        public FarmerLivestockCooldownCondition fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf, boolean inverted) {
            return new FarmerLivestockCooldownCondition(inverted);
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, FarmerLivestockCooldownCondition condition) {
            IConditionSerializer.super.toNetwork(buf, condition);
        }
    }
}
