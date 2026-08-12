package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

/**
 * Immutable transaction plan produced by
 * {@link ShadowTransferExecutor#prepare(ShadowAttemptContext, ShadowCandidate,
 * net.minecraft.util.RandomSource)} (8C.1).
 *
 * <p>Plans are pure data: they never hold live {@code Player}/{@code Inventory}
 * (or other entity) references — only UUIDs, slot indexes, scalar values and
 * defensive snapshots (copies). This lets the coordinator pass the plan across
 * the pre-write audit boundary and lets the executor re-validate the world
 * state against the snapshots at commit time.
 *
 * @see ItemPlan
 * @see HealthPlan
 * @see HungerPlan
 * @see EffectPlan
 */
public sealed interface ShadowTransferPlan permits ItemPlan, HealthPlan, HungerPlan, EffectPlan,
        ShadowTransferPlan.Generic {

    /**
     * @return the theft type this plan was prepared for
     */
    ShadowTheftType type();

    /**
     * @return an additive success-chance modifier derived from the concrete
     *         selected asset (e.g. the high-value item penalty); 0 by default
     */
    default double successModifier() {
        return 0.0d;
    }

    /**
     * Minimal plan for test fakes and executors without concrete assets.
     */
    record Generic(ShadowTheftType type) implements ShadowTransferPlan {
        public Generic {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public ShadowTheftType type() {
            return type;
        }
    }
}
