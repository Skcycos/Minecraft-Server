package com.tanrunn.tcth.impl.compat.cooking;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Transactional helper for Shift-click take-out of cooking-pot style result
 * slots (Farmer's Delight cooking pot and Dungeon's Delight monster pot share
 * the same {@code quickMoveStack} defect, phase 6B.2).
 *
 * <p>Lifecycle of one Shift-click take:
 * <ol>
 *   <li>{@link #begin} — snapshot the slot's real delivery stack, remember its
 *       previous signature, sign it with the current chef, then take an event
 *       snapshot <em>after</em> signing so the published
 *       {@code DishCookedEvent#getResult()} carries the new signature;</li>
 *   <li>{@link #resolveMovedCount} — derive how many items actually left the
 *       slot ({@code originalCount - remainingCount});</li>
 *   <li>on success {@link #commit} — returns an event stack whose count is the
 *       <em>actually delivered</em> amount and, for a partial move, restores
 *       the <em>remaining</em> in-slot stack to its previous signature;</li>
 *   <li>on failure or exception {@link #abort} — restores the output slot's
 *       previous signature (removing the one this take added);</li>
 *   <li>{@link #end} — clears all per-take state so the next take starts
 *       clean and the same take can never publish twice.</li>
 * </ol>
 *
 * <p>All state lives on this instance, so one instance per
 * {@code quickMoveStack} invocation; publish is allowed at most once
 * (the {@code published} guard).
 */
public final class ShiftTakeTransaction {

    /** The real in-slot stack captured before the move (may be mutated by the menu). */
    private final ItemStack slotStack;

    /** Copy of the slot stack after signing, used as the event result. */
    private final ItemStack eventSnapshot;

    /** Signature that was on the slot stack before this take, or {@code null}. */
    @Nullable
    private final CookingSignature previousSignature;

    /** Recipe id captured before the tracker was cleared, or {@code null}. */
    @Nullable
    private final ResourceLocationHolder recipeIdHolder;

    private final int originalCount;

    private boolean published = false;

    /**
     * Small mutable holder so the mixin can keep the recipe id in the same
     * transaction object without extra mixin fields.
     */
    public static final class ResourceLocationHolder {
        @Nullable
        public net.minecraft.resources.ResourceLocation id;
    }

    private ShiftTakeTransaction(ItemStack slotStack, ItemStack eventSnapshot,
                                 @Nullable CookingSignature previousSignature,
                                 ResourceLocationHolder recipeIdHolder) {
        this.slotStack = slotStack;
        this.eventSnapshot = eventSnapshot;
        this.previousSignature = previousSignature;
        this.recipeIdHolder = recipeIdHolder;
        this.originalCount = slotStack.getCount();
    }

    /**
     * Begins a shift-take transaction: remembers the previous signature,
     * signs the real slot stack with the current chef, and snapshots the
     * signed stack for the event.
     *
     * @param player    server player taking the dish (must be non-null to sign)
     * @param slotStack the real stack currently in the output slot
     * @param recipeId  resolved recipe id (may be {@code null})
     * @return a new transaction, or {@code null} when there is nothing to take
     *         (empty stack / not a dish)
     */
    @Nullable
    public static ShiftTakeTransaction begin(@Nullable ServerPlayer player, ItemStack slotStack,
                                             @Nullable net.minecraft.resources.ResourceLocation recipeId) {
        if (slotStack == null || slotStack.isEmpty()) {
            return null;
        }
        // Non-dishes are never signed, never tracked and never published.
        if (!DishClassifier.isDish(slotStack)) {
            return null;
        }
        CookingSignature previous = null;
        DataComponentType<CookingSignature> sigType = CookingSignatureComponents.tryType();
        if (sigType != null) {
            previous = slotStack.get(sigType);
        }
        ResourceLocationHolder holder = new ResourceLocationHolder();
        holder.id = recipeId;
        if (player != null) {
            DishSignatureService.sign(player, slotStack);
        }
        ItemStack signedSnapshot = slotStack.copy();
        return new ShiftTakeTransaction(slotStack, signedSnapshot, previous, holder);
    }

    /**
     * How many items were actually delivered, given the slot's remaining
     * count after the move.
     *
     * @param remainingCount slot count after {@code moveItemStackTo}
     * @return {@code originalCount - remainingCount}, clamped to >= 0
     */
    public int resolveMovedCount(int remainingCount) {
        int moved = this.originalCount - remainingCount;
        return Math.max(0, moved);
    }

    /**
     * Current remaining count of the real slot stack (mutated by the menu's
     * {@code moveItemStackTo}).
     */
    public int remainingCount() {
        return this.slotStack == null ? 0 : this.slotStack.getCount();
    }

    /**
     * Commits a successful take.
     *
     * @param remainingCount remaining in-slot count after the move
     * @return the event stack (signed snapshot trimmed to the delivered
     *         count), or {@code null} when nothing was delivered
     */
    @Nullable
    public ItemStack commit(int remainingCount) {
        if (this.published) {
            return null;
        }
        int moved = resolveMovedCount(remainingCount);
        if (moved <= 0) {
            return null;
        }
        this.published = true;
        // Partial move: the items left in the slot must not keep this take's
        // signature; restore the previous one (or remove the component).
        if (remainingCount > 0 && this.slotStack.getCount() > 0) {
            restoreSignature(this.slotStack, this.previousSignature);
        }
        this.eventSnapshot.setCount(moved);
        return this.eventSnapshot;
    }

    /**
     * Aborts a failed take: restores the slot stack's previous signature
     * (removes the one added by this take). Safe to call multiple times.
     */
    public void abort() {
        this.published = true; // never publish after abort
        if (this.slotStack != null && !this.slotStack.isEmpty()) {
            restoreSignature(this.slotStack, this.previousSignature);
        }
    }

    /** True once the transaction has published or been aborted. */
    public boolean isFinished() {
        return this.published;
    }

    /** Recipe id captured for this take (may be {@code null}). */
    @Nullable
    public net.minecraft.resources.ResourceLocation recipeId() {
        return this.recipeIdHolder.id;
    }

    /** Clears per-take state so the next take starts clean. */
    public void end() {
        this.recipeIdHolder.id = null;
    }

    private static void restoreSignature(ItemStack stack, @Nullable CookingSignature signature) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        DataComponentType<CookingSignature> sigType = CookingSignatureComponents.tryType();
        if (sigType == null) {
            return;
        }
        if (signature == null) {
            stack.remove(sigType);
        } else {
            stack.set(sigType, signature);
        }
    }
}
