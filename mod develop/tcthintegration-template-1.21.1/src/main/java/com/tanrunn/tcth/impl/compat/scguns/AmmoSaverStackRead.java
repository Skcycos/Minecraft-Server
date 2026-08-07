package com.tanrunn.tcth.impl.compat.scguns;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Read-only gun-stack CustomData access for the beam ammo-saver gate
 * (phase 5B.1.1).
 *
 * <p>API confirmed against Minecraft 1.21.1 ({@code javap} on the NeoForge
 * compile classpath):
 * <ul>
 *   <li>{@code DataComponents.CUSTOM_DATA}</li>
 *   <li>{@code ItemStack.get(DataComponentType)} — returns {@code null} when
 *       absent; never creates a component</li>
 *   <li>{@code CustomData.isEmpty()} / {@code CustomData.copyTag()} — copy is
 *       detached from the stack; reading it cannot write NBT back</li>
 * </ul>
 *
 * <p>Mirrors SG's private {@code getCustomData(ItemStack)} (which also uses
 * {@code get} + {@code copyTag} and returns {@code null} when missing) and
 * deliberately avoids SG's {@code getOrCreateCustomData} so this path never
 * materialises a new {@code CustomData} component on the stack.
 */
public final class AmmoSaverStackRead {

    private static final String IGNORE_AMMO = "IgnoreAmmo";
    private static final String AMMO_COUNT = "AmmoCount";

    private AmmoSaverStackRead() {
    }

    /**
     * Read-only snapshot for {@link AmmoSaverBeamGate}. Missing CustomData is
     * treated as {@code IgnoreAmmo=false} and {@code AmmoCount=0} (same as SG
     * {@code getBoolean}/{@code getInt} on an empty or absent tag).
     *
     * <p>Does not create CustomData, write NBT, mutate the stack, copy ammo,
     * or refund items.
     */
    public static AmmoSaverBeamGate.StackFields readFields(ItemStack stack) {
        CompoundTag tag = readCustomDataCopy(stack);
        if (tag == null) {
            return new AmmoSaverBeamGate.StackFields(false, 0);
        }
        return new AmmoSaverBeamGate.StackFields(
                tag.getBoolean(IGNORE_AMMO),
                tag.getInt(AMMO_COUNT));
    }

    /**
     * Whether {@code IgnoreAmmo} is set. Missing CustomData → {@code false}.
     */
    public static boolean isIgnoreAmmo(ItemStack stack) {
        return readFields(stack).ignoreAmmo();
    }

    /**
     * Current {@code AmmoCount}. Missing CustomData → {@code 0}.
     */
    public static int ammoCount(ItemStack stack) {
        return readFields(stack).ammoCount();
    }

    /**
     * Detached copy of the stack's CUSTOM_DATA tag, or {@code null} when the
     * component is absent/empty — same contract as SG
     * {@code ServerPlayHandler.getCustomData}.
     */
    static CompoundTag readCustomDataCopy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag();
    }
}
