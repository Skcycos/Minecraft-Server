package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link PlayerAssetTransferExecutor} (phase 8C.1).
 *
 * <p>Covers the real ITEM / HEALTH / HUNGER / EFFECT transactions: exact
 * receipts, component preservation, floors and caps, uniform selection,
 * per-type drift fail-closed, internal rollback on commit exceptions
 * (FAILED_CLEAN vs RECOVERY_REQUIRED), exact outer rollback, and the
 * coordinator-level random call counts.
 */
class PlayerAssetTransferExecutorTest {

    private static final ResourceLocation DIAMOND =
            ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
    private static final ResourceLocation REGENERATION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "regeneration");

    private ServerLevel level;
    private ServerPlayer victim;
    private ServerPlayer thief;
    private Inventory victimInventory;
    private Inventory thiefInventory;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowTheftEventDispatcher.resetForTesting();
        ShadowTheftEventDispatcher.setEnabledSupplierForTesting(() -> true);
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        victim = mock(ServerPlayer.class);
        thief = mock(ServerPlayer.class);
        UUID victimId = UUID.randomUUID();
        UUID thiefId = UUID.randomUUID();
        when(victim.getUUID()).thenReturn(victimId);
        when(thief.getUUID()).thenReturn(thiefId);
        when(level.getPlayerByUUID(victimId)).thenReturn(victim);
        when(victim.level()).thenReturn(level);
        when(thief.level()).thenReturn(level);
        when(victim.isAlive()).thenReturn(true);
        when(victim.isDeadOrDying()).thenReturn(false);
        when(thief.canInteractWithEntity(any(net.minecraft.world.phys.AABB.class), anyDouble()))
                .thenReturn(true);
        when(victim.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
        // Real inventories for observable slot semantics.
        victimInventory = new Inventory(mock(Player.class));
        thiefInventory = new Inventory(mock(Player.class));
        when(victim.getInventory()).thenReturn(victimInventory);
        when(thief.getInventory()).thenReturn(thiefInventory);
        // Health harness.
        when(victim.getMaxHealth()).thenReturn(20.0f);
        when(thief.getMaxHealth()).thenReturn(20.0f);
        when(victim.getHealth()).thenReturn(20.0f);
        when(thief.getHealth()).thenReturn(20.0f);
        // Food harness (real FoodData objects).
        when(victim.getFoodData()).thenReturn(new FoodData());
        when(thief.getFoodData()).thenReturn(new FoodData());
        // Effect harness (empty by default).
        when(victim.getActiveEffects()).thenReturn(List.of());
        when(victim.getEffect(any())).thenReturn(null);
        when(thief.getEffect(any())).thenReturn(null);
        when(thief.hasEffect(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        ShadowTheftEventDispatcher.resetForTesting();
    }

    private ShadowAttemptContext context() {
        return new ShadowAttemptContext(UUID.randomUUID(), thief, com.tanrunn.tcth.api.shadow.ShadowTargetKind.PLAYER,
                victim.getUUID(), null, level, new net.minecraft.core.BlockPos(1, 2, 3),
                1_000L, false, 1.0d, true);
    }

    private ShadowCandidate candidate(ShadowTheftType type) {
        return ShadowCandidate.plain(type, 30);
    }

    /** Fills the thief's real inventory slots {@code [from, to)} so that
     *  {@code getFreeSlot()} returns {@code to}. */
    private void fillThiefSlots(int from, int to) {
        for (int i = from; i < to; i++) {
            thiefInventory.setItem(i, new ItemStack(Items.EMERALD, 1));
        }
    }

    // ---- ITEM ----

    @Test
    void itemCommitTransfersOneWithFullComponents() {
        ItemStack enchanted = new ItemStack(Items.DIAMOND_SWORD);
        enchanted.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Lucky Blade"));
        victimInventory.setItem(0, enchanted.copy());
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(anyInt())).thenReturn(0);

        ShadowTransferPlan plan = PlayerAssetTransferExecutor.INSTANCE.prepare(context(), candidate(ShadowTheftType.ITEM), random);
        assertNotNull(plan);
        assertTrue(plan instanceof ItemPlan);
        verify(random, times(1)).nextInt(anyInt());

        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(), candidate(ShadowTheftType.ITEM), plan);
        assertTrue(result.committed());
        assertEquals(DIAMOND_SWORD_ID, result.receipt().itemId());
        assertEquals(1, result.receipt().itemCount());
        // The single sword fully moved; the thief slot received a
        // full-component copy.
        assertTrue(victimInventory.getItem(0).isEmpty(), "the single sword fully moved");
        assertEquals(1, thiefInventory.getItem(5).getCount());
        assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                thiefInventory.getItem(5), enchanted));
    }

    private static final ResourceLocation DIAMOND_SWORD_ID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "diamond_sword");

    @Test
    void itemPrepareFailsWhenNothingReceivable() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 36); // full inventory: no free slot, no mergeable diamond
        RandomSource random = mock(RandomSource.class);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(), candidate(ShadowTheftType.ITEM), random),
                "full inventory must fail prepare cleanly (no re-draw)");
        verify(random, times(0)).nextInt(anyInt());
    }

    @Test
    void itemSelectsUniformlyAmongReceivableStacks() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 1));
        victimInventory.setItem(1, new ItemStack(Items.EMERALD, 1));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(2)).thenReturn(1);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertNotNull(plan);
        assertEquals(1, plan.victimSlot(), "the uniform selection must honour the random index");
        assertEquals(Items.EMERALD, plan.selected().getItem());
        verify(random, times(1)).nextInt(anyInt());
    }

    @Test
    void itemHighValueModifierApplies() {
        victimInventory.setItem(0, new ItemStack(Items.NETHERITE_INGOT, 1));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        // The item is not in the (empty) high-value tag in the test env;
        // force the plan's modifier through the tag-independent path by
        // checking the modifier of a mock-tagged stack instead.
        ItemStack tagged = mock(ItemStack.class);
        when(tagged.getCount()).thenReturn(1);
        when(tagged.copy()).thenReturn(tagged);
        when(tagged.is(ShadowTags.HIGH_VALUE_STEALABLE_ITEMS)).thenReturn(true);
        ItemPlan plan = new ItemPlan(0, victimInventory.getItem(0), 5, ItemStack.EMPTY, tagged);
        assertEquals(ItemPlan.HIGH_VALUE_MODIFIER, plan.successModifier());
        ItemPlan plain = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertEquals(0.0d, plain.successModifier());
    }

    @Test
    void itemSlotDriftFailsClean() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        victimInventory.setItem(0, new ItemStack(Items.EMERALD, 1)); // drift
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("slot_drift", result.failureReason());
    }

    @Test
    void itemThiefSlotDriftFailsClean() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        thiefInventory.setItem(5, new ItemStack(Items.DIAMOND, 64)); // no longer free
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("thief_slot_drift", result.failureReason());
    }

    @Test
    void itemCommitExceptionAfterPartialChangeRollsBackInternally() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        // Mocked thief inventory: the merge read throws on the SECOND
        // invocation (drift check ok, merge boom) — after the victim already
        // lost the item.
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        ItemStack mergeStack = new ItemStack(Items.DIAMOND, 10);
        when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(5);
        when(mockedThief.getFreeSlot()).thenReturn(-1);
        when(mockedThief.getItem(5)).thenReturn(mergeStack)
                .thenThrow(new RuntimeException("merge boom")).thenReturn(mergeStack);
        when(victim.getInventory()).thenReturn(victimInventory);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a restored internal rollback is a clean failure");
        assertEquals("item_commit_exception", result.failureReason());
        assertEquals(3, victimInventory.getItem(0).getCount(),
                "the victim slot must be exactly restored");
    }

    @Test
    void itemCommitInternalRollbackFailureIsRecoveryRequired() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        ItemStack mergeStack = new ItemStack(Items.DIAMOND, 10);
        when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(5);
        when(mockedThief.getFreeSlot()).thenReturn(-1);
        when(mockedThief.getItem(5)).thenReturn(mergeStack).thenThrow(new RuntimeException("merge boom"));
        doThrow(new RuntimeException("restore boom")).when(mockedThief).setItem(anyInt(), any());
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.RECOVERY_REQUIRED, result.state(),
                "a failed internal rollback must never be a plain failure");
        org.junit.jupiter.api.Assertions.assertNotNull(result.receipt(),
                "RECOVERY_REQUIRED must carry a receipt");
    }

    @Test
    void itemOuterRollbackRestoresBothSlotsExactly() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(), candidate(ShadowTheftType.ITEM), plan)
                .committed());
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.rollback(context(), candidate(ShadowTheftType.ITEM), plan));
        assertEquals(3, victimInventory.getItem(0).getCount());
        assertTrue(thiefInventory.getItem(5).isEmpty());
    }

    // ---- HEALTH ----

    private void healthHarness(float victimHealth, float thiefHealth, float thiefMax) {
        when(victim.getHealth()).thenReturn(victimHealth);
        when(thief.getHealth()).thenReturn(thiefHealth);
        when(thief.getMaxHealth()).thenReturn(thiefMax);
        final float[] victimH = { victimHealth };
        final float[] thiefH = { thiefHealth };
        doAnswer(i -> victimH[0] = i.getArgument(0)).when(victim).setHealth(anyFloat());
        when(victim.getHealth()).thenAnswer(i -> victimH[0]);
        doAnswer(i -> thiefH[0] = Math.min(thiefMax, thiefH[0] + (float) i.getArgument(0)))
                .when(thief).heal(anyFloat());
        doAnswer(i -> thiefH[0] = i.getArgument(0)).when(thief).setHealth(anyFloat());
        when(thief.getHealth()).thenAnswer(i -> thiefH[0]);
    }

    @Test
    void healthCommitTransfersOnePointWithinBounds() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(HealthPlan.BASE_TRANSFER, plan.transfer());
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertTrue(result.committed());
        assertEquals(1.0d, result.receipt().numericAmount(), 1.0E-6);
        assertEquals(9.0f, victim.getHealth());
        assertEquals(16.0f, thief.getHealth());
    }

    @Test
    void healthRespectsVictimFloor() {
        healthHarness(2.5f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(0.5f, plan.transfer(), 1.0E-6);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertTrue(result.committed());
        assertEquals(0.5d, result.receipt().numericAmount(), 1.0E-6);
        assertEquals(2.0f, victim.getHealth(), "the victim must never drop below the floor");
    }

    @Test
    void healthNeverOverhealsTheThief() {
        healthHarness(10.0f, 19.5f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(0.5f, plan.transfer(), 1.0E-6, "the transfer is capped by the thief's capacity");
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertTrue(result.committed());
        assertEquals(20.0f, thief.getHealth(), "no over-heal");
    }

    @Test
    void healthPrepareFailsWhenVictimAtFloorOrThiefFull() {
        healthHarness(2.0f, 15.0f, 20.0f);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class)));
        healthHarness(10.0f, 20.0f, 20.0f);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class)));
    }

    @Test
    void healthDriftFailsClean() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        healthHarness(2.0f, 15.0f, 20.0f); // the victim dropped to the floor
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("health_drift", result.failureReason());
    }

    @Test
    void healthCommitExceptionRollsBackInternally() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        // Thief heal throws AFTER the victim was reduced → internal rollback.
        doThrow(new RuntimeException("heal boom")).when(thief).heal(anyFloat());
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals(10.0f, victim.getHealth(), "the victim health must be restored exactly");
    }

    @Test
    void healthOuterRollbackRestoresBothSides() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan).committed());
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.HEALTH), plan));
        assertEquals(10.0f, victim.getHealth());
        assertEquals(15.0f, thief.getHealth());
    }

    // ---- HUNGER ----

    private FoodData foodOf(ServerPlayer player) {
        return player.getFoodData();
    }

    @Test
    void hungerCommitTransfersFoodAndSmallSaturation() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(2, plan.foodTransfer());
        assertTrue(plan.satTransfer() > 0.0f && plan.satTransfer() <= HungerPlan.MAX_SATURATION_TRANSFER);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertTrue(result.committed());
        assertEquals(2.0d, result.receipt().numericAmount(), 1.0E-6);
        assertEquals(10, vf.getFoodLevel());
        assertEquals(8, tf.getFoodLevel());
        assertEquals(8.0f - plan.satTransfer(), vf.getSaturationLevel(), 1.0E-5);
        assertEquals(2.0f + plan.satTransfer(), tf.getSaturationLevel(), 1.0E-5);
    }

    @Test
    void hungerRespectsVictimFloor() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(5);
        vf.setSaturation(1.0f);
        tf.setFoodLevel(6);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(1, plan.foodTransfer(), "only 1 point may leave a level-5 victim");
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertTrue(result.committed());
        assertEquals(4, vf.getFoodLevel(), "the victim must never drop below the hunger floor");
    }

    @Test
    void hungerRespectsThiefFullness() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        tf.setFoodLevel(19);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertNotNull(plan);
        assertEquals(1, plan.foodTransfer(), "only 1 point fits into the thief's 19/20");
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan).committed());
        assertEquals(20, tf.getFoodLevel());
    }

    @Test
    void hungerPrepareFailsWhenVictimAtFloorOrThiefFull() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(HungerPlan.HUNGER_FLOOR);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class)));
        vf.setFoodLevel(12);
        tf.setFoodLevel(20);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class)));
    }

    @Test
    void hungerOuterRollbackRestoresBothSides() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan).committed());
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.HUNGER), plan));
        assertEquals(12, vf.getFoodLevel());
        assertEquals(8.0f, vf.getSaturationLevel(), 1.0E-5);
        assertEquals(6, tf.getFoodLevel());
        assertEquals(2.0f, tf.getSaturationLevel(), 1.0E-5);
    }

    @Test
    void hungerDriftFailsClean() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        tf.setFoodLevel(6);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        vf.setFoodLevel(HungerPlan.HUNGER_FLOOR); // the victim dropped to the floor
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("hunger_drift", result.failureReason());
    }

    private FoodData mockThiefFood(int level, float sat, boolean throwOnce) {
        FoodData mockFood = mock(FoodData.class);
        when(mockFood.getFoodLevel()).thenReturn(level);
        when(mockFood.getSaturationLevel()).thenReturn(sat);
        if (throwOnce) {
            doThrow(new RuntimeException("food boom")).doNothing().when(mockFood).setFoodLevel(anyInt());
        } else {
            doThrow(new RuntimeException("food boom")).when(mockFood).setFoodLevel(anyInt());
            doThrow(new RuntimeException("restore boom")).when(mockFood).setSaturation(anyFloat());
        }
        return mockFood;
    }

    @Test
    void hungerCommitExceptionRollsBackInternally() {
        FoodData vf = foodOf(victim);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        // Thief food: real values for prepare, but setFoodLevel throws once
        // (after the victim was already reduced) and then succeeds, so the
        // internal restore can complete → FAILED_CLEAN.
        FoodData tf = mockThiefFood(6, 2.0f, true);
        when(thief.getFoodData()).thenReturn(tf);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a restored internal rollback is a clean failure");
        assertEquals(12, vf.getFoodLevel(), "the victim food must be restored exactly");
        assertEquals(8.0f, vf.getSaturationLevel(), 1.0E-5);
    }

    @Test
    void hungerCommitInternalRollbackFailureIsRecoveryRequired() {
        FoodData vf = foodOf(victim);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        // Thief food: every mutation throws — the internal restore cannot
        // complete → RECOVERY_REQUIRED, never a plain failure.
        FoodData tf = mockThiefFood(6, 2.0f, false);
        when(thief.getFoodData()).thenReturn(tf);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.RECOVERY_REQUIRED, result.state());
        assertEquals(2.0d, result.receipt().numericAmount(), 1.0E-6);
    }

    @Test
    void effectThiefStrongerAtCommitFailsClean() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 1, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        // The thief gains a stronger effect between prepare and commit.
        thiefEffects.put(REGENERATION, new MobEffectInstance(regen, 400, 2, false, true, true));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("thief_effect_drift", result.failureReason());
    }

    // ---- EFFECT ----

    @SuppressWarnings("unchecked")
    private Holder<MobEffect> mockEffectHolder(ResourceLocation id, boolean whitelisted, boolean blacklisted) {
        Holder<MobEffect> holder = mock(Holder.class);
        when(holder.value()).thenReturn(MobEffects.REGENERATION.value());
        when(holder.is(ShadowTags.STEALABLE_EFFECTS)).thenReturn(whitelisted);
        when(holder.is(ShadowTags.UNSTEALABLE_EFFECTS)).thenReturn(blacklisted);
        when(holder.unwrapKey()).thenReturn(Optional.of(ResourceKey.create(Registries.MOB_EFFECT, id)));
        return holder;
    }

    private void effectHarness(Map<ResourceLocation, MobEffectInstance> victimEffects,
                               Map<ResourceLocation, MobEffectInstance> thiefEffects) {
        when(victim.getActiveEffects()).thenAnswer(i -> List.copyOf(victimEffects.values()));
        when(victim.getEffect(any())).thenAnswer(i -> victimEffects.get(
                ((Holder<MobEffect>) i.getArgument(0)).unwrapKey().orElseThrow().location()));
        when(victim.removeEffect(any())).thenAnswer(i -> victimEffects.remove(
                ((Holder<MobEffect>) i.getArgument(0)).unwrapKey().orElseThrow().location()) != null);
        doAnswer(i -> {
            MobEffectInstance added = i.getArgument(0);
            victimEffects.put(added.getEffect().unwrapKey().orElseThrow().location(), added);
            return null;
        }).when(victim).forceAddEffect(any(), any());
        when(thief.getEffect(any())).thenAnswer(i -> thiefEffects.get(
                ((Holder<MobEffect>) i.getArgument(0)).unwrapKey().orElseThrow().location()));
        when(thief.hasEffect(any())).thenAnswer(i -> thiefEffects.containsKey(
                ((Holder<MobEffect>) i.getArgument(0)).unwrapKey().orElseThrow().location()));
        doAnswer(i -> {
            MobEffectInstance added = i.getArgument(0);
            thiefEffects.put(added.getEffect().unwrapKey().orElseThrow().location(), added);
            return null;
        }).when(thief).forceAddEffect(any(), any());
        when(thief.removeEffect(any())).thenAnswer(i -> thiefEffects.remove(
                ((Holder<MobEffect>) i.getArgument(0)).unwrapKey().orElseThrow().location()) != null);
    }

    @Test
    void effectCommitTransfersUpToTwoHundredTicksAtSameAmplifier() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 1, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertNotNull(plan);
        assertEquals(EffectPlan.BASE_MAX_TRANSFER_TICKS, plan.transferTicks());
        verify(random, times(1)).nextInt(anyInt());
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        if (!result.committed()) {
            System.out.println("EFFECT-DIAG state=" + result.state() + " reason=" + result.failureReason()
                    + " plan=" + plan);
        }
        assertTrue(result.committed());
        assertEquals(REGENERATION, result.receipt().effectId());
        assertEquals(EffectPlan.BASE_MAX_TRANSFER_TICKS, result.receipt().effectDurationTicks());
        // Victim keeps the remainder; thief receives exactly the transfer.
        assertEquals(300, victimEffects.get(REGENERATION).getDuration());
        assertEquals(1, victimEffects.get(REGENERATION).getAmplifier());
        assertEquals(200, thiefEffects.get(REGENERATION).getDuration());
        assertEquals(1, thiefEffects.get(REGENERATION).getAmplifier(), "the amplifier is never raised");
    }

    @Test
    void effectTransferIsCappedByVictimRemainingTime() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 50, 0, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertNotNull(plan);
        assertEquals(50, plan.transferTicks());
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertTrue(result.committed());
        assertEquals(50, result.receipt().effectDurationTicks());
        assertFalse(victimEffects.containsKey(REGENERATION), "a fully-drained effect is removed");
        assertEquals(50, thiefEffects.get(REGENERATION).getDuration());
    }

    @Test
    void effectPrepareExcludesStrongerThief() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 1, false, true, true));
        thiefEffects.put(REGENERATION, new MobEffectInstance(regen, 400, 2, false, true, true)); // stronger
        RandomSource random = mock(RandomSource.class);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random),
                "a stronger existing thief effect must remove the effect from the options");
        verify(random, times(0)).nextInt(anyInt());
    }

    @Test
    void effectPrepareExcludesLongerSameAmplifierThief() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 100, 1, false, true, true));
        thiefEffects.put(REGENERATION, new MobEffectInstance(regen, 400, 1, false, true, true));
        RandomSource random = mock(RandomSource.class);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random));
    }

    @Test
    void effectPrepareFiltersAmbientHarmfulAndBlacklisted() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 0, true, true, true)); // ambient
        RandomSource random = mock(RandomSource.class);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random), "ambient effects are never selected");
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 0, false, true, true));
        // Blacklisted now.
        Holder<MobEffect> blacklisted = mockEffectHolder(REGENERATION, true, true);
        victimEffects.put(REGENERATION, new MobEffectInstance(blacklisted, 500, 0, false, true, true));
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random));
        // Not whitelisted.
        Holder<MobEffect> unlisted = mockEffectHolder(REGENERATION, false, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(unlisted, 500, 0, false, true, true));
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random));
    }

    @Test
    void effectDriftFailsClean() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 0, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        victimEffects.remove(REGENERATION); // the victim lost the effect
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("effect_drift", result.failureReason());
    }

    @Test
    void effectOuterRollbackRestoresBothSidesExactly() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance victimBefore = new MobEffectInstance(regen, 500, 1, false, true, true);
        victimEffects.put(REGENERATION, victimBefore);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan).committed());
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.EFFECT), plan));
        assertEquals(500, victimEffects.get(REGENERATION).getDuration());
        assertEquals(1, victimEffects.get(REGENERATION).getAmplifier());
        assertFalse(thiefEffects.containsKey(REGENERATION), "a thief without the effect stays without it");
    }

    // ---- 8C.1.1 conservation regressions ----

    @Test
    void unregisteredItemRestoresCleanly() {
        PlayerAssetTransferExecutor.setItemIdResolverForTesting(stack -> null);
        try {
            victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
            fillThiefSlots(0, 5);
            RandomSource random = mock(RandomSource.class);
            when(random.nextInt(1)).thenReturn(0);
            ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                    candidate(ShadowTheftType.ITEM), random);
            ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                    candidate(ShadowTheftType.ITEM), plan);
            assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                    "an unregistered item with a restored rollback is a clean failure");
            assertEquals("unregistered_item", result.failureReason());
            assertEquals(3, victimInventory.getItem(0).getCount(), "the victim slot must be restored");
            assertTrue(thiefInventory.getItem(5).isEmpty(), "the thief slot must be restored");
        } finally {
            PlayerAssetTransferExecutor.resetForTesting();
        }
    }

    @Test
    void unregisteredItemRestoreFailureIsRecoveryRequired() {
        PlayerAssetTransferExecutor.setItemIdResolverForTesting(stack -> null);
        try {
            victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
            Inventory mockedThief = mock(Inventory.class);
            when(thief.getInventory()).thenReturn(mockedThief);
            when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(5);
            when(mockedThief.getFreeSlot()).thenReturn(-1);
            ItemStack mergeStack = new ItemStack(Items.DIAMOND, 10);
            when(mockedThief.getItem(5)).thenReturn(mergeStack);
            doThrow(new RuntimeException("restore boom")).when(mockedThief).setItem(anyInt(), any());
            RandomSource random = mock(RandomSource.class);
            when(random.nextInt(1)).thenReturn(0);
            ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                    candidate(ShadowTheftType.ITEM), random);
            ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                    candidate(ShadowTheftType.ITEM), plan);
            assertEquals(ShadowTransferState.RECOVERY_REQUIRED, result.state(),
                    "a failed restore of an unregistered item is RECOVERY_REQUIRED");
            assertEquals("unregistered_item; internal_rollback_failed", result.failureReason());
        } finally {
            PlayerAssetTransferExecutor.resetForTesting();
        }
    }

    @Test
    void healthStrictSnapshotDriftFailsClean() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        // The victim heals between prepare and commit: any change fails
        // clean — the engine must NOT recompute and continue.
        healthHarness(11.0f, 15.0f, 20.0f);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("health_drift", result.failureReason());
    }

    @Test
    void healthZeroActualHealFailsCleanWithFullRestore() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        // A LivingHealEvent-style cancellation: heal() does nothing.
        doAnswer(i -> {
            /* cancelled */ return null;
        }).when(thief).heal(anyFloat());
        final float[] victimH = { 10.0f };
        doAnswer(i -> victimH[0] = i.getArgument(0)).when(victim).setHealth(anyFloat());
        when(victim.getHealth()).thenAnswer(i -> victimH[0]);
        final float[] thiefH = { 15.0f };
        when(thief.getHealth()).thenAnswer(i -> thiefH[0]);
        doAnswer(i -> thiefH[0] = i.getArgument(0)).when(thief).setHealth(anyFloat());

        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a zero actual heal is a clean mismatch, never a committed transfer");
        assertEquals("health_heal_mismatch", result.failureReason());
        assertEquals(10.0f, victim.getHealth(), "the victim must be fully restored");
        assertEquals(15.0f, thief.getHealth(), "the thief must be fully restored");
    }

    @Test
    void healthPartialHealMismatchFailsClean() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        // A LivingHealEvent-style modification: the heal only applies 0.5.
        final float[] thiefH = { 15.0f };
        doAnswer(i -> thiefH[0] = thiefH[0] + 0.5f).when(thief).heal(anyFloat());
        when(thief.getHealth()).thenAnswer(i -> thiefH[0]);
        doAnswer(i -> thiefH[0] = i.getArgument(0)).when(thief).setHealth(anyFloat());
        final float[] victimH = { 10.0f };
        doAnswer(i -> victimH[0] = i.getArgument(0)).when(victim).setHealth(anyFloat());
        when(victim.getHealth()).thenAnswer(i -> victimH[0]);

        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a partial heal must never commit a 1.0 deduction for a 0.5 gain");
        assertEquals("health_heal_mismatch", result.failureReason());
        assertEquals(10.0f, victim.getHealth());
        assertEquals(15.0f, thief.getHealth());
    }

    @Test
    void hungerHighSaturationInfeasibleProducesNoPlan() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(10);
        vf.setSaturation(9.5f); // post-transfer victim sat would exceed food
        tf.setFoodLevel(6);
        tf.setSaturation(0.5f);
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class)),
                "an infeasible saturation budget must not produce a plan");
    }

    @Test
    void hungerSaturationTransferStaysWithinFoodLevels() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertNotNull(plan);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan).committed());
        assertTrue(vf.getSaturationLevel() >= 0.0f && vf.getSaturationLevel() <= vf.getFoodLevel(),
                "victim saturation must stay within [0, foodLevel]");
        assertTrue(tf.getSaturationLevel() >= 0.0f && tf.getSaturationLevel() <= tf.getFoodLevel(),
                "thief saturation must stay within [0, foodLevel]");
    }

    @Test
    void hungerSaturationDriftFailsClean() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        vf.setSaturation(1.0f); // drift
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("hunger_drift", result.failureReason());
    }

    @Test
    void protocolTypeMismatchRefusesCommitAndRollback() {
        ShadowCandidate item = candidate(ShadowTheftType.ITEM);
        ShadowTransferPlan wrong = new ShadowTransferPlan.Generic(ShadowTheftType.HEALTH);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(), item, wrong);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("plan_type_mismatch", result.failureReason());
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(), item, wrong),
                "a type mismatch must never roll back");
    }

    @Test
    void itemRollbackRefusesExternalSlotChanges() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan).committed());
        // An external change after the commit: the victim slot now holds a
        // DIFFERENT item — the rollback must refuse to overwrite it.
        victimInventory.setItem(0, new ItemStack(Items.EMERALD, 9));
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.ITEM), plan),
                "an externally changed slot must never be overwritten");
        assertEquals(9, victimInventory.getItem(0).getCount(), "the external stack must survive");
    }

    @Test
    void healthRollbackRefusesExternalChanges() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan).committed());
        healthHarness(3.0f, 8.0f, 20.0f); // external changes
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.HEALTH), plan),
                "externally changed health must never be overwritten");
    }

    @Test
    void coordinatorRejectsPlanTypeMismatchWithoutRoll() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        // The executor lies about the plan type (HEALTH plan for ITEM).
        ShadowFrameworkSettings settings = new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        InMemoryAudit audit = new InMemoryAudit();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> settings,
                ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30)),
                new ShadowTransferExecutor() {
                    @Override
                    public ShadowTransferPlan prepare(ShadowAttemptContext c, ShadowCandidate s,
                                                      RandomSource r) {
                        return new ShadowTransferPlan.Generic(ShadowTheftType.HEALTH); // wrong
                    }

                    @Override
                    public ShadowTransferResult commit(ShadowAttemptContext c, ShadowCandidate s,
                                                       ShadowTransferPlan p) {
                        throw new AssertionError("commit must never run");
                    }

                    @Override
                    public boolean rollback(ShadowAttemptContext c, ShadowCandidate s,
                                            ShadowTransferPlan p) {
                        throw new AssertionError("rollback must never run");
                    }
                },
                ctx -> ShadowProtectionResult.ALLOWED,
                new ShadowCooldownTracker(), new ShadowIdempotencyTracker(),
                lvl -> audit, level -> new FakeDailyLimits(),() -> random, () -> 1L, () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, result.outcome());
        assertEquals("plan_type_mismatch", result.failureReason());
        verify(random, times(1)).nextLong();
        verify(random, times(0)).nextDouble();
        assertEquals(3, victimInventory.getItem(0).getCount(), "no asset may move");
    }

    // ---- 8C.1.2 rollback truthfulness regressions ----

    @Test
    void effectCancelledRemovalAbortsTheTransfer() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance victimBefore = new MobEffectInstance(regen, 500, 1, false, true, true);
        victimEffects.put(REGENERATION, victimBefore);
        // The removal is cancelled: removeEffect returns false. doReturn is
        // used so the previous answer never sees the stubbing placeholder.
        org.mockito.Mockito.doReturn(false).when(victim).removeEffect(any());
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("effect_remove_rejected", result.failureReason());
        assertTrue(thiefEffects.isEmpty(), "the thief must never receive the effect after a cancelled removal");
        assertTrue(victimEffects.containsKey(REGENERATION), "the victim's effect must be untouched");
    }

    @Test
    void effectRestoreWithCancelledThiefRemovalReturnsFalse() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance victimBefore = new MobEffectInstance(regen, 500, 1, false, true, true);
        victimEffects.put(REGENERATION, victimBefore);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan).committed());
        // The thief still holds the gained effect (its removal is cancelled
        // during restore) — the re-read verification must fail.
        org.mockito.Mockito.doReturn(false).when(thief).removeEffect(any());
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.EFFECT), plan),
                "a restore that cannot remove the thief's gain must never return true");
    }

    @Test
    void effectRestoreWithNoOpForceAddReturnsFalse() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 1, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan).committed());
        // The victim's forceAddEffect becomes a no-op during restore: the
        // snapshot is never written back and the re-read fails.
        doAnswer(i -> {
            /* no-op */ return null;
        }).when(victim).forceAddEffect(any(), any());
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.EFFECT), plan),
                "a restore whose write-back never stuck must return false");
    }

    @Test
    void effectInternalRestoreFromEveryIntermediateState() {
        // PRE: nothing changed — an early failure restores exactly.
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance victimBefore = new MobEffectInstance(regen, 500, 1, false, true, true);
        victimEffects.put(REGENERATION, victimBefore);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);

        // VICTIM_REMOVED: the remainder write throws → internal restore.
        doThrow(new RuntimeException("remainder boom"))
                .doAnswer(i -> {
                    MobEffectInstance added = i.getArgument(0);
                    victimEffects.put(added.getEffect().unwrapKey().orElseThrow().location(), added);
                    return null;
                }).when(victim).forceAddEffect(any(), any());
        ShadowTransferResult removed = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, removed.state(), "VICTIM_REMOVED restore succeeds");
        assertEquals("effect_commit_exception", removed.failureReason());
        assertTrue(instanceEquals(victimEffects.get(REGENERATION), victimBefore),
                "the victim snapshot must return");
        assertTrue(thiefEffects.isEmpty());
    }

    @Test
    void effectRestoreFromRemainderWrittenState() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance victimBefore = new MobEffectInstance(regen, 500, 1, false, true, true);
        victimEffects.put(REGENERATION, victimBefore);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        // VICTIM_REMAINDER_WRITTEN: the thief write throws → internal restore.
        doThrow(new RuntimeException("thief write boom")).when(thief).forceAddEffect(any(), any());
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "VICTIM_REMAINDER_WRITTEN restore succeeds");
        assertEquals("effect_commit_exception", result.failureReason());
        assertTrue(instanceEquals(victimEffects.get(REGENERATION), victimBefore),
                "the victim snapshot must return");
        assertTrue(thiefEffects.isEmpty());
    }

    /** Field-by-field MobEffectInstance comparison (local copy of the
     *  engine's private matcher). */
    private static boolean instanceEquals(MobEffectInstance a, MobEffectInstance b) {
        return a != null && b != null
                && a.getDuration() == b.getDuration()
                && a.getAmplifier() == b.getAmplifier()
                && a.isAmbient() == b.isAmbient()
                && a.isVisible() == b.isVisible()
                && a.showIcon() == b.showIcon();
    }

    @Test
    void effectExternalLookalikeEffectIsNotOverwritten() {
        Map<ResourceLocation, MobEffectInstance> victimEffects = new HashMap<>();
        Map<ResourceLocation, MobEffectInstance> thiefEffects = new HashMap<>();
        effectHarness(victimEffects, thiefEffects);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        victimEffects.put(REGENERATION, new MobEffectInstance(regen, 500, 1, false, true, true));
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        EffectPlan plan = (EffectPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.EFFECT), plan).committed());
        // An external effect with the SAME duration but a DIFFERENT amplifier:
        // the owned-state classifier must reject it (FOREIGN).
        thiefEffects.put(REGENERATION, new MobEffectInstance(regen, 200, 2, false, true, true));
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.EFFECT), plan),
                "an external lookalike effect must never be overwritten");
        assertEquals(2, thiefEffects.get(REGENERATION).getAmplifier(), "the external effect must survive");
        // Same duration, different flags — also FOREIGN.
        thiefEffects.put(REGENERATION, new MobEffectInstance(regen, 200, 1, true, true, true));
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.EFFECT), plan));
    }

    @Test
    void itemRestoreNoOpWriteNeverReportsTrue() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan).committed());
        // The thief's setItem becomes a no-op: the write-back never sticks.
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        ItemStack gained = new ItemStack(Items.DIAMOND, 1);
        when(mockedThief.getItem(5)).thenReturn(gained);
        doNothing2(mockedThief);
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.ITEM), plan),
                "a no-op write-back must never report a successful restore");
    }

    private static void doNothing2(Inventory mockedThief) {
        org.mockito.Mockito.doNothing().when(mockedThief).setItem(anyInt(), any());
    }

    @Test
    void healthRestoreNoOpWriteNeverReportsTrue() {
        healthHarness(10.0f, 15.0f, 20.0f);
        HealthPlan plan = (HealthPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HEALTH), mock(RandomSource.class));
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HEALTH), plan).committed());
        // Thief setHealth becomes a no-op; getHealth keeps returning the post value.
        when(thief.getHealth()).thenReturn(16.0f);
        doAnswer(i -> {
            /* no-op */ return null;
        }).when(thief).setHealth(anyFloat());
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.HEALTH), plan),
                "a no-op health write-back must never report true");
    }

    @Test
    void hungerRestoreNoOpWriteNeverReportsTrue() {
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan).committed());
        // The thief's food writes become no-ops; the getters keep the post values.
        FoodData mockTf = mock(FoodData.class);
        when(thief.getFoodData()).thenReturn(mockTf);
        when(mockTf.getFoodLevel()).thenReturn(8);
        when(mockTf.getSaturationLevel()).thenReturn(3.0f);
        doAnswer(i -> { /* no-op */ return null; }).when(mockTf).setFoodLevel(anyInt());
        doAnswer(i -> { /* no-op */ return null; }).when(mockTf).setSaturation(anyFloat());
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.HUNGER), plan),
                "a no-op food write-back must never report true");
    }

    @Test
    void externalRollbackRefusesIntermediateItemState() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan).committed());
        // Revert the thief slot back to empty → the state is the intermediate
        // VICTIM_REMOVED, NOT the committed post-state.
        thiefInventory.setItem(5, ItemStack.EMPTY);
        assertFalse(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.ITEM), plan),
                "external rollback must refuse an intermediate state");
        assertEquals(2, victimInventory.getItem(0).getCount(), "nothing may change");
    }

    // ---- 8C.1.3 commit truthfulness & pool consistency regressions ----

    @Test
    void itemSingleItemStackCommitsAndRollsBackExactly() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 1)); // single item
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertTrue(result.committed(), "a single-item source commits normally");
        assertTrue(victimInventory.getItem(0).isEmpty(),
                "a single-item source must leave ItemStack.EMPTY behind");
        assertEquals(1, thiefInventory.getItem(5).getCount());
        // External rollback from the exact post-state restores the item.
        assertTrue(PlayerAssetTransferExecutor.INSTANCE.rollback(context(),
                candidate(ShadowTheftType.ITEM), plan));
        assertEquals(1, victimInventory.getItem(0).getCount());
        assertTrue(thiefInventory.getItem(5).isEmpty());
    }

    @Test
    void itemSingleItemInternalRestoreFromRemovedState() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 1));
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(-1);
        when(mockedThief.getFreeSlot()).thenReturn(5);
        when(mockedThief.getItem(5)).thenReturn(ItemStack.EMPTY);
        org.mockito.Mockito.doThrow(new RuntimeException("write boom"))
                .doNothing().when(mockedThief).setItem(anyInt(), any());
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a single-item VICTIM_REMOVED state restores cleanly");
        assertEquals(1, victimInventory.getItem(0).getCount(),
                "the single item must be restored exactly");
    }

    @Test
    void itemReceiverNoOpWriteIsRecoveryRequired() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(-1);
        when(mockedThief.getFreeSlot()).thenReturn(5);
        when(mockedThief.getItem(5)).thenReturn(ItemStack.EMPTY);
        org.mockito.Mockito.doNothing().when(mockedThief).setItem(anyInt(), any());
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a no-op receiver write is caught by the post-verify; the "
                        + "internal restore completes (thief slot already at before)");
        assertEquals("item_commit_write_mismatch", result.failureReason());
        assertEquals(3, victimInventory.getItem(0).getCount(), "the victim must be exactly restored");
    }

    @Test
    void itemWrongReceiverWriteRestoresCleanly() {
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        Inventory mockedThief = mock(Inventory.class);
        when(thief.getInventory()).thenReturn(mockedThief);
        when(mockedThief.getSlotWithRemainingSpace(any())).thenReturn(-1);
        when(mockedThief.getFreeSlot()).thenReturn(5);
        // The receiver write succeeds but writes the WRONG stack; the restore
        // afterwards overwrites it correctly → FAILED_CLEAN.
        when(mockedThief.getItem(5)).thenReturn(ItemStack.EMPTY)  // prepare
                .thenReturn(ItemStack.EMPTY)                      // drift check
                .thenReturn(new ItemStack(Items.EMERALD, 9))      // post-verify: wrong write
                .thenReturn(ItemStack.EMPTY)                      // restore classify
                .thenReturn(ItemStack.EMPTY);                     // restore verify
        org.mockito.Mockito.doAnswer(i -> {
            /* the write lands in the getItem sequence */ return null;
        }).when(mockedThief).setItem(anyInt(), any());
        RandomSource random = mock(RandomSource.class);
        when(random.nextInt(1)).thenReturn(0);
        ItemPlan plan = (ItemPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.ITEM), random);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.ITEM), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state());
        assertEquals("item_commit_write_mismatch", result.failureReason());
        assertEquals(3, victimInventory.getItem(0).getCount(), "the victim must be exactly restored");
    }

    @Test
    void hungerInternalRestoreFromEachSetterException() {
        // (a) victim setFoodLevel throws → PRE → restore without re-writing.
        FoodData mockVf = mock(FoodData.class);
        when(victim.getFoodData()).thenReturn(mockVf);
        final int[] vf = { 12 };
        final float[] vfSat = { 8.0f };
        when(mockVf.getFoodLevel()).thenAnswer(i -> vf[0]);
        when(mockVf.getSaturationLevel()).thenAnswer(i -> vfSat[0]);
        FoodData tf = foodOf(thief);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        org.mockito.Mockito.doThrow(new RuntimeException("food boom"))
                .doAnswer(i -> { vf[0] = i.getArgument(0); return null; })
                .when(mockVf).setFoodLevel(anyInt());
        org.mockito.Mockito.doAnswer(i -> { vfSat[0] = i.getArgument(0); return null; })
                .when(mockVf).setSaturation(anyFloat());
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        assertNotNull(plan);
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "a failed first write with a PRE state restores without side effects");
        assertEquals(12, vf[0]);
    }

    @Test
    void hungerInternalRestoreFromVictimSatState() {
        // (b) victim setSaturation throws → VICTIM_FOOD_REDUCED.
        FoodData mockVf = mock(FoodData.class);
        when(victim.getFoodData()).thenReturn(mockVf);
        final int[] vf = { 12 };
        final float[] vfSat = { 8.0f };
        when(mockVf.getFoodLevel()).thenAnswer(i -> vf[0]);
        when(mockVf.getSaturationLevel()).thenAnswer(i -> vfSat[0]);
        org.mockito.Mockito.doAnswer(i -> { vf[0] = i.getArgument(0); return null; })
                .when(mockVf).setFoodLevel(anyInt());
        org.mockito.Mockito.doThrow(new RuntimeException("sat boom"))
                .doAnswer(i -> { vfSat[0] = i.getArgument(0); return null; })
                .when(mockVf).setSaturation(anyFloat());
        FoodData tf = foodOf(thief);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "VICTIM_FOOD_REDUCED restores exactly");
        assertEquals(12, vf[0]);
        assertEquals(8.0f, vfSat[0], 1.0E-5);
    }

    @Test
    void hungerInternalRestoreFromThiefFoodState() {
        // (c) thief setFoodLevel throws → VICTIM_SAT_REDUCED.
        FoodData vf = foodOf(victim);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        FoodData mockTf = mock(FoodData.class);
        when(thief.getFoodData()).thenReturn(mockTf);
        final int[] tf = { 6 };
        final float[] tfSat = { 2.0f };
        when(mockTf.getFoodLevel()).thenAnswer(i -> tf[0]);
        when(mockTf.getSaturationLevel()).thenAnswer(i -> tfSat[0]);
        org.mockito.Mockito.doThrow(new RuntimeException("food boom"))
                .doAnswer(i -> { tf[0] = i.getArgument(0); return null; })
                .when(mockTf).setFoodLevel(anyInt());
        org.mockito.Mockito.doAnswer(i -> { tfSat[0] = i.getArgument(0); return null; })
                .when(mockTf).setSaturation(anyFloat());
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "VICTIM_SAT_REDUCED restores exactly");
        assertEquals(12, vf.getFoodLevel());
        assertEquals(8.0f, vf.getSaturationLevel(), 1.0E-5);
        assertEquals(6, tf[0]);
        assertEquals(2.0f, tfSat[0], 1.0E-5);
    }

    @Test
    void hungerInternalRestoreFromThiefSatState() {
        // (d) thief setSaturation throws → THIEF_FOOD_RAISED.
        FoodData vf = foodOf(victim);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        FoodData mockTf = mock(FoodData.class);
        when(thief.getFoodData()).thenReturn(mockTf);
        final int[] tf = { 6 };
        final float[] tfSat = { 2.0f };
        when(mockTf.getFoodLevel()).thenAnswer(i -> tf[0]);
        when(mockTf.getSaturationLevel()).thenAnswer(i -> tfSat[0]);
        org.mockito.Mockito.doAnswer(i -> { tf[0] = i.getArgument(0); return null; })
                .when(mockTf).setFoodLevel(anyInt());
        org.mockito.Mockito.doThrow(new RuntimeException("sat boom"))
                .doAnswer(i -> { tfSat[0] = i.getArgument(0); return null; })
                .when(mockTf).setSaturation(anyFloat());
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.FAILED_CLEAN, result.state(),
                "THIEF_FOOD_RAISED restores exactly");
        assertEquals(6, tf[0]);
        assertEquals(2.0f, tfSat[0], 1.0E-5);
        assertEquals(12, vf.getFoodLevel());
        assertEquals(8.0f, vf.getSaturationLevel(), 1.0E-5);
    }

    @Test
    void hungerClampedPostValuesNeverCommit() {
        FoodData mockVf = mock(FoodData.class);
        when(victim.getFoodData()).thenReturn(mockVf);
        final int[] vf = { 12 };
        final float[] vfSat = { 8.0f };
        when(mockVf.getFoodLevel()).thenAnswer(i -> vf[0]);
        when(mockVf.getSaturationLevel()).thenAnswer(i -> vfSat[0]);
        // The write CLAMPS the food level to >= 11 — legal but not equal to
        // the planned 10 → never committed (8C.1.3 §3).
        org.mockito.Mockito.doAnswer(i -> vf[0] = Math.max(i.getArgument(0), 11))
                .when(mockVf).setFoodLevel(anyInt());
        org.mockito.Mockito.doAnswer(i -> { vfSat[0] = i.getArgument(0); return null; })
                .when(mockVf).setSaturation(anyFloat());
        FoodData tf = foodOf(thief);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        HungerPlan plan = (HungerPlan) PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class));
        ShadowTransferResult result = PlayerAssetTransferExecutor.INSTANCE.commit(context(),
                candidate(ShadowTheftType.HUNGER), plan);
        assertEquals(ShadowTransferState.RECOVERY_REQUIRED, result.state(),
                "a clamped post value matches neither the pre nor the reduced "
                        + "state — the internal rollback cannot safely complete "
                        + "and must never fabricate a clean failure");
        assertEquals("hunger_commit_write_mismatch; internal_rollback_failed", result.failureReason());
    }

    @Test
    void effectCandidatePoolExcludesThiefHeldEffects() {
        // The thief already holds the buff → EFFECT must not enter the pool
        // (shared feasibility, 8C.1.3 §5).
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance instance = new MobEffectInstance(regen, 500, 0, false, true, true);
        when(victim.getActiveEffects()).thenReturn(List.of(instance));
        when(thief.hasEffect(any())).thenReturn(true);
        List<ShadowTheftType> types = probeTypes();
        assertFalse(types.contains(ShadowTheftType.EFFECT),
                "a buff the thief already holds must not make EFFECT a candidate");
    }

    @Test
    void hungerCandidatePoolExcludesInfeasibleSaturation() {
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(10);
        vf.setSaturation(9.5f); // infeasible within the 1-point budget
        tf.setFoodLevel(6);
        tf.setSaturation(0.5f);
        List<ShadowTheftType> types = probeTypes();
        assertFalse(types.contains(ShadowTheftType.HUNGER),
                "an infeasible saturation budget must not make HUNGER a candidate");
    }

    @Test
    void candidatePresentImpliesPrepareNonNullWithoutDrift() {
        // Consistency: when the pool says HUNGER+EFFECT are available, the
        // engine's prepare must produce a plan for each (no state drift).
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        Holder<MobEffect> regen = mockEffectHolder(REGENERATION, true, false);
        MobEffectInstance instance = new MobEffectInstance(regen, 500, 0, false, true, true);
        when(victim.getActiveEffects()).thenReturn(List.of(instance));
        when(thief.hasEffect(any())).thenReturn(false);
        List<ShadowTheftType> types = probeTypes();
        assertTrue(types.contains(ShadowTheftType.HUNGER));
        assertTrue(types.contains(ShadowTheftType.EFFECT));
        assertNotNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.HUNGER), mock(RandomSource.class)));
        assertNotNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.EFFECT), mock(RandomSource.class)));
        // And the unavailable types are absent from the pool entirely.
        assertFalse(types.contains(ShadowTheftType.COIN));
    }

    private List<ShadowTheftType> probeTypes() {
        return PlayerReadonlyCandidateProvider.INSTANCE.provide(context()).stream()
                .map(c -> c.type()).toList();
    }

    @Test
    void providerPoolDrawsExactlyOnceOverRenormalisedWeights() {
        // Only HUNGER + ITEM are feasible; the pool contains exactly those
        // and draws the type exactly once.
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 1));
        fillThiefSlots(0, 5);
        FoodData vf = foodOf(victim);
        FoodData tf = foodOf(thief);
        vf.setFoodLevel(12);
        vf.setSaturation(8.0f);
        tf.setFoodLevel(6);
        tf.setSaturation(2.0f);
        ShadowCandidatePool pool = ShadowCandidatePool.empty();
        for (ShadowCandidate c : PlayerReadonlyCandidateProvider.INSTANCE.provide(context())) {
            pool = pool.with(c);
        }
        assertEquals(2, pool.size());
        assertTrue(pool.contains(ShadowTheftType.ITEM));
        assertTrue(pool.contains(ShadowTheftType.HUNGER));
        assertEquals(ShadowCandidatePool.DEFAULT_ITEM_WEIGHT + ShadowCandidatePool.DEFAULT_HUNGER_WEIGHT,
                pool.totalWeight(), "the remaining weights renormalise");
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        assertEquals(ShadowTheftType.ITEM, pool.draw(random).type());
        verify(random, times(1)).nextLong();
    }

    // ---- random call counts through the coordinator ----

    @Test
    void coordinatorRandomCallCountsWithRealEngine() {
        // Full path: type draw (nextLong x1), item selection (nextInt x1),
        // success roll (nextDouble x1).
        victimInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        fillThiefSlots(0, 5);
        RandomSource random = mock(RandomSource.class);
        when(random.nextLong()).thenReturn(0L);
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.1d);

        ShadowFrameworkSettings settings = new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        InMemoryAudit audit = new InMemoryAudit();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> settings,
                ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30)),
                PlayerAssetTransferExecutor.INSTANCE,
                ctx -> ShadowProtectionResult.ALLOWED,
                new ShadowCooldownTracker(), new ShadowIdempotencyTracker(),
                lvl -> audit, level -> new FakeDailyLimits(),() -> random, () -> 1L, () -> "2026-08-11");
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
        assertEquals(1, result.receipt().itemCount());
        verify(random, times(1)).nextLong();
        verify(random, times(1)).nextInt(anyInt());
        verify(random, times(1)).nextDouble();
        assertEquals(2, victimInventory.getItem(0).getCount());
        assertEquals(1, thiefInventory.getItem(5).getCount());
    }

    @Test
    void coinIsAlwaysRefusedByTheEngine() {
        assertNull(PlayerAssetTransferExecutor.INSTANCE.prepare(context(),
                candidate(ShadowTheftType.COIN), mock(RandomSource.class)));
    }

    /** Small in-memory audit for the coordinator-path test, mirroring the
     *  production store's state-transition rules and health probe. */
    static final class InMemoryAudit implements ShadowAuditWriter {
        private final List<ShadowAuditRecord> records = new ArrayList<>();

        @Override
        public boolean append(ShadowAuditRecord record) {
            int index = -1;
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).eventId().equals(record.eventId())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                records.add(record);
                return true;
            }
            ShadowAuditRecord existing = records.get(index);
            if (existing.auditState() == ShadowAuditState.PENDING
                    && record.auditState() == ShadowAuditState.FINAL
                    && record.outcome() != null) {
                records.set(index, record);
                return true;
            }
            if (existing.auditState() == ShadowAuditState.FINAL && existing.equals(record)) {
                return true;
            }
            return false;
        }

        @Override
        public ShadowAuditRecord byEventId(UUID eventId) {
            return records.stream().filter(r -> r.eventId().equals(eventId)).findFirst().orElse(null);
        }

        @Override
        public boolean has(UUID eventId) {
            return byEventId(eventId) != null;
        }

        @Override
        public List<ShadowAuditRecord> byThief(UUID thiefId) {
            return records.stream().filter(r -> r.thiefId().equals(thiefId)).toList();
        }

        @Override
        public List<ShadowAuditRecord> byTarget(UUID targetId) {
            return records.stream().filter(r -> r.targetId().equals(targetId)).toList();
        }

        @Override
        public List<ShadowAuditRecord> all() {
            return List.copyOf(records);
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }
}
