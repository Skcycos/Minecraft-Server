package com.tanrunn.tcth.impl.shadow;

import java.util.ArrayList;
import java.util.List;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only player candidate probe (phase 8C.0).
 *
 * <p>Returns the currently available theft types for a PLAYER target without
 * modifying any state — no inventory, health, food or effect mutation, no
 * packets, no client-visible slot/effect/balance information. The probe only
 * reports <em>types</em>; the per-item/per-effect details stay server-side.
 *
 * <p>Rules (8C.0 §3):
 * <ul>
 *   <li>ITEM — victim main-inventory slots 0..35 only (armor and offhand are
 *       excluded); at least one stack that is neither {@code #tcth:unstealable_items}
 *       nor a container-component item; the thief must have space
 *       (a mergeable stack or a free slot);</li>
 *   <li>HEALTH — victim above the health floor and the thief not at full
 *       health;</li>
 *   <li>HUNGER — victim above the hunger floor and the thief not full;</li>
 *   <li>EFFECT — at least one positive, finite-duration, non-ambient effect on
 *       the victim that is whitelisted by {@code #tcth:stealable_effects} and
 *       not blacklisted by {@code #tcth:unstealable_effects};</li>
 *   <li>COIN — never produced (its transfer cannot be atomic yet).</li>
 * </ul>
 */
public final class PlayerReadonlyCandidateProvider implements ShadowCandidateProvider {

    /** Minimum victim health for a HEALTH candidate (stage 8A §5.3). */
    public static final float HEALTH_FLOOR = 2.0f;
    /** Minimum victim hunger for a HUNGER candidate (stage 8A §5.4). */
    public static final int HUNGER_FLOOR = 4;
    /** Main-inventory slot range (0..35); armor 36..39 and offhand 40 are
     *  never probed. */
    static final int MAIN_INVENTORY_END_EXCLUSIVE = 36;

    public static final PlayerReadonlyCandidateProvider INSTANCE = new PlayerReadonlyCandidateProvider();

    private PlayerReadonlyCandidateProvider() {
    }

    @Override
    public List<ShadowCandidate> provide(ShadowAttemptContext context) {
        if (context.targetKind() != ShadowTargetKind.PLAYER) {
            return List.of(); // entity probing belongs to a later phase
        }
        net.minecraft.world.entity.player.Player targetPlayer = context.level().getPlayerByUUID(context.targetId());
        if (!(targetPlayer instanceof ServerPlayer target)) {
            return List.of();
        }
        List<ShadowCandidate> candidates = new ArrayList<>(4);
        if (itemAvailable(target, context.thief())) {
            candidates.add(ShadowCandidate.plain(ShadowTheftType.ITEM, ShadowCandidatePool.DEFAULT_ITEM_WEIGHT));
        }
        if (healthAvailable(target, context.thief())) {
            candidates.add(ShadowCandidate.plain(ShadowTheftType.HEALTH, ShadowCandidatePool.DEFAULT_HEALTH_WEIGHT));
        }
        if (hungerAvailable(target, context.thief())) {
            candidates.add(ShadowCandidate.plain(ShadowTheftType.HUNGER, ShadowCandidatePool.DEFAULT_HUNGER_WEIGHT));
        }
        if (effectAvailable(target, context.thief())) {
            candidates.add(ShadowCandidate.plain(ShadowTheftType.EFFECT, ShadowCandidatePool.DEFAULT_EFFECT_WEIGHT));
        }
        // COIN is deliberately never probed: no atomic transfer exists.
        return List.copyOf(candidates);
    }

    private static boolean itemAvailable(ServerPlayer target, ServerPlayer thief) {
        Inventory targetInventory = target.getInventory();
        Inventory thiefInventory = thief.getInventory();
        // Per-stack capacity check (8C.0.1 §2): every stealable stack of the
        // victim's main inventory (0..35) is checked individually against the
        // thief's capacity (a mergeable partial stack OR a free slot). ITEM
        // is only available when at least ONE stack can really be received.
        // The probe never picks the final slot and never modifies anything.
        for (int slot = 0; slot < MAIN_INVENTORY_END_EXCLUSIVE; slot++) {
            ItemStack stack = targetInventory.getItem(slot);
            if (!isStealable(stack)) {
                continue;
            }
            if (thiefInventory.getSlotWithRemainingSpace(stack) >= 0
                    || thiefInventory.getFreeSlot() >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Read-only stealability: empty, tag-blacklisted or container items are
     *  excluded. Never looks at stack details beyond components. */
    static boolean isStealable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.is(ShadowTags.UNSTEALABLE_ITEMS)) {
            return false;
        }
        // Containers (shulker boxes, loot-table containers, …) are excluded
        // at the component level — never by display name.
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.CONTAINER_LOOT)) {
            return false;
        }
        return true;
    }

    private static boolean healthAvailable(ServerPlayer target, ServerPlayer thief) {
        return target.getHealth() > HEALTH_FLOOR
                && thief.getHealth() < thief.getMaxHealth();
    }

    private static boolean hungerAvailable(ServerPlayer target, ServerPlayer thief) {
        // Shared feasibility (8C.1.3 §5): the same conserving-plan rule the
        // engine's prepare uses — never two copies of the formula.
        return ShadowFeasibility.computeHungerPlan(target.getFoodData(), thief.getFoodData()) != null;
    }

    private static boolean effectAvailable(ServerPlayer target, ServerPlayer thief) {
        // Shared feasibility (8C.1.3 §5): stealable rules AND the thief must
        // not already hold the effect.
        for (MobEffectInstance instance : target.getActiveEffects()) {
            if (ShadowFeasibility.effectIsCandidateFor(thief, instance)) {
                return true;
            }
        }
        return false;
    }
}
