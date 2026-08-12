package com.tanrunn.tcth.api.shadow;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * Immutable description of what a shadow theft did (or did not) move.
 *
 * <p>The receipt deliberately carries <em>no</em> {@code ItemStack}, no NBT,
 * no item components and no account objects — only the minimal scalar facts
 * needed by consumers (audit, analytics, rewards): the item id and count, a
 * scalar numeric amount (coins, health or hunger points) and/or an effect id
 * plus duration in ticks.
 *
 * <p><b>Validation rules:</b>
 * <ul>
 *   <li>all scalar values are finite (where fractional) and non-negative;</li>
 *   <li>factories only ever build receipts where the fields matching the
 *       intended theft type are set and the others are left at their default;</li>
 *   <li>{@link #matches(ShadowTheftType)} verifies that only the fields
 *       belonging to the given type carry values — the coordinator uses it as
 *       a defence against a misbehaving transfer executor;</li>
 *   <li>a non-{@code SUCCESS} outcome must use {@link #empty()}, which has all
 *       fields at their default values.</li>
 * </ul>
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this record may change
 * without notice until 1.0.0. See the API stability statement in
 * {@code com.tanrunn.tcth.api}.
 *
 * @param itemId              the item id moved by an {@code ITEM} theft, or
 *                            {@code null}
 * @param itemCount           the number of items moved (0 when not an
 *                            {@code ITEM} theft)
 * @param numericAmount       the scalar amount moved by a {@code COIN},
 *                            {@code HEALTH} or {@code HUNGER} theft in that
 *                            type's unit (core value / hit points / food
 *                            points), or 0
 * @param effectDurationTicks the duration in ticks moved by an
 *                            {@code EFFECT} theft, or 0
 * @param effectId            the effect id moved by an {@code EFFECT} theft,
 *                            or {@code null}
 */
public record ShadowTheftReceipt(@Nullable ResourceLocation itemId, int itemCount,
                                 double numericAmount, int effectDurationTicks,
                                 @Nullable ResourceLocation effectId) {

    /**
     * A receipt describing "nothing was moved" — the default for every
     * non-{@code SUCCESS} outcome.
     */
    public static ShadowTheftReceipt empty() {
        return new ShadowTheftReceipt(null, 0, 0.0d, 0, null);
    }

    /**
     * @return a receipt describing an {@code ITEM} theft of {@code count}
     *         items with the given id
     */
    public static ShadowTheftReceipt item(ResourceLocation itemId, int count) {
        return new ShadowTheftReceipt(Objects.requireNonNull(itemId, "itemId"), count, 0.0d, 0, null);
    }

    /**
     * @return a receipt describing a scalar theft ({@code COIN},
     *         {@code HEALTH} or {@code HUNGER}) of the given amount
     */
    public static ShadowTheftReceipt numeric(double amount) {
        return new ShadowTheftReceipt(null, 0, amount, 0, null);
    }

    /**
     * @return a receipt describing an {@code EFFECT} theft of
     *         {@code durationTicks} ticks of the given effect
     */
    public static ShadowTheftReceipt effect(ResourceLocation effectId, int durationTicks) {
        return new ShadowTheftReceipt(null, 0, 0.0d, durationTicks,
                Objects.requireNonNull(effectId, "effectId"));
    }

    /**
     * Compact constructor enforcing the scalar validation rules. Item counts
     * and durations must be non-negative; the numeric amount must be finite
     * and non-negative. Cross-field consistency is enforced both ways:
     * {@code itemCount > 0} requires {@code itemId != null} and vice versa;
     * {@code effectDurationTicks > 0} requires {@code effectId != null} and
     * vice versa. Throws otherwise.
     */
    public ShadowTheftReceipt {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be non-negative: " + itemCount);
        }
        if (effectDurationTicks < 0) {
            throw new IllegalArgumentException("effectDurationTicks must be non-negative: " + effectDurationTicks);
        }
        if (!Double.isFinite(numericAmount) || numericAmount < 0.0d) {
            throw new IllegalArgumentException("numericAmount must be finite and non-negative: " + numericAmount);
        }
        if (itemCount > 0 && itemId == null) {
            throw new IllegalArgumentException("itemId is required when itemCount > 0");
        }
        if (itemId != null && itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive when itemId is set");
        }
        if (effectDurationTicks > 0 && effectId == null) {
            throw new IllegalArgumentException("effectId is required when effectDurationTicks > 0");
        }
        if (effectId != null && effectDurationTicks <= 0) {
            throw new IllegalArgumentException("effectDurationTicks must be positive when effectId is set");
        }
    }

    /**
     * @return {@code true} if every field is at its default ("nothing moved")
     */
    public boolean isEmpty() {
        return itemId == null && itemCount == 0 && numericAmount == 0.0d
                && effectDurationTicks == 0 && effectId == null;
    }

    /**
     * Verifies that only the fields belonging to the given theft type carry
     * values:
     * <ul>
     *   <li>{@code ITEM}   — itemId set, itemCount &gt; 0, nothing else;</li>
     *   <li>{@code COIN} / {@code HEALTH} / {@code HUNGER} — numericAmount
     *       &gt; 0, nothing else;</li>
     *   <li>{@code EFFECT} — effectId set, effectDurationTicks &gt; 0,
     *       nothing else.</li>
     * </ul>
     *
     * @param type the theft type this receipt must correspond to
     * @return {@code true} if the receipt only carries fields of that type
     */
    public boolean matches(ShadowTheftType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case ITEM -> itemId != null && itemCount > 0 && numericAmount == 0.0d
                    && effectDurationTicks == 0 && effectId == null;
            case COIN, HEALTH, HUNGER -> itemId == null && itemCount == 0
                    && numericAmount > 0.0d && effectDurationTicks == 0 && effectId == null;
            case EFFECT -> itemId == null && itemCount == 0 && numericAmount == 0.0d
                    && effectId != null && effectDurationTicks > 0;
        };
    }
}
