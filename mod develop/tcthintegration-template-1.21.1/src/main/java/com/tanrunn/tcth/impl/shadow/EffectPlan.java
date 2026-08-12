package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Immutable EFFECT transfer plan (8C.1).
 *
 * <p>The effect is identified by its {@link ResourceLocation} (never a live
 * holder/entity reference); both sides' pre-commit {@code MobEffectInstance}s
 * are snapshotted as defensive copies so commit re-validation and both
 * rollback paths can restore the exact durations, amplifiers and flags.
 *
 * @param effectId             the effect id
 * @param amplifier            the shared amplifier (never raised)
 * @param transferTicks        the planned transfer (base max 200 ticks, capped
 *                             by the victim's remaining time)
 * @param victimInstanceBefore a defensive copy of the victim's effect instance
 *                             BEFORE the commit
 * @param thiefInstanceBefore  a defensive copy of the thief's effect instance
 *                             BEFORE the commit, or {@code null} when the
 *                             thief did not have the effect
 */
public record EffectPlan(ResourceLocation effectId, int amplifier, int transferTicks,
                         MobEffectInstance victimInstanceBefore,
                         @Nullable MobEffectInstance thiefInstanceBefore) implements ShadowTransferPlan {

    /** Base maximum effect transfer in ticks (8C.1 §6). */
    public static final int BASE_MAX_TRANSFER_TICKS = 200;

    public EffectPlan {
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(victimInstanceBefore, "victimInstanceBefore");
        if (amplifier < 0 || transferTicks <= 0 || victimInstanceBefore.getDuration() <= 0) {
            throw new IllegalArgumentException("effect plan values out of range");
        }
        // Defensive snapshot copies; never share mutable instances with the
        // world.
        victimInstanceBefore = new MobEffectInstance(victimInstanceBefore);
        if (thiefInstanceBefore != null) {
            thiefInstanceBefore = new MobEffectInstance(thiefInstanceBefore);
        }
    }

    @Override
    public ShadowTheftType type() {
        return ShadowTheftType.EFFECT;
    }
}
