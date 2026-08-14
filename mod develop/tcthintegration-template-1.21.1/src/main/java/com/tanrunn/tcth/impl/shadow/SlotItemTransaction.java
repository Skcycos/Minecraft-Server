package com.tanrunn.tcth.impl.shadow;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Explicit-slot item delivery transaction (8D.1.1 §1, hardened 8D.1.2).
 *
 * <p>Real state classification (8D.1.2 §1):
 * <ul>
 *   <li>{@link Phase#PRE} — nothing written; rollback succeeds without
 *       touching the slot;</li>
 *   <li>{@link Phase#COMMITTED} — the slot was verified to equal
 *       afterStack; rollback may restore beforeStack;</li>
 *   <li>{@link Phase#FOREIGN} — the slot content is neither before nor
 *       after (external change or unverifiable); rollback REFUSES and never
 *       overwrites it.</li>
 * </ul>
 *
 * <p>{@code commit} reads and writes through try/catch and ALWAYS re-reads
 * the slot afterwards — even when {@code getItem}/{@code setItem} throws:
 * equal to before → PRE (clean failure); equal to after → COMMITTED;
 * anything else → FOREIGN. Exceptions never escape to the coordinator.
 */
public final class SlotItemTransaction {

    public enum Phase {
        PRE, COMMITTED, FOREIGN
    }

    /** Main inventory slots only (0..35). */
    public static final int MAIN_SLOTS = 36;

    private final Inventory inventory;
    private final int slot;
    private final ItemStack beforeStack;
    private final ItemStack afterStack;
    private final ItemStack deliveryStack;
    private Phase phase = Phase.PRE;

    private SlotItemTransaction(Inventory inventory, int slot, ItemStack beforeStack,
                                ItemStack afterStack, ItemStack deliveryStack) {
        this.inventory = inventory;
        this.slot = slot;
        this.beforeStack = beforeStack;
        this.afterStack = afterStack;
        this.deliveryStack = deliveryStack;
    }

    /**
     * Selects the delivery slot and snapshots the transaction.
     *
     * @return the prepared transaction, or {@code null} when no main slot can
     *         fully receive the stack
     */
    public static SlotItemTransaction prepare(Inventory inventory, ItemStack delivery) {
        if (inventory == null || delivery == null || delivery.isEmpty()) {
            return null;
        }
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack slot;
            try {
                slot = inventory.getItem(i);
            } catch (RuntimeException | LinkageError e) {
                return null; // unreadable inventory → cannot prepare
            }
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, delivery)
                    && slot.getCount() + delivery.getCount() <= slot.getMaxStackSize()) {
                return snapshot(inventory, i, slot, delivery);
            }
        }
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack slot;
            try {
                slot = inventory.getItem(i);
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
            if (slot.isEmpty() && delivery.getMaxStackSize() >= delivery.getCount()) {
                return snapshot(inventory, i, ItemStack.EMPTY, delivery);
            }
        }
        return null;
    }

    private static SlotItemTransaction snapshot(Inventory inventory, int slot,
                                                ItemStack before, ItemStack delivery) {
        ItemStack after = before.isEmpty() ? delivery.copy() : before.copy();
        if (!before.isEmpty()) {
            after.grow(delivery.getCount());
        }
        return new SlotItemTransaction(inventory, slot, before.copy(), after, delivery.copy());
    }

    public int slot() {
        return slot;
    }

    public ItemStack deliveryStack() {
        return deliveryStack.copy();
    }

    public Phase phase() {
        return phase;
    }

    /**
     * Writes the delivery with a mandatory post-write re-read, even when
     * {@code setItem} throws:
     * <ul>
     *   <li>slot equals beforeStack → PRE (clean failure, nothing written);</li>
     *   <li>slot equals afterStack → COMMITTED (success);</li>
     *   <li>anything else (or an unreadable slot) → FOREIGN.</li>
     * </ul>
     */
    public boolean commit() {
        if (phase != Phase.PRE) {
            return false;
        }
        ItemStack current;
        try {
            current = inventory.getItem(slot);
        } catch (RuntimeException | LinkageError e) {
            phase = Phase.FOREIGN; // unreadable → cannot verify
            return false;
        }
        if (!stacksEqual(current, beforeStack)) {
            phase = Phase.FOREIGN; // external change before the write
            return false;
        }
        try {
            inventory.setItem(slot, afterStack.copy());
        } catch (RuntimeException | LinkageError e) {
            // fall through to the mandatory re-read
        }
        ItemStack readBack;
        try {
            readBack = inventory.getItem(slot);
        } catch (RuntimeException | LinkageError e) {
            phase = Phase.FOREIGN; // cannot verify the write
            return false;
        }
        if (stacksEqual(readBack, beforeStack)) {
            phase = Phase.PRE; // no-op write: clean failure
            return false;
        }
        if (stacksEqual(readBack, afterStack)) {
            phase = Phase.COMMITTED;
            return true;
        }
        phase = Phase.FOREIGN; // wrong write / external change
        return false;
    }

    /**
     * Restores beforeStack. PRE succeeds without writing. COMMITTED accepts
     * only a slot currently equal to afterStack and re-verifies the restore.
     * FOREIGN never overwrites anything and always fails.
     */
    public boolean rollback() {
        if (phase == Phase.PRE) {
            return true;
        }
        if (phase != Phase.COMMITTED) {
            return false; // FOREIGN: never overwrite
        }
        ItemStack current;
        try {
            current = inventory.getItem(slot);
        } catch (RuntimeException | LinkageError e) {
            phase = Phase.FOREIGN;
            return false;
        }
        if (!stacksEqual(current, afterStack)) {
            phase = Phase.FOREIGN; // external change → refuse
            return false;
        }
        try {
            inventory.setItem(slot, beforeStack.copy());
        } catch (RuntimeException | LinkageError e) {
            // fall through to the mandatory re-read (8D.1.3 §3)
        }
        ItemStack readBack;
        try {
            readBack = inventory.getItem(slot);
        } catch (RuntimeException | LinkageError e) {
            phase = Phase.FOREIGN;
            return false;
        }
        if (stacksEqual(readBack, beforeStack)) {
            phase = Phase.PRE; // restore landed despite the throw
            return true;
        }
        if (stacksEqual(readBack, afterStack)) {
            phase = Phase.COMMITTED; // restore did not land
            return false;
        }
        phase = Phase.FOREIGN; // restore wrote something else
        return false;
    }

    /** Exact comparison: both empty, or same item + components + count. */
    static boolean stacksEqual(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(a, b) && a.getCount() == b.getCount();
    }
}
