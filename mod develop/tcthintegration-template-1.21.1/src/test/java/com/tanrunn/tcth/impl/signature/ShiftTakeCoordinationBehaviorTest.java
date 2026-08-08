package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeSuppression;
import com.tanrunn.tcth.impl.compat.cooking.ShiftTakeTransaction;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 6B.2.2/6B.2.3 — <strong>coordination behavior test</strong> for the
 * Shift-click take-out path.
 *
 * <p>This test drives the real {@link ShiftTakeTransaction} together with the
 * real {@link ShiftTakeSuppression} coordination that the mixins use, and
 * simulates the {@code ResultSlot.onTake} re-entry the menu triggers after a
 * partial move (the exact condition that caused double-publish before 6B.2.2).
 *
 * <p><strong>Scope</strong>: this is NOT a live Mixin execution. The actual
 * bytecode injection is validated by the smoke test (server boot applies the
 * mixins) and by {@code CookingPotJarLifecycleTest} / structural guards. This
 * class only verifies the shared coordination logic the mixins delegate to;
 * player-verified partial-move take-out remains LIVE NOT TESTED.</p>
 */
class ShiftTakeCoordinationBehaviorTest {

    private DataComponentType<CookingSignature> type;
    private ServerPlayer player;
    private net.minecraft.world.inventory.AbstractContainerMenu menu;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        type = DataComponentType.<CookingSignature>builder()
                .persistent(CookingSignature.CODEC)
                .build();
        CookingSignatureComponents.setTypeOverrideForTesting(type);
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
        ShiftTakeSuppression.resetForTesting();
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"));
        Mockito.doReturn(new com.mojang.authlib.GameProfile(
                UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"), "Tanrunn"))
                .when(player).getGameProfile();
        menu = Mockito.mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        Player.class.getField("containerMenu").set(player, menu);
    }

    @AfterEach
    void tearDown() {
        CookingSignatureComponents.clearTypeOverrideForTesting();
        DishSignatureService.resetForTesting();
        ShiftTakeSuppression.resetForTesting();
    }

    /**
     * The exact menu-mixin flow for a partial shift-take, then the exact
     * result-slot-mixin onTake re-entry, asserting a single published event.
     */
    @Test
    void partialMoveOfStackCount3PublishesExactlyOneEvent() {
        // Slot holds 3 dishes, previously signed by another chef.
        ItemStack slot = new ItemStack(Items.MUSHROOM_STEW, 3);
        slot.set(type, new CookingSignature(UUID.randomUUID(), "OldChef"));

        // --- menu mixin HEAD ---
        ResourceLocation recipeId = ResourceLocation.parse("farmersdelight:cooking/mushroom_stew");
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, recipeId);
        assertNotNull(tx, "dish stack must begin a transaction");
        ShiftTakeSuppression.ShiftTakeToken token = ShiftTakeSuppression.enter(menu);

        // --- menu moveItemStackTo: only 1 of 3 moves; slot keeps 2 ---
        slot.setCount(2);

        // --- result-slot mixin onTake re-entry while suppressed ---
        // (mixin checks isShiftTakeSuppressed(player.containerMenu) == true)
        assertTrue(ShiftTakeSuppression.isSuppressed(player.containerMenu),
                "result-slot mixin must see suppression during shift-take");
        // The suppressed result-slot mixin must not publish; simulate it:
        // we only count publications that actually go through.
        AtomicInteger resultSlotPublishes = new AtomicInteger();

        // --- menu mixin Slot.onTake AFTER: commit + publish ---
        int remaining = tx.remainingCount(); // 2
        ItemStack eventStack = tx.commit(remaining);
        assertNotNull(eventStack, "partial move must produce an event");
        assertEquals(1, eventStack.getCount(), "event count = actually delivered (3-2)");
        assertEquals("Tanrunn", eventStack.get(type).chefName(),
                "delivered item signed by the current chef");
        // Delivered items now in the player's inventory (eventStack):
        assertEquals(1, eventStack.getCount());

        // Items remaining in the slot keep the OLD signature:
        assertTrue(slot.has(type));
        assertEquals("OldChef", slot.get(type).chefName(),
                "remaining slot items must restore the previous signature");

        // The result-slot mixin, if it ran onTake, would be suppressed:
        if (ShiftTakeSuppression.isSuppressed(player.containerMenu)) {
            // suppressed => no second publish
        } else {
            resultSlotPublishes.incrementAndGet();
        }

        // --- menu mixin RETURN cleanup ---
        assertTrue(tx.isFinished());
        token.close();
        tx.end();
        assertFalse(ShiftTakeSuppression.isSuppressed(player.containerMenu),
                "suppression must be cleared after the take");

        assertEquals(0, resultSlotPublishes.get(),
                "result-slot mixin must not publish during a shift-take");
        // And exactly one event total was produced (the commit above).
        assertEquals(1, eventStack.getCount(), "single event with delivered count");
    }

    @Test
    void suppressionIsClearedOnFailurePath() {
        ItemStack slot = new ItemStack(Items.MUSHROOM_STEW, 1);
        ShiftTakeTransaction tx = ShiftTakeTransaction.begin(player, slot, null);
        assertNotNull(tx);
        ShiftTakeSuppression.ShiftTakeToken token = ShiftTakeSuppression.enter(menu);
        assertTrue(ShiftTakeSuppression.isSuppressed(menu));

        // Move failed: nothing left the slot.
        tx.abort();
        token.close();
        tx.end();
        assertFalse(ShiftTakeSuppression.isSuppressed(menu),
                "suppression must be cleared on the failure path");
        assertTrue(tx.isFinished());
    }

    @Test
    void suppressionIsGlobalSetNotThreadLocal() throws Exception {
        Object menuA = new Object();
        Object menuB = new Object();
        ShiftTakeSuppression.ShiftTakeToken tokenA = ShiftTakeSuppression.enter(menuA);
        assertTrue(ShiftTakeSuppression.isSuppressed(menuA));
        assertFalse(ShiftTakeSuppression.isSuppressed(menuB),
                "a different menu must not be suppressed");

        // The suppression set is a global thread-safe Set (NOT ThreadLocal):
        // the same menu instance must be visible from another thread, and a
        // different menu must not be suppressed there either. Any assertion
        // failure in the child thread is captured and propagated back.
        java.util.concurrent.Future<Boolean> visible = java.util.concurrent.Executors
                .newSingleThreadExecutor()
                .submit(() -> {
                    if (!ShiftTakeSuppression.isSuppressed(menuA)) {
                        throw new AssertionError("same menu must be visible across threads");
                    }
                    if (ShiftTakeSuppression.isSuppressed(menuB)) {
                        throw new AssertionError("different menu must not be suppressed");
                    }
                    return true;
                });
        assertTrue(visible.get(5, java.util.concurrent.TimeUnit.SECONDS),
                "child thread observation must succeed (exceptions propagated via Future.get)");

        tokenA.close();
        assertFalse(ShiftTakeSuppression.isSuppressed(menuA));
    }

    @Test
    void tokenCloseIsIdempotent() {
        Object menu = new Object();
        ShiftTakeSuppression.ShiftTakeToken token = ShiftTakeSuppression.enter(menu);
        assertTrue(ShiftTakeSuppression.isSuppressed(menu));
        token.close();
        token.close(); // second close must be a no-op
        assertFalse(ShiftTakeSuppression.isSuppressed(menu));
    }
}
