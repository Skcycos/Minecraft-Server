package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.world.item.ItemStack;

/**
 * Immutable ITEM transfer plan (8C.1).
 *
 * <p>Holds only slot indexes and defensive {@code ItemStack} copies — never
 * player/inventory references. {@code successModifier()} applies the
 * {@code #tcth:high_value_stealable_items} penalty (-0.10) when the selected
 * stack is high-value.
 *
 * @param victimSlot       the victim's main-inventory slot (0..35)
 * @param victimStackBefore a defensive copy of the victim's slot BEFORE the
 *                          commit (used for slot-drift re-validation and
 *                          rollback)
 * @param thiefSlot        the thief's target slot (a mergeable partial stack
 *                          or a free slot)
 * @param thiefStackBefore a defensive copy of the thief's target slot BEFORE
 *                          the commit (empty or a mergeable stack)
 * @param selected         the selected stack as a defensive copy (count 1)
 */
public record ItemPlan(int victimSlot, ItemStack victimStackBefore, int thiefSlot,
                       ItemStack thiefStackBefore, ItemStack selected) implements ShadowTransferPlan {

    /** High-value success-chance penalty (stage 8A §10). */
    public static final double HIGH_VALUE_MODIFIER = -0.10d;

    public ItemPlan {
        Objects.requireNonNull(victimStackBefore, "victimStackBefore");
        Objects.requireNonNull(thiefStackBefore, "thiefStackBefore");
        Objects.requireNonNull(selected, "selected");
        if (victimSlot < 0 || victimSlot >= PlayerReadonlyCandidateProvider.MAIN_INVENTORY_END_EXCLUSIVE) {
            throw new IllegalArgumentException("victimSlot out of main-inventory range: " + victimSlot);
        }
        if (thiefSlot < 0) {
            throw new IllegalArgumentException("thiefSlot must be non-negative: " + thiefSlot);
        }
        if (selected.getCount() != 1) {
            throw new IllegalArgumentException("the selected stack must carry exactly 1 item: " + selected.getCount());
        }
        // Defensive copies only; never share mutable stacks with the world.
        victimStackBefore = victimStackBefore.copy();
        thiefStackBefore = thiefStackBefore.copy();
        selected = selected.copy();
    }

    @Override
    public ShadowTheftType type() {
        return ShadowTheftType.ITEM;
    }

    @Override
    public double successModifier() {
        return selected.is(ShadowTags.HIGH_VALUE_STEALABLE_ITEMS) ? HIGH_VALUE_MODIFIER : 0.0d;
    }
}
