package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link PlayerReadonlyCandidateProvider} (phase 8C.0).
 *
 * <p>Covers the four read-only probes: ITEM (main inventory only, container /
 * tag exclusion, thief capacity), HEALTH (floor + thief not full), HUNGER
 * (floor + thief not full), EFFECT (whitelist / beneficial / finite /
 * non-ambient), COIN never, and the no-mutation guarantee.
 */
class PlayerReadonlyCandidateProviderTest {

    private ServerLevel level;
    private ServerPlayer target;
    private ServerPlayer thief;
    private Inventory targetInventory;
    private Inventory thiefInventory;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        target = mock(ServerPlayer.class);
        thief = mock(ServerPlayer.class);
        targetInventory = mock(Inventory.class);
        thiefInventory = mock(Inventory.class);
        when(target.getInventory()).thenReturn(targetInventory);
        when(thief.getInventory()).thenReturn(thiefInventory);
        when(level.getPlayerByUUID(any(UUID.class))).thenReturn(target);
        when(target.getHealth()).thenReturn(20.0f);
        when(target.getMaxHealth()).thenReturn(20.0f);
        when(thief.getHealth()).thenReturn(20.0f);
        when(thief.getMaxHealth()).thenReturn(20.0f);
        FoodData targetFood = mock(FoodData.class);
        FoodData thiefFood = mock(FoodData.class);
        when(target.getFoodData()).thenReturn(targetFood);
        when(thief.getFoodData()).thenReturn(thiefFood);
        when(targetFood.getFoodLevel()).thenReturn(20);
        when(thiefFood.getFoodLevel()).thenReturn(20);
        when(target.getActiveEffects()).thenReturn(List.of());
    }

    private ShadowAttemptContext context() {
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, level, new BlockPos(1, 2, 3), 1L, false, 1.0d, true);
    }

    private List<ShadowTheftType> probe() {
        return PlayerReadonlyCandidateProvider.INSTANCE.provide(context()).stream()
                .map(c -> c.type()).toList();
    }

    @Test
    void entityTargetsProduceNothing() {
        when(level.getPlayerByUUID(any(UUID.class))).thenReturn(null);
        assertEquals(List.of(), PlayerReadonlyCandidateProvider.INSTANCE.provide(context()));
        ShadowAttemptContext entityCtx = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.ENTITY, UUID.randomUUID(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "zombie"),
                level, new BlockPos(1, 2, 3), 1L, false, 1.0d, true);
        assertEquals(List.of(), PlayerReadonlyCandidateProvider.INSTANCE.provide(entityCtx));
    }

    @Test
    void coinIsNeverProduced() {
        for (int i = 0; i < 36; i++) {
            when(targetInventory.getItem(i)).thenReturn(new ItemStack(Items.DIAMOND));
        }
        when(thiefInventory.getFreeSlot()).thenReturn(0);
        List<ShadowTheftType> types = probe();
        assertFalse(types.contains(ShadowTheftType.COIN), "COIN must never enter the candidates");
    }

    // ---- ITEM ----

    @Test
    void itemCandidateRequiresMainInventoryItemAndThiefSpace() {
        when(targetInventory.getItem(0)).thenReturn(new ItemStack(Items.DIAMOND));
        when(thiefInventory.getFreeSlot()).thenReturn(0);
        assertTrue(probe().contains(ShadowTheftType.ITEM));
    }

    @Test
    void armorAndOffhandSlotsAreNeverProbed() {
        // Item only in the armor slot (36) — the probe scans 0..35 only.
        when(targetInventory.getItem(36)).thenReturn(new ItemStack(Items.NETHERITE_CHESTPLATE));
        when(thiefInventory.getFreeSlot()).thenReturn(0);
        assertFalse(probe().contains(ShadowTheftType.ITEM));
    }

    @Test
    void shulkerBoxIsNotStealable() {
        when(targetInventory.getItem(0)).thenReturn(new ItemStack(Items.SHULKER_BOX));
        when(thiefInventory.getFreeSlot()).thenReturn(0);
        assertFalse(probe().contains(ShadowTheftType.ITEM),
                "container items must never be ITEM candidates");
    }

    @Test
    void tagBlacklistedItemIsNotStealable() {
        ItemStack tagged = mock(ItemStack.class);
        when(tagged.isEmpty()).thenReturn(false);
        when(tagged.is(ShadowTags.UNSTEALABLE_ITEMS)).thenReturn(true);
        when(targetInventory.getItem(0)).thenReturn(tagged);
        when(thiefInventory.getFreeSlot()).thenReturn(0);
        assertFalse(probe().contains(ShadowTheftType.ITEM));
    }

    @Test
    void isStealableReadOnlyRules() {
        assertFalse(PlayerReadonlyCandidateProvider.isStealable(ItemStack.EMPTY));
        assertTrue(PlayerReadonlyCandidateProvider.isStealable(new ItemStack(Items.DIAMOND)));
        assertFalse(PlayerReadonlyCandidateProvider.isStealable(new ItemStack(Items.SHULKER_BOX)));
        ItemStack tagged = mock(ItemStack.class);
        when(tagged.isEmpty()).thenReturn(false);
        when(tagged.is(ShadowTags.UNSTEALABLE_ITEMS)).thenReturn(true);
        assertFalse(PlayerReadonlyCandidateProvider.isStealable(tagged));
    }

    @Test
    void itemCandidateRequiresThiefCapacity() {
        when(targetInventory.getItem(0)).thenReturn(new ItemStack(Items.DIAMOND));
        when(thiefInventory.getFreeSlot()).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(any())).thenReturn(-1);
        assertFalse(probe().contains(ShadowTheftType.ITEM));
    }

    @Test
    void itemCandidateRequiresCapacityPerStack() {
        // Regression (8C.0.1 §2): the FIRST stealable stack (diamond) cannot
        // be received, but the SECOND (emerald) has a mergeable slot in the
        // thief's inventory — ITEM must still be available.
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        ItemStack emerald = new ItemStack(Items.EMERALD);
        when(targetInventory.getItem(0)).thenReturn(diamond);
        when(targetInventory.getItem(1)).thenReturn(emerald);
        when(thiefInventory.getFreeSlot()).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(diamond)).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(emerald)).thenReturn(5);
        assertTrue(probe().contains(ShadowTheftType.ITEM),
                "a later mergeable stack must make ITEM available even when the first is unreceivable");
    }

    @Test
    void itemCandidateAbsentWhenNoStackIsReceivable() {
        // Every stealable stack individually unreceivable → no ITEM.
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        ItemStack emerald = new ItemStack(Items.EMERALD);
        when(targetInventory.getItem(0)).thenReturn(diamond);
        when(targetInventory.getItem(1)).thenReturn(emerald);
        when(thiefInventory.getFreeSlot()).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(diamond)).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(emerald)).thenReturn(-1);
        assertFalse(probe().contains(ShadowTheftType.ITEM));
    }

    @Test
    void itemCandidateAllowsMergeIntoPartialStack() {
        when(targetInventory.getItem(0)).thenReturn(new ItemStack(Items.DIAMOND));
        when(thiefInventory.getFreeSlot()).thenReturn(-1);
        when(thiefInventory.getSlotWithRemainingSpace(any())).thenReturn(3);
        assertTrue(probe().contains(ShadowTheftType.ITEM));
    }

    // ---- HEALTH / HUNGER ----

    @Test
    void healthCandidateRequiresTargetAboveFloorAndThiefNotFull() {
        when(target.getHealth()).thenReturn(10.0f);
        when(thief.getHealth()).thenReturn(15.0f);
        assertTrue(probe().contains(ShadowTheftType.HEALTH));
    }

    @Test
    void healthCandidateAbsentWhenTargetAtFloor() {
        when(target.getHealth()).thenReturn(PlayerReadonlyCandidateProvider.HEALTH_FLOOR);
        when(thief.getHealth()).thenReturn(15.0f);
        assertFalse(probe().contains(ShadowTheftType.HEALTH));
    }

    @Test
    void healthCandidateAbsentWhenThiefFull() {
        when(target.getHealth()).thenReturn(10.0f);
        assertFalse(probe().contains(ShadowTheftType.HEALTH));
    }

    @Test
    void hungerCandidateRequiresTargetAboveFloorAndThiefNotFull() {
        when(target.getFoodData().getFoodLevel()).thenReturn(10);
        when(thief.getFoodData().getFoodLevel()).thenReturn(10);
        assertTrue(probe().contains(ShadowTheftType.HUNGER));
    }

    @Test
    void hungerCandidateAbsentWhenTargetAtFloor() {
        when(target.getFoodData().getFoodLevel()).thenReturn(PlayerReadonlyCandidateProvider.HUNGER_FLOOR);
        when(thief.getFoodData().getFoodLevel()).thenReturn(10);
        assertFalse(probe().contains(ShadowTheftType.HUNGER));
    }

    @Test
    void hungerCandidateAbsentWhenThiefFull() {
        when(target.getFoodData().getFoodLevel()).thenReturn(10);
        assertFalse(probe().contains(ShadowTheftType.HUNGER));
    }

    // ---- EFFECT ----

    @SuppressWarnings("unchecked")
    private MobEffectInstance effect(Holder<MobEffect> holder, int duration, boolean ambient,
                                     boolean infinite) {
        MobEffectInstance instance = mock(MobEffectInstance.class);
        when(instance.getEffect()).thenReturn(holder);
        when(instance.getDuration()).thenReturn(duration);
        when(instance.isInfiniteDuration()).thenReturn(infinite);
        when(instance.isAmbient()).thenReturn(ambient);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Holder<MobEffect> holder(MobEffect effect, boolean whitelisted, boolean blacklisted) {
        Holder<MobEffect> holder = mock(Holder.class);
        when(holder.value()).thenReturn(effect);
        when(holder.is(ShadowTags.STEALABLE_EFFECTS)).thenReturn(whitelisted);
        when(holder.is(ShadowTags.UNSTEALABLE_EFFECTS)).thenReturn(blacklisted);
        return holder;
    }

    @Test
    void effectCandidateRequiresWhitelistedBeneficialFiniteNonAmbient() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), true, false), 200, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertTrue(probe().contains(ShadowTheftType.EFFECT));
    }

    @Test
    void effectCandidateAbsentWhenNotWhitelisted() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), false, false), 200, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT));
    }

    @Test
    void effectCandidateAbsentWhenBlacklisted() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), true, true), 200, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT), "the blacklist must win over the whitelist");
    }

    @Test
    void effectCandidateAbsentForHarmfulEffect() {
        MobEffectInstance instance =
                effect(holder(MobEffects.HARM.value(), true, false), 200, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT));
    }

    @Test
    void effectCandidateAbsentForInfiniteDuration() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), true, false), 200, false, true);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT));
    }

    @Test
    void effectCandidateAbsentForAmbientEffects() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), true, false), 200, true, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT),
                "ambient (beacon-style) effects are excluded: unidentifiable source");
    }

    @Test
    void effectCandidateAbsentForZeroDuration() {
        MobEffectInstance instance =
                effect(holder(MobEffects.REGENERATION.value(), true, false), 0, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(instance));
        assertFalse(probe().contains(ShadowTheftType.EFFECT));
    }

    // ---- no-mutation guarantee ----

    @Test
    void probingNeverMutatesAnyAsset() {
        // Snapshot every relevant asset, run the probe, compare.
        ItemStack[] targetStacks = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            ItemStack stack = new ItemStack(Items.DIAMOND);
            targetStacks[i] = stack.copy();
            when(targetInventory.getItem(i)).thenReturn(stack);
        }
        float targetHealth = target.getHealth();
        float targetMax = target.getMaxHealth();
        float thiefHealth = thief.getHealth();
        int targetFood = target.getFoodData().getFoodLevel();
        int thiefFood = thief.getFoodData().getFoodLevel();
        MobEffectInstance regen =
                effect(holder(MobEffects.REGENERATION.value(), true, false), 200, false, false);
        when(target.getActiveEffects()).thenReturn(List.of(regen));
        when(thiefInventory.getFreeSlot()).thenReturn(0);

        List<ShadowTheftType> types = probe();
        assertTrue(types.contains(ShadowTheftType.ITEM));
        assertTrue(types.contains(ShadowTheftType.EFFECT));

        for (int i = 0; i < 36; i++) {
            assertTrue(net.minecraft.world.item.ItemStack.matches(targetStacks[i], targetInventory.getItem(i)),
                    "slot " + i + " must be untouched after probing");
        }
        assertEquals(targetHealth, target.getHealth());
        assertEquals(targetMax, target.getMaxHealth());
        assertEquals(thiefHealth, thief.getHealth());
        assertEquals(targetFood, target.getFoodData().getFoodLevel());
        assertEquals(thiefFood, thief.getFoodData().getFoodLevel());
    }
}
