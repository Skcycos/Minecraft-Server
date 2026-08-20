package com.tanrunn.tcth.impl.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;

/**
 * Real player-asset transfer engine for ITEM / HEALTH / HUNGER / EFFECT
 * (8C.1 / 8C.1.1 / 8C.1.2).
 *
 * <p><strong>Not wired into production</strong> —
 * {@link ShadowAttemptCoordinator#defaults()} keeps
 * {@link NoopShadowTransferExecutor}.
 *
 * <p>Rollback truthfulness (8C.1.2):
 * <ul>
 *   <li>every transaction classifies the world state into explicitly
 *       enumerated states (complete conjunctions of all relevant fields —
 *       never loose per-field ORs): PRE / intermediate / COMMITTED / FOREIGN;</li>
 *   <li><em>internal</em> rollback (commit exceptions, mismatches) accepts the
 *       enumerated intermediate states and COMMITTED; <em>external</em>
 *       rollback (the coordinator's final-audit-failure call) accepts ONLY
 *       the exact COMMITTED post-state;</li>
 *   <li>every restore writes the snapshots back and then RE-READS both sides,
 *       verifying every field — a no-op, clamped or partial write-back makes
 *       the restore return {@code false} (never a fabricated {@code true});</li>
 *   <li>EFFECT: the victim's {@code removeEffect} return value is honoured
 *       (a cancelled removal aborts the transfer), the four states
 *       PRE / VICTIM_REMOVED / VICTIM_REMAINDER_WRITTEN / COMMITTED are
 *       recognised with full field comparison, and a restore whose removal or
 *       re-application was cancelled/invalid returns {@code false}.</li>
 * </ul>
 *
 * <p>COIN is always refused.
 */
public final class PlayerAssetTransferExecutor implements ShadowTransferExecutor {

    public static final PlayerAssetTransferExecutor INSTANCE = new PlayerAssetTransferExecutor();

    /** Tolerance for float health comparisons. */
    static final float HEALTH_EPSILON = 1.0E-4f;

    private static Function<ItemStack, ResourceLocation> itemIdResolver =
            PlayerAssetTransferExecutor::defaultItemId;

    private PlayerAssetTransferExecutor() {
    }

    // ---- explicit transaction states ----

    private enum ItemState { PRE, VICTIM_REMOVED, COMMITTED, FOREIGN }

    private enum HealthState { PRE, VICTIM_REDUCED, COMMITTED, FOREIGN }

    private enum HungerState {
        PRE, VICTIM_FOOD_REDUCED, VICTIM_SAT_REDUCED, THIEF_FOOD_RAISED, COMMITTED, FOREIGN
    }

    private enum EffectState { PRE, VICTIM_REMOVED, VICTIM_REMAINDER_WRITTEN, COMMITTED, FOREIGN }

    // ---- prepare ----

    @Override
    @Nullable
    public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                      RandomSource random) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(random, "random");
        if (selected.type() == ShadowTheftType.COIN) {
            return null; // COIN is always refused
        }
        return switch (selected.type()) {
            case ITEM -> prepareItem(context, random);
            case HEALTH -> prepareHealth(context);
            case HUNGER -> prepareHunger(context);
            case EFFECT -> prepareEffect(context, random);
            case COIN -> null; // unreachable
        };
    }

    private @Nullable ShadowTransferPlan prepareItem(ShadowAttemptContext context, RandomSource random) {
        ServerPlayer victim = resolveVictim(context);
        if (victim == null) {
            return null;
        }
        Inventory victimInventory = victim.getInventory();
        Inventory thiefInventory = context.thief().getInventory();
        List<Integer> victimSlots = new ArrayList<>();
        List<Integer> thiefSlots = new ArrayList<>();
        for (int slot = 0; slot < PlayerReadonlyCandidateProvider.MAIN_INVENTORY_END_EXCLUSIVE; slot++) {
            ItemStack stack = victimInventory.getItem(slot);
            if (!PlayerReadonlyCandidateProvider.isStealable(stack)) {
                continue;
            }
            int mergeSlot = thiefInventory.getSlotWithRemainingSpace(stack);
            int freeSlot = thiefInventory.getFreeSlot();
            if (mergeSlot < 0 && freeSlot < 0) {
                continue; // not receivable
            }
            victimSlots.add(slot);
            thiefSlots.add(mergeSlot >= 0 ? mergeSlot : freeSlot);
        }
        if (victimSlots.isEmpty()) {
            return null;
        }
        int index = random.nextInt(victimSlots.size());
        int victimSlot = victimSlots.get(index);
        int thiefSlot = thiefSlots.get(index);
        ItemStack stack = victimInventory.getItem(victimSlot);
        // High-value penalty by 妙手 tier (phase 8E): the tag never gets
        // bypassed — only the penalty magnitude changes (-0.10 / -0.05 / 0).
        double highValueModifier = stack.is(ShadowTags.HIGH_VALUE_STEALABLE_ITEMS)
                ? ShadowAbilityValues.highValueModifier(context.abilities().sleight())
                : 0.0d;
        return new ItemPlan(victimSlot, victimInventory.getItem(victimSlot).copy(), thiefSlot,
                thiefInventory.getItem(thiefSlot).copy(), stack.copyWithCount(1),
                highValueModifier);
    }

    private @Nullable ShadowTransferPlan prepareHealth(ShadowAttemptContext context) {
        ServerPlayer victim = resolveVictim(context);
        if (victim == null) {
            return null;
        }
        float victimHealth = victim.getHealth();
        float thiefHealth = context.thief().getHealth();
        float thiefMax = context.thief().getMaxHealth();
        if (victimHealth <= PlayerReadonlyCandidateProvider.HEALTH_FLOOR
                || thiefHealth >= thiefMax) {
            return null;
        }
        // 夺生 tier transfer (1 / 2 / 4, phase 8E) — the SAME shared source
        // the candidate probe relies on; the protection lines never move.
        float transfer = Math.min(
                ShadowAbilityValues.lifeSiphonHealthTransfer(context.abilities().lifeSiphon()),
                Math.min(victimHealth - PlayerReadonlyCandidateProvider.HEALTH_FLOOR,
                        thiefMax - thiefHealth));
        if (transfer <= 0.0f) {
            return null;
        }
        return new HealthPlan(victimHealth, thiefHealth, transfer);
    }

    private @Nullable ShadowTransferPlan prepareHunger(ShadowAttemptContext context) {
        ServerPlayer victim = resolveVictim(context);
        if (victim == null) {
            return null;
        }
        // Shared feasibility (8C.1.3 §5) with the SAME tier-adjusted transfer
        // the candidate probe uses (phase 8E: 2 / 3 / 4) — one numeric source,
        // so a tier change can never make "candidate available" while
        // prepare (without drift) returns null.
        return ShadowFeasibility.computeHungerPlan(victim.getFoodData(),
                context.thief().getFoodData(),
                ShadowAbilityValues.lifeSiphonHungerTransfer(context.abilities().lifeSiphon()));
    }

    private @Nullable ShadowTransferPlan prepareEffect(ShadowAttemptContext context, RandomSource random) {
        ServerPlayer victim = resolveVictim(context);
        if (victim == null) {
            return null;
        }
        ServerPlayer thief = context.thief();
        List<MobEffectInstance> eligible = new ArrayList<>();
        for (MobEffectInstance instance : victim.getActiveEffects()) {
            // Shared feasibility (8C.1.3 §5): stealable rules + the thief
            // must not already hold the effect — identical to the probe.
            if (ShadowFeasibility.effectIsCandidateFor(thief, instance)) {
                eligible.add(instance);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        MobEffectInstance instance = eligible.get(random.nextInt(eligible.size()));
        // 窃法 tier cap (200 / 400 / 600 ticks, phase 8E) — the same shared
        // max-duration source the candidate layer documents; the receipt
        // records the REAL transferred duration.
        int transferTicks = Math.min(
                ShadowAbilityValues.spellTheftMaxTicks(context.abilities().spellTheft()),
                instance.getDuration());
        return new EffectPlan(instance.getEffect().unwrapKey().map(ResourceKey::location).orElseThrow(),
                instance.getAmplifier(), transferTicks, instance, null);
    }

    static boolean isStealableEffect(MobEffectInstance instance) {
        return ShadowFeasibility.isStealableEffect(instance);
    }

    // ---- commit ----

    @Override
    public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                       ShadowTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(selected, "selected");
        if (plan.type() != selected.type()) {
            return ShadowTransferResult.failed("plan_type_mismatch");
        }
        return switch (plan.type()) {
            case ITEM -> commitItem(context, (ItemPlan) plan);
            case HEALTH -> commitHealth(context, (HealthPlan) plan);
            case HUNGER -> commitHunger(context, (HungerPlan) plan);
            case EFFECT -> commitEffect(context, (EffectPlan) plan);
            case COIN -> ShadowTransferResult.failed("coin_blocked");
        };
    }

    private ShadowTransferResult commitItem(ShadowAttemptContext context, ItemPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return ShadowTransferResult.failed("target_drift");
            }
            Inventory victimInventory = victim.getInventory();
            Inventory thiefInventory = context.thief().getInventory();
            ItemStack current = victimInventory.getItem(plan.victimSlot());
            if (!ItemStack.isSameItemSameComponents(current, plan.victimStackBefore())
                    || current.getCount() != plan.victimStackBefore().getCount()) {
                return ShadowTransferResult.failed("slot_drift");
            }
            ItemStack thiefCurrent = thiefInventory.getItem(plan.thiefSlot());
            if (plan.thiefStackBefore().isEmpty()) {
                if (!thiefCurrent.isEmpty()) {
                    return ShadowTransferResult.failed("thief_slot_drift");
                }
            } else if (!ItemStack.isSameItemSameComponents(thiefCurrent, plan.thiefStackBefore())
                    || thiefCurrent.getCount() != plan.thiefStackBefore().getCount()) {
                return ShadowTransferResult.failed("thief_slot_drift");
            }
            ItemStack taken = victimInventory.removeItem(plan.victimSlot(), 1);
            if (taken.isEmpty()) {
                return ShadowTransferResult.failed("slot_drift");
            }
            if (plan.thiefStackBefore().isEmpty()) {
                thiefInventory.setItem(plan.thiefSlot(), taken);
            } else {
                thiefInventory.getItem(plan.thiefSlot()).grow(1);
            }
            // Post-commit verification (8C.1.3 §2): re-read BOTH slots — the
            // source must have lost exactly 1 and the receiver gained exactly
            // 1 with identical components. A no-op, clamped or wrong write is
            // restored internally.
            ItemStack victimAfter = victimInventory.getItem(plan.victimSlot());
            ItemStack thiefAfter = thiefInventory.getItem(plan.thiefSlot());
            if (!itemCommittedState(victimAfter, thiefAfter, plan)) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] ITEM commit write mismatch (event {}): victim={} thief={}",
                        context.eventId(), victimAfter, thiefAfter);
                boolean restored = restoreItem(context, plan, false);
                if (restored) {
                    return ShadowTransferResult.failed("item_commit_write_mismatch");
                }
                return ShadowTransferResult.recoveryRequired(
                        "item_commit_write_mismatch; internal_rollback_failed",
                        ShadowTheftReceipt.empty());
            }
            ResourceLocation itemId = itemIdOf(plan.selected());
            if (itemId == null) {
                boolean restored = restoreItem(context, plan, false);
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] ITEM commit found an unregistered item (event {})", context.eventId());
                if (restored) {
                    return ShadowTransferResult.failed("unregistered_item");
                }
                return ShadowTransferResult.recoveryRequired(
                        "unregistered_item; internal_rollback_failed", ShadowTheftReceipt.empty());
            }
            return ShadowTransferResult.committed(ShadowTheftReceipt.item(itemId, 1));
        } catch (RuntimeException | LinkageError e) {
            boolean restored = restoreItem(context, plan, false);
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] ITEM commit failed (event {}): {}", context.eventId(), e.toString());
            if (restored) {
                return ShadowTransferResult.failed("item_commit_exception");
            }
            return ShadowTransferResult.recoveryRequired("item_commit_exception; internal_rollback_failed",
                    ShadowTheftReceipt.empty());
        }
    }

    private ShadowTransferResult commitHealth(ShadowAttemptContext context, HealthPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return ShadowTransferResult.failed("target_drift");
            }
            float victimBefore = plan.victimHealthBefore();
            float thiefBefore = plan.thiefHealthBefore();
            if (!close(victim.getHealth(), victimBefore)
                    || !close(context.thief().getHealth(), thiefBefore)) {
                return ShadowTransferResult.failed("health_drift");
            }
            victim.setHealth(victimBefore - plan.transfer());
            context.thief().heal(plan.transfer());
            float actualGain = context.thief().getHealth() - thiefBefore;
            float actualLoss = victimBefore - victim.getHealth();
            if (actualGain <= 0.0f
                    || !close(actualGain, plan.transfer())
                    || !close(actualLoss, plan.transfer())) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] HEALTH commit heal mismatch (event {}): gain={} loss={} planned={}",
                        context.eventId(), actualGain, actualLoss, plan.transfer());
                boolean restored = restoreHealth(context, plan, false);
                if (restored) {
                    return ShadowTransferResult.failed("health_heal_mismatch");
                }
                return ShadowTransferResult.recoveryRequired(
                        "health_heal_mismatch; internal_rollback_failed",
                        ShadowTheftReceipt.numeric(plan.transfer()));
            }
            return ShadowTransferResult.committed(ShadowTheftReceipt.numeric(actualGain));
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] HEALTH commit failed (event {}): {}", context.eventId(), e.toString());
            boolean restored = restoreHealth(context, plan, false);
            if (restored) {
                return ShadowTransferResult.failed("health_commit_exception");
            }
            return ShadowTransferResult.recoveryRequired("health_commit_exception; internal_rollback_failed",
                    ShadowTheftReceipt.numeric(plan.transfer()));
        }
    }

    private ShadowTransferResult commitHunger(ShadowAttemptContext context, HungerPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return ShadowTransferResult.failed("target_drift");
            }
            FoodData victimFood = victim.getFoodData();
            FoodData thiefFood = context.thief().getFoodData();
            if (victimFood.getFoodLevel() != plan.victimFoodBefore()
                    || !close(victimFood.getSaturationLevel(), plan.victimSatBefore())
                    || thiefFood.getFoodLevel() != plan.thiefFoodBefore()
                    || !close(thiefFood.getSaturationLevel(), plan.thiefSatBefore())) {
                return ShadowTransferResult.failed("hunger_drift");
            }
            int foodTransfer = plan.foodTransfer();
            float satTransfer = plan.satTransfer();
            // Four independent writes (8C.1.3 §3); the final verification
            // compares ALL FOUR post values against the plan — saturation
            // legality alone never proves the transfer succeeded.
            victimFood.setFoodLevel(plan.victimFoodBefore() - foodTransfer);
            victimFood.setSaturation(plan.victimSatBefore() - satTransfer);
            thiefFood.setFoodLevel(plan.thiefFoodBefore() + foodTransfer);
            thiefFood.setSaturation(plan.thiefSatBefore() + satTransfer);
            if (!hungerCommittedState(victimFood, thiefFood, plan)) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] HUNGER commit write mismatch (event {})", context.eventId());
                boolean restored = restoreHunger(context, plan, false);
                if (restored) {
                    return ShadowTransferResult.failed("hunger_commit_write_mismatch");
                }
                return ShadowTransferResult.recoveryRequired(
                        "hunger_commit_write_mismatch; internal_rollback_failed",
                        ShadowTheftReceipt.numeric(foodTransfer));
            }
            return ShadowTransferResult.committed(ShadowTheftReceipt.numeric(foodTransfer));
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] HUNGER commit failed (event {}): {}", context.eventId(), e.toString());
            boolean restored = restoreHunger(context, plan, false);
            if (restored) {
                return ShadowTransferResult.failed("hunger_commit_exception");
            }
            return ShadowTransferResult.recoveryRequired("hunger_commit_exception; internal_rollback_failed",
                    ShadowTheftReceipt.numeric(plan.foodTransfer()));
        }
    }

    /** Shared COMMITTED predicate: all four post values equal the plan
     *  (8C.1.3 §3). */
    private static boolean hungerCommittedState(FoodData victimFood, FoodData thiefFood, HungerPlan plan) {
        return victimFood.getFoodLevel() == plan.victimFoodBefore() - plan.foodTransfer()
                && close(victimFood.getSaturationLevel(), plan.victimSatBefore() - plan.satTransfer())
                && thiefFood.getFoodLevel() == plan.thiefFoodBefore() + plan.foodTransfer()
                && close(thiefFood.getSaturationLevel(), plan.thiefSatBefore() + plan.satTransfer());
    }

    private ShadowTransferResult commitEffect(ShadowAttemptContext context, EffectPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return ShadowTransferResult.failed("target_drift");
            }
            Holder<MobEffect> effect = effectHolder(context, plan.effectId());
            if (effect == null) {
                return ShadowTransferResult.failed("effect_drift");
            }
            MobEffectInstance victimInstance = victim.getEffect(effect);
            if (!effectMatches(victimInstance, plan.victimInstanceBefore())) {
                return ShadowTransferResult.failed("effect_drift");
            }
            if (context.thief().hasEffect(effect)) {
                return ShadowTransferResult.failed("thief_effect_drift");
            }
            int actual = Math.min(plan.transferTicks(), plan.victimInstanceBefore().getDuration());
            if (actual <= 0) {
                return ShadowTransferResult.failed("effect_drift");
            }
            int remaining = plan.victimInstanceBefore().getDuration() - actual;

            // Step 1: remove the victim's effect. A cancelled removal aborts
            // the transfer — nothing further is written (8C.1.2 §2).
            boolean removed = victim.removeEffect(effect);
            if (!removed) {
                return ShadowTransferResult.failed("effect_remove_rejected");
            }

            // Step 2: write the victim's remainder (or leave absent when
            // fully drained), then VERIFY it stuck.
            if (remaining > 0) {
                victim.forceAddEffect(new MobEffectInstance(effect, remaining, plan.amplifier(),
                        plan.victimInstanceBefore().isAmbient(),
                        plan.victimInstanceBefore().isVisible(),
                        plan.victimInstanceBefore().showIcon()), context.thief());
            }
            if (!effectRemainderMatches(victim.getEffect(effect), plan, remaining)) {
                boolean restored = restoreEffect(context, plan, false);
                if (restored) {
                    return ShadowTransferResult.failed("effect_remainder_write_failed");
                }
                return ShadowTransferResult.recoveryRequired(
                        "effect_remainder_write_failed; internal_rollback_failed",
                        ShadowTheftReceipt.effect(plan.effectId(), actual));
            }

            // Step 3: write the thief's gain, then VERIFY it stuck.
            context.thief().forceAddEffect(
                    new MobEffectInstance(effect, actual, plan.amplifier(), false, true, true),
                    context.thief());
            MobEffectInstance thiefAfter = context.thief().getEffect(effect);
            boolean thiefOk = thiefAfter != null && thiefAfter.getDuration() == actual
                    && thiefAfter.getAmplifier() == plan.amplifier()
                    && !thiefAfter.isAmbient() && thiefAfter.isVisible() && thiefAfter.showIcon();
            if (!thiefOk) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] EFFECT commit thief write mismatch (event {}): thief={}",
                        context.eventId(), thiefAfter);
                boolean restored = restoreEffect(context, plan, false);
                if (restored) {
                    return ShadowTransferResult.failed("effect_thief_write_failed");
                }
                return ShadowTransferResult.recoveryRequired(
                        "effect_thief_write_failed; internal_rollback_failed",
                        ShadowTheftReceipt.effect(plan.effectId(), actual));
            }

            return ShadowTransferResult.committed(ShadowTheftReceipt.effect(plan.effectId(), actual));
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] EFFECT commit failed (event {}): {}", context.eventId(), e.toString());
            boolean restored = restoreEffect(context, plan, false);
            if (restored) {
                return ShadowTransferResult.failed("effect_commit_exception");
            }
            return ShadowTransferResult.recoveryRequired("effect_commit_exception; internal_rollback_failed",
                    ShadowTheftReceipt.effect(plan.effectId(), plan.transferTicks()));
        }
    }

    // ---- rollback (external: coordinator's final-audit-failure call) ----

    @Override
    public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected, ShadowTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(selected, "selected");
        if (plan.type() != selected.type()) {
            return false;
        }
        return switch (plan.type()) {
            case ITEM -> restoreItem(context, (ItemPlan) plan, true);
            case HEALTH -> restoreHealth(context, (HealthPlan) plan, true);
            case HUNGER -> restoreHunger(context, (HungerPlan) plan, true);
            case EFFECT -> restoreEffect(context, (EffectPlan) plan, true);
            case COIN -> false;
        };
    }

    // ---- state classifiers (complete conjunctions, never per-field ORs) ----

    private static ItemState classifyItem(ShadowAttemptContext context, ItemPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return ItemState.FOREIGN;
            }
            ItemStack victimCur = victim.getInventory().getItem(plan.victimSlot());
            ItemStack thiefCur = context.thief().getInventory().getItem(plan.thiefSlot());
            int before = plan.victimStackBefore().getCount();
            boolean victimPre = ItemStack.isSameItemSameComponents(victimCur, plan.victimStackBefore())
                    && victimCur.getCount() == before;
            // A single-item source leaves ItemStack.EMPTY behind — the legal
            // post-removal state (8C.1.3 §1).
            boolean victimRemoved = before == 1
                    ? victimCur.isEmpty()
                    : ItemStack.isSameItemSameComponents(victimCur, plan.victimStackBefore())
                            && victimCur.getCount() == before - 1;
            boolean thiefPre;
            if (plan.thiefStackBefore().isEmpty()) {
                thiefPre = thiefCur.isEmpty();
            } else {
                thiefPre = ItemStack.isSameItemSameComponents(thiefCur, plan.thiefStackBefore())
                        && thiefCur.getCount() == plan.thiefStackBefore().getCount();
            }
            boolean thiefPost = itemCommittedThiefState(thiefCur, plan);
            if (victimPre && thiefPre) {
                return ItemState.PRE;
            }
            if (victimRemoved && thiefPre) {
                return ItemState.VICTIM_REMOVED;
            }
            if (victimRemoved && thiefPost) {
                return ItemState.COMMITTED;
            }
            return ItemState.FOREIGN;
        } catch (RuntimeException | LinkageError e) {
            return ItemState.FOREIGN;
        }
    }

    /** Shared COMMITTED predicate for the thief slot (used by the classifier
     *  and the commit's post-verification — never two copies). */
    private static boolean itemCommittedThiefState(ItemStack thiefCur, ItemPlan plan) {
        if (plan.thiefStackBefore().isEmpty()) {
            return ItemStack.isSameItemSameComponents(thiefCur, plan.selected())
                    && thiefCur.getCount() == 1;
        }
        return ItemStack.isSameItemSameComponents(thiefCur, plan.thiefStackBefore())
                && thiefCur.getCount() == plan.thiefStackBefore().getCount() + 1;
    }

    /** Shared COMMITTED predicate for both slots (8C.1.3 §2). */
    private static boolean itemCommittedState(ItemStack victimCur, ItemStack thiefCur, ItemPlan plan) {
        int before = plan.victimStackBefore().getCount();
        boolean victimOk = before == 1
                ? victimCur.isEmpty()
                : ItemStack.isSameItemSameComponents(victimCur, plan.victimStackBefore())
                        && victimCur.getCount() == before - 1;
        return victimOk && itemCommittedThiefState(thiefCur, plan);
    }

    private static HealthState classifyHealth(ShadowAttemptContext context, HealthPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return HealthState.FOREIGN;
            }
            float victimH = victim.getHealth();
            float thiefH = context.thief().getHealth();
            boolean victimPre = close(victimH, plan.victimHealthBefore());
            boolean victimReduced = close(victimH, plan.victimHealthBefore() - plan.transfer());
            boolean thiefPre = close(thiefH, plan.thiefHealthBefore());
            boolean thiefCommitted = close(thiefH, plan.thiefHealthBefore() + plan.transfer());
            if (victimPre && thiefPre) {
                return HealthState.PRE;
            }
            // The transaction's own heal may have been modified by a
            // LivingHealEvent listener, so a reduced victim with a thief
            // anywhere in [before, before + transfer] is an owned state.
            if (victimReduced && thiefH >= plan.thiefHealthBefore() - HEALTH_EPSILON
                    && thiefH <= plan.thiefHealthBefore() + plan.transfer() + HEALTH_EPSILON) {
                return thiefCommitted ? HealthState.COMMITTED : HealthState.VICTIM_REDUCED;
            }
            return HealthState.FOREIGN;
        } catch (RuntimeException | LinkageError e) {
            return HealthState.FOREIGN;
        }
    }

    private static HungerState classifyHunger(ShadowAttemptContext context, HungerPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return HungerState.FOREIGN;
            }
            FoodData vf = victim.getFoodData();
            FoodData tf = context.thief().getFoodData();
            int f = plan.foodTransfer();
            float sat = plan.satTransfer();
            // Each of the four writes has its own explicit state — the
            // classifier is the complete conjunction of every field (8C.1.3
            // §3), never a per-field OR.
            boolean victimFoodPre = vf.getFoodLevel() == plan.victimFoodBefore();
            boolean victimFoodReduced = vf.getFoodLevel() == plan.victimFoodBefore() - f;
            boolean victimSatPre = close(vf.getSaturationLevel(), plan.victimSatBefore());
            boolean victimSatReduced = close(vf.getSaturationLevel(), plan.victimSatBefore() - sat);
            boolean thiefFoodPre = tf.getFoodLevel() == plan.thiefFoodBefore();
            boolean thiefFoodRaised = tf.getFoodLevel() == plan.thiefFoodBefore() + f;
            boolean thiefSatPre = close(tf.getSaturationLevel(), plan.thiefSatBefore());
            boolean thiefSatRaised = close(tf.getSaturationLevel(), plan.thiefSatBefore() + sat);
            if (victimFoodPre && victimSatPre && thiefFoodPre && thiefSatPre) {
                return HungerState.PRE;
            }
            if (victimFoodReduced && victimSatPre && thiefFoodPre && thiefSatPre) {
                return HungerState.VICTIM_FOOD_REDUCED;
            }
            if (victimFoodReduced && victimSatReduced && thiefFoodPre && thiefSatPre) {
                return HungerState.VICTIM_SAT_REDUCED;
            }
            if (victimFoodReduced && victimSatReduced && thiefFoodRaised && thiefSatPre) {
                return HungerState.THIEF_FOOD_RAISED;
            }
            if (victimFoodReduced && victimSatReduced && thiefFoodRaised && thiefSatRaised) {
                return HungerState.COMMITTED;
            }
            return HungerState.FOREIGN;
        } catch (RuntimeException | LinkageError e) {
            return HungerState.FOREIGN;
        }
    }

    private static EffectState classifyEffect(ShadowAttemptContext context, EffectPlan plan) {
        try {
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return EffectState.FOREIGN;
            }
            Holder<MobEffect> effect = effectHolder(context, plan.effectId());
            if (effect == null) {
                return EffectState.FOREIGN;
            }
            MobEffectInstance victimCur = victim.getEffect(effect);
            MobEffectInstance thiefCur = context.thief().getEffect(effect);
            int before = plan.victimInstanceBefore().getDuration();
            int actual = Math.min(plan.transferTicks(), before);
            int remaining = before - actual;
            boolean thiefAbsent = thiefCur == null;
            boolean thiefGained = thiefCur != null && thiefCur.getDuration() == actual
                    && thiefCur.getAmplifier() == plan.amplifier()
                    && !thiefCur.isAmbient() && thiefCur.isVisible() && thiefCur.showIcon();
            boolean victimSnapshot = effectMatches(victimCur, plan.victimInstanceBefore());
            boolean victimAbsent = victimCur == null;
            boolean victimRemainder = effectRemainderMatches(victimCur, plan, remaining);
            if (victimSnapshot && thiefAbsent) {
                return EffectState.PRE;
            }
            if (victimAbsent && thiefAbsent) {
                return EffectState.VICTIM_REMOVED;
            }
            if (victimRemainder && thiefAbsent) {
                return EffectState.VICTIM_REMAINDER_WRITTEN;
            }
            if (victimRemainder && thiefGained) {
                return EffectState.COMMITTED;
            }
            return EffectState.FOREIGN;
        } catch (RuntimeException | LinkageError e) {
            return EffectState.FOREIGN;
        }
    }

    /** Full field comparison (duration / amplifier / ambient / visible / icon). */
    private static boolean effectMatches(MobEffectInstance current, MobEffectInstance snapshot) {
        if (current == null || snapshot == null) {
            return false;
        }
        return current.getDuration() == snapshot.getDuration()
                && current.getAmplifier() == snapshot.getAmplifier()
                && current.isAmbient() == snapshot.isAmbient()
                && current.isVisible() == snapshot.isVisible()
                && current.showIcon() == snapshot.showIcon();
    }

    private static boolean effectRemainderMatches(@Nullable MobEffectInstance current, EffectPlan plan,
                                                  int remaining) {
        if (remaining <= 0) {
            return current == null;
        }
        if (current == null) {
            return false;
        }
        return current.getDuration() == remaining
                && current.getAmplifier() == plan.amplifier()
                && current.isAmbient() == plan.victimInstanceBefore().isAmbient()
                && current.isVisible() == plan.victimInstanceBefore().isVisible()
                && current.showIcon() == plan.victimInstanceBefore().showIcon();
    }

    // ---- internal / external restore with write-back verification ----

    private static boolean restoreItem(ShadowAttemptContext context, ItemPlan plan, boolean external) {
        try {
            ItemState state = classifyItem(context, plan);
            if (state == ItemState.FOREIGN) {
                return false; // never overwrite external changes
            }
            if (external && state != ItemState.COMMITTED) {
                return false; // external rollback accepts ONLY the exact post-state
            }
            if (!external && state == ItemState.PRE) {
                return true; // nothing changed — never re-write assets (8C.1.3 §4)
            }
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return false;
            }
            victim.getInventory().setItem(plan.victimSlot(), plan.victimStackBefore().copy());
            context.thief().getInventory().setItem(plan.thiefSlot(), plan.thiefStackBefore().copy());
            // Write-back verification: re-read both slots and compare.
            ItemStack victimAfter = victim.getInventory().getItem(plan.victimSlot());
            ItemStack thiefAfter = context.thief().getInventory().getItem(plan.thiefSlot());
            return ItemStack.isSameItemSameComponents(victimAfter, plan.victimStackBefore())
                    && victimAfter.getCount() == plan.victimStackBefore().getCount()
                    && ItemStack.isSameItemSameComponents(thiefAfter, plan.thiefStackBefore())
                    && thiefAfter.getCount() == plan.thiefStackBefore().getCount();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean restoreHealth(ShadowAttemptContext context, HealthPlan plan, boolean external) {
        try {
            HealthState state = classifyHealth(context, plan);
            if (state == HealthState.FOREIGN) {
                return false;
            }
            if (external && state != HealthState.COMMITTED) {
                return false;
            }
            if (!external && state == HealthState.PRE) {
                return true; // nothing changed — no heal/setHealth events (8C.1.3 §4)
            }
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return false;
            }
            victim.setHealth(plan.victimHealthBefore());
            context.thief().setHealth(plan.thiefHealthBefore());
            return close(victim.getHealth(), plan.victimHealthBefore())
                    && close(context.thief().getHealth(), plan.thiefHealthBefore());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean restoreHunger(ShadowAttemptContext context, HungerPlan plan, boolean external) {
        try {
            HungerState state = classifyHunger(context, plan);
            if (state == HungerState.FOREIGN) {
                return false;
            }
            if (external && state != HungerState.COMMITTED) {
                return false;
            }
            if (!external && state == HungerState.PRE) {
                return true; // nothing changed — never re-write food state (8C.1.3 §4)
            }
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return false;
            }
            FoodData vf = victim.getFoodData();
            FoodData tf = context.thief().getFoodData();
            vf.setFoodLevel(plan.victimFoodBefore());
            vf.setSaturation(plan.victimSatBefore());
            tf.setFoodLevel(plan.thiefFoodBefore());
            tf.setSaturation(plan.thiefSatBefore());
            return vf.getFoodLevel() == plan.victimFoodBefore()
                    && close(vf.getSaturationLevel(), plan.victimSatBefore())
                    && tf.getFoodLevel() == plan.thiefFoodBefore()
                    && close(tf.getSaturationLevel(), plan.thiefSatBefore());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean restoreEffect(ShadowAttemptContext context, EffectPlan plan, boolean external) {
        try {
            EffectState state = classifyEffect(context, plan);
            if (state == EffectState.FOREIGN) {
                return false;
            }
            if (external && state != EffectState.COMMITTED) {
                return false;
            }
            if (!external && state == EffectState.PRE) {
                return true; // nothing changed — no effect writes/events (8C.1.3 §4)
            }
            ServerPlayer victim = resolveVictim(context);
            if (victim == null) {
                return false;
            }
            Holder<MobEffect> effect = effectHolder(context, plan.effectId());
            if (effect == null) {
                return false;
            }
            victim.removeEffect(effect);
            context.thief().removeEffect(effect);
            victim.forceAddEffect(new MobEffectInstance(plan.victimInstanceBefore()), context.thief());
            if (plan.thiefInstanceBefore() != null) {
                context.thief().forceAddEffect(
                        new MobEffectInstance(plan.thiefInstanceBefore()), context.thief());
            }
            // Write-back verification: re-read BOTH sides field by field.
            MobEffectInstance victimAfter = victim.getEffect(effect);
            MobEffectInstance thiefAfter = context.thief().getEffect(effect);
            boolean victimOk = effectMatches(victimAfter, plan.victimInstanceBefore());
            boolean thiefOk = plan.thiefInstanceBefore() == null
                    ? thiefAfter == null
                    : effectMatches(thiefAfter, plan.thiefInstanceBefore());
            return victimOk && thiefOk;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ---- helpers ----

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= HEALTH_EPSILON;
    }

    @Nullable
    private static ServerPlayer resolveVictim(ShadowAttemptContext context) {
        net.minecraft.world.entity.player.Player player = context.level().getPlayerByUUID(context.targetId());
        if (player instanceof ServerPlayer serverPlayer
                && serverPlayer.isAlive()
                && !serverPlayer.isDeadOrDying()
                && serverPlayer.level() == context.level()) {
            return serverPlayer;
        }
        return null;
    }

    private static ResourceLocation defaultItemId(ItemStack stack) {
        return stack.getItemHolder().unwrapKey().map(ResourceKey::location).orElse(null);
    }

    @Nullable
    private static ResourceLocation itemIdOf(ItemStack stack) {
        return itemIdResolver.apply(stack);
    }

    @Nullable
    private static Holder<MobEffect> effectHolder(ShadowAttemptContext context, ResourceLocation effectId) {
        return context.level().registryAccess().registryOrThrow(Registries.MOB_EFFECT)
                .getHolder(effectId).orElse(null);
    }

    // ---- test hooks (not part of the public API) ----

    static void setItemIdResolverForTesting(Function<ItemStack, ResourceLocation> resolver) {
        itemIdResolver = resolver;
    }

    static void resetForTesting() {
        itemIdResolver = PlayerAssetTransferExecutor::defaultItemId;
    }
}
