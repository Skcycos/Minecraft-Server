package com.tanrunn.tcth.impl.compat.jobsplus.arc.reward;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.result.ActionResult;
import com.daqem.arc.api.reward.AbstractReward;
import com.daqem.arc.api.reward.serializer.IRewardSerializer;
import com.daqem.arc.api.reward.type.IRewardType;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.BrewerDrinkCooldown;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * TCTH reward {@code tcth:brewer_tasting_effects} — grants the brewer
 * tasting-route status effect package (phase 7E).
 *
 * <pre>{@code
 * { "type": "tcth:brewer_tasting_effects", "tier": 2 }
 * }</pre>
 *
 * <p>One reward grants the complete package of its tier from a single action
 * (the tier is data-selected by the preset's {@code powerup_not_active}
 * mutual exclusion), so lower-tier actions never fire alongside it:
 * <ul>
 *   <li>tier 1 (浅酌回甘): Regeneration I, 5 s;</li>
 *   <li>tier 2 (细品醇香): Regeneration I 5 s + Resistance I 8 s;</li>
 *   <li>tier 3 (余韵绵长): Regeneration I 5 s + Resistance I 8 s + Speed I 15 s.</li>
 * </ul>
 *
 * <p>All effects are level I (amplifier 0), short-duration, and never modify
 * the beverage. The shared brewer-drink cooldown is committed only after at
 * least one effect was actually applied (success-driven), via
 * {@link BrewerDrinkCooldown}.
 */
public class BrewerTastingEffectsReward extends AbstractReward {

    /** Regeneration I duration in ticks (5 s). */
    public static final int REGENERATION_TICKS = 100;
    /** Resistance I duration in ticks (8 s). */
    public static final int RESISTANCE_TICKS = 160;
    /** Speed I duration in ticks (15 s). */
    public static final int SPEED_TICKS = 300;

    private final int tier;

    public BrewerTastingEffectsReward(double chance, int priority, int tier) {
        super(chance, priority);
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("tcth:brewer_tasting_effects tier must be 1..3, got " + tier);
        }
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    @Override
    public ActionResult apply(ActionData data) {
        if (!(data.getPlayer().arc$getPlayer() instanceof ServerPlayer serverPlayer)) {
            return new ActionResult();
        }
        boolean anyApplied = false;
        // 生命恢复 I，5 秒
        if (tier >= 1) {
            anyApplied |= serverPlayer.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGENERATION_TICKS, 0));
        }
        // 抗性提升 I，8 秒
        if (tier >= 2) {
            anyApplied |= serverPlayer.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, RESISTANCE_TICKS, 0));
        }
        // 速度 I，15 秒
        if (tier >= 3) {
            anyApplied |= serverPlayer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, SPEED_TICKS, 0));
        }
        if (anyApplied) {
            BrewerDrinkCooldown.instance().commit(serverPlayer.getUUID(), serverPlayer);
        }
        return new ActionResult();
    }

    @Override
    public IRewardType<?> getType() {
        return TcthArcRegistrar.BREWER_TASTING_EFFECTS;
    }

    @Override
    public Component getName() {
        return Component.translatable("tcth.reward.brewer_tasting_effects.name");
    }

    @Override
    public Component getDescription(Object... args) {
        return Component.translatable("tcth.reward.brewer_tasting_effects.desc", tier);
    }

    public static final class Serializer implements IRewardSerializer<BrewerTastingEffectsReward> {

        @Override
        public BrewerTastingEffectsReward fromJson(JsonObject json, double chance, int priority) {
            return new BrewerTastingEffectsReward(chance, priority, GsonHelper.getAsInt(json, "tier"));
        }

        @Override
        public BrewerTastingEffectsReward fromNetwork(RegistryFriendlyByteBuf buf, double chance, int priority) {
            return new BrewerTastingEffectsReward(chance, priority, buf.readVarInt());
        }

        @Override
        public void toNetwork(RegistryFriendlyByteBuf buf, BrewerTastingEffectsReward reward) {
            IRewardSerializer.super.toNetwork(buf, reward);
            buf.writeVarInt(reward.tier);
        }
    }
}
