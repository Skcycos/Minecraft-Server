package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeTransaction;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 6B.2.1 — behavior tests for the Shift-click transaction helper shared
 * by the FD and DD menu mixins. These assert the real transactional semantics
 * (signature preservation/restore, delivered-count, single-publish) instead of
 * scanning mixin source text.
 */
class ShiftTakeTransactionTest {

    private DataComponentType<CookingSignature> type;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        type = DataComponentType.<CookingSignature>builder()
                .persistent(CookingSignature.CODEC)
                .build();
        CookingSignatureComponents.setTypeOverrideForTesting(type);
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"));
        Mockito.doReturn(new com.mojang.authlib.GameProfile(
                UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"), "Tanrunn"))
                .when(player).getGameProfile();
    }

    @AfterEach
    void tearDown() {
        CookingSignatureComponents.clearTypeOverrideForTesting();
        DishSignatureService.resetForTesting();
    }

    private static ItemStack stew(int count) {
        ItemStack stack = new ItemStack(Items.MUSHROOM_STEW, count);
        return stack;
    }

    // ---- 成功全量移动 ----

    @Test
    void fullMovePublishesOnceWithDeliveredCountAndSignedSnapshot() {
        ItemStack slot = stew(1);
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, recipe("fd:cooking/mushroom_stew"));
        assertNotNull(tx);

        // Simulate moveItemStackTo moving the whole stack: slot becomes empty.
        slot.setCount(0);
        ItemStack event = tx.commit(tx.remainingCount()); // remaining == 0

        assertNotNull(event);
        assertEquals(1, event.getCount(), "event count = delivered amount");
        assertTrue(event.has(type), "event snapshot must carry the current chef signature");
        assertEquals("Tanrunn", event.get(type).chefName());
        assertTrue(tx.isFinished());
        assertEquals(recipe("fd:cooking/mushroom_stew"), tx.recipeId());
    }

    @Test
    void commitTwiceReturnsNullSecondTime() {
        ItemStack slot = stew(1);
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        slot.setCount(0);
        assertNotNull(tx.commit(tx.remainingCount()));
        assertNull(tx.commit(tx.remainingCount()), "second publish must be suppressed");
    }

    // ---- 背包满、移动失败 ----

    @Test
    void failedMovePublishesNothingAndRestoresPreviousSignature() {
        ItemStack slot = stew(1);
        CookingSignature old = new CookingSignature(UUID.randomUUID(), "OldChef");
        slot.set(type, old);

        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        // Move failed: nothing left the slot; remaining count unchanged.
        assertEquals(1, tx.remainingCount());

        tx.abort();
        assertTrue(tx.isFinished());
        assertNull(tx.commit(tx.remainingCount()), "no event after abort");
        assertTrue(slot.has(type));
        assertEquals("OldChef", slot.get(type).chefName(),
                "previous signature restored after failed move");
    }

    @Test
    void failedMoveRemovesSignatureWhenPreviouslyUnsigned() {
        ItemStack slot = stew(1);
        assertFalse(slot.has(type), "precondition: unsigned");

        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        assertTrue(slot.has(type), "signed during take");
        tx.abort();

        assertFalse(slot.has(type), "unsigned stack must be unsigned again after abort");
    }

    // ---- 部分移动 ----

    @Test
    void partialMoveDeliversMovedCountAndRestoresRemainingSignature() {
        ItemStack slot = stew(3);
        CookingSignature old = new CookingSignature(UUID.randomUUID(), "OldChef");
        slot.set(type, old);

        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        // Simulate only 1 of 3 moving out: slot keeps 2.
        slot.setCount(2);
        ItemStack event = tx.commit(tx.remainingCount());

        assertNotNull(event);
        assertEquals(1, event.getCount(), "event count = actually delivered (3-2)");
        assertEquals("Tanrunn", event.get(type).chefName());
        assertTrue(slot.has(type));
        assertEquals("OldChef", slot.get(type).chefName(),
                "items remaining in the slot keep the previous signature");
    }

    // ---- 事件快照含当前厨师署名 ----

    @Test
    void eventSnapshotCarriesCurrentChefNotOldChef() {
        ItemStack slot = stew(2);
        slot.set(type, new CookingSignature(UUID.randomUUID(), "OldChef"));

        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        slot.setCount(0);
        ItemStack event = tx.commit(0);

        assertNotNull(event);
        assertEquals("Tanrunn", event.get(type).chefName(),
                "event result must show the chef who took the dish out");
    }

    // ---- 失败为 0 事件 / 移动 0 ----

    @Test
    void zeroMoveCommitsToNull() {
        ItemStack slot = stew(2);
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        // Nothing moved: remaining == original.
        assertNull(tx.commit(tx.remainingCount()));
        assertFalse(tx.isFinished(), "zero-move is not a completed take; caller aborts");
    }

    // ---- recipeId 正确且调用后清理 ----

    @Test
    void recipeIdSurvivesUntilEndThenCleared() {
        ItemStack slot = stew(1);
        ResourceLocation id = recipe("dungeonsdelight:monster_cooking/cob_n_candy");
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, id);
        assertNotNull(tx);
        slot.setCount(0);
        assertNotNull(tx.commit(0));
        assertEquals(id, tx.recipeId());
        tx.end();
        assertNull(tx.recipeId(), "recipe id cleared after take");
    }

    // ---- begin 拒绝空栈 / 空栈异常安全 ----

    @Test
    void beginRejectsEmptyStack() {
        assertNull(ShiftTakeTransaction.begin(player, ItemStack.EMPTY, null));
        assertNull(ShiftTakeTransaction.begin(player, null, null));
    }

    // ---- 非料理：不签名、不创建事务、不发布 ----

    @Test
    void beginRejectsNonDishWithoutSigning() {
        // Tools/blocks are not dishes; begin must refuse them outright.
        assertNull(ShiftTakeTransaction.begin(player, new ItemStack(Items.IRON_PICKAXE), null));
        assertNull(ShiftTakeTransaction.begin(player, new ItemStack(Items.DIRT), null));
    }

    @Test
    void beginRejectsFoodInNotDishesTagWithoutSigning() {
        // A stack carrying FOOD but explicitly listed in tcth:not_dishes must be
        // refused by the classifier before any transaction/signature happens.
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.isEmpty()).thenReturn(false);
        net.minecraft.core.Holder<Item> holder = Mockito.mock(net.minecraft.core.Holder.class);
        Mockito.when(stack.getItemHolder()).thenReturn(holder);
        Mockito.when(stack.has(net.minecraft.core.component.DataComponents.FOOD)).thenReturn(true);
        Mockito.when(holder.is(DishClassifier.NOT_DISHES_TAG)).thenReturn(true);

        assertNull(ShiftTakeTransaction.begin(player, stack, null),
                "FOOD in not_dishes must not create a transaction (no sign, no publish)");
        Mockito.verify(stack, Mockito.never())
                .set(Mockito.any(net.minecraft.core.component.DataComponentType.class), Mockito.any());
    }

    // ---- 同一语义：FD 与 DD 共用（此处验证共享类行为，非各自复制） ----

    @Test
    void sharedSemanticsAppliedByIdentity() {
        // Both menu mixins delegate to the exact same class; the behavior
        // above is therefore identical for FD and DD. Assert the shared
        // contract used by both: begin→commit→end on a full move.
        ItemStack slot = stew(1);
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        slot.setCount(0);
        ItemStack event = tx.commit(0);
        assertNotNull(event);
        assertEquals(1, event.getCount());
        tx.end();
    }

    private static ResourceLocation recipe(String s) {
        return ResourceLocation.parse(s);
    }
}
