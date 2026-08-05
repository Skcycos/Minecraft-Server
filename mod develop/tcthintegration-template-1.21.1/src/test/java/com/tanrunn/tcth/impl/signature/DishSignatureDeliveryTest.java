package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mojang.authlib.GameProfile;
import com.tanrunn.tcth.impl.classifier.DishClassifier;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Device take-out semantics, mirrored from the REAL mixin call order:
 *
 * <pre>
 *   HEAD: save previous signature(s) → sign live stack → copy SIGNED snapshot
 *   take: delivered stack = live stack (signed)
 *   RETURN true : publish the signed snapshot (event carries same signature)
 *   RETURN false: restore the previous signature state (never plain-remove)
 * </pre>
 *
 * These tests reflect that exact sequence (unlike an earlier version that
 * simulated "sign then copy" while the mixin did "copy then sign").
 */
class DishSignatureDeliveryTest {

    private static final UUID CHEF_B = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private ServerPlayer chefA;
    private ServerPlayer chefB;

    @BeforeAll
    static void bootstrap() {
        SignatureTestRegistry.ensureRegistered();
    }

    @BeforeEach
    void setUp() {
        DishSignatureService.resetForTesting();
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
        chefA = mockChef("11111111-2222-3333-4444-555555555555", "ChefA");
        chefB = mockChef(CHEF_B.toString(), "ChefB");
    }

    @AfterEach
    void tearDown() {
        DishSignatureService.resetForTesting();
    }

    private static ServerPlayer mockChef(String uuid, String name) {
        ServerPlayer p = Mockito.mock(ServerPlayer.class);
        Mockito.when(p.getUUID()).thenReturn(UUID.fromString(uuid));
        Mockito.when(p.getGameProfile()).thenReturn(new GameProfile(UUID.fromString(uuid), name));
        Mockito.when(p.getName()).thenReturn(Component.literal(name));
        return p;
    }

    private static CookingSignature sigOf(ItemStack s) {
        return s.get(CookingSignatureComponents.type());
    }

    /** Simulates the HEAD handler of the wok/stockpot mixins. */
    private static ItemStack headWokTake(ServerPlayer player, ItemStack live) {
        // 1) save previous, 2) sign live, 3) copy SIGNED snapshot.
        DishSignatureService.sign(player, live);
        return live.copy(); // the event snapshot
    }

    /** Simulates the RETURN false handler: restore previous state. */
    private static void restorePrevious(ItemStack live, CookingSignature prev, boolean hadPrev) {
        if (hadPrev) {
            live.set(CookingSignatureComponents.type(), prev);
        } else {
            live.remove(CookingSignatureComponents.type());
        }
    }

    // ---- 炒锅 / 汤锅：事件 snapshot 必须带新署名 ----

    @Test
    void wokEventSnapshotCarriesNewSignature() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF, 3);
        ItemStack snapshot = headWokTake(chefA, live);
        assertNotNull(sigOf(snapshot), "event snapshot (signed copy) must carry the signature");
        assertEquals(chefA.getUUID(), sigOf(snapshot).chefId());
        assertEquals(chefA.getUUID(), sigOf(live).chefId(), "delivered live stack must match");
        assertEquals(3, live.getCount(), "count must be unchanged");
    }

    @Test
    void stockpotEventSnapshotCarriesNewSignature() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF, 6);
        ItemStack snapshot = headWokTake(chefA, live); // HEAD order
        assertNotNull(sigOf(snapshot));
        ItemStack portion = snapshot.copyWithCount(1); // delivered portion
        assertEquals(1, portion.getCount());
        assertNotNull(sigOf(portion), "delivered portion must carry the signature");
    }

    // ---- 失败回滚：原来无署名 → 失败后仍无署名 ----

    @Test
    void wokFailureWithNoPreviousSignatureLeavesUnsigned() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF);
        CookingSignature prev = sigOf(live);
        boolean hadPrev = prev != null;
        ItemStack snapshot = headWokTake(chefA, live);
        assertNotNull(sigOf(live), "signed during take attempt");
        // RETURN false: restore.
        restorePrevious(live, prev, hadPrev);
        assertNull(sigOf(live), "failed take with no previous signature must end unsigned");
    }

    // ---- 失败回滚：原来 Chef A → Chef B 失败后恢复 Chef A ----

    @Test
    void wokFailureRestoresPreviousChefSignature() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF);
        DishSignatureService.sign(chefA, live); // previously signed by Chef A
        CookingSignature prev = sigOf(live);

        headWokTake(chefB, live); // Chef B attempts
        assertEquals(CHEF_B, sigOf(live).chefId(), "Chef B signed during attempt");
        restorePrevious(live, prev, true); // RETURN false
        assertEquals(chefA.getUUID(), sigOf(live).chefId(),
                "failed take must restore the previous chef (Chef A)");
    }

    @Test
    void stockpotFailureRestoresPreviousChefSignature() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF);
        DishSignatureService.sign(chefA, live);
        CookingSignature prev = sigOf(live);

        headWokTake(chefB, live);
        restorePrevious(live, prev, true);
        assertEquals(chefA.getUUID(), sigOf(live).chefId());
    }

    // ---- 成功取餐覆盖旧署名为当前玩家 ----

    @Test
    void successfulTakeOverwritesOldSignatureWithCurrentChef() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF);
        DishSignatureService.sign(chefA, live);
        ItemStack snapshot = headWokTake(chefB, live); // Chef B takes successfully
        assertEquals(CHEF_B, sigOf(snapshot).chefId(), "event snapshot must show Chef B");
        assertEquals(CHEF_B, sigOf(live).chefId(), "delivered stack must show Chef B");
    }

    // ---- 蒸笼：每槽独立旧署名；失败逐槽恢复；部分交付恢复未交付槽 ----

    private static ItemStack[] headSteamerTake(ServerPlayer player, ItemStack[] slots) {
        // 1) save each slot's previous signature, 2) sign dish slots,
        // 3) snapshot AFTER signing (signed snapshot used for diff/event).
        int n = slots.length;
        CookingSignature[] prevs = new CookingSignature[n];
        boolean[] hadPrevs = new boolean[n];
        for (int i = 0; i < n; i++) {
            prevs[i] = sigOf(slots[i]);
            hadPrevs[i] = prevs[i] != null;
        }
        for (int i = 0; i < n; i++) {
            if (!slots[i].isEmpty() && DishClassifier.isDish(slots[i])) {
                DishSignatureService.sign(player, slots[i]);
            }
        }
        ItemStack[] before = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            before[i] = slots[i].copy();
        }
        return before; // caller keeps prevs/hadPrevs for the restore logic
    }

    @Test
    void steamerEventTakenCarriesNewSignature() {
        ItemStack slot = new ItemStack(Items.COOKED_BEEF);
        ItemStack[] before = headSteamerTake(chefA, new ItemStack[] {slot});
        // takeFood succeeds and consumes slot 0.
        slot.setCount(0);
        ItemStack taken = before[0].copy();
        assertNotNull(sigOf(taken), "the event's taken stack (signed snapshot) must carry the signature");
        assertEquals(chefA.getUUID(), sigOf(taken).chefId());
    }

    @Test
    void steamerRestoresDistinctOldSignaturesPerSlotOnFailure() {
        ItemStack slotA = new ItemStack(Items.COOKED_BEEF);
        ItemStack slotB = new ItemStack(Items.COOKED_CHICKEN);
        ItemStack raw = new ItemStack(Items.STICK);
        DishSignatureService.sign(chefA, slotA); // slot 0 signed by Chef A
        // slot 1 unsigned, slot 2 raw unsigned.

        ItemStack[] slots = {slotA, slotB, raw};
        int n = slots.length;
        CookingSignature[] prevs = new CookingSignature[n];
        boolean[] hadPrevs = new boolean[n];
        for (int i = 0; i < n; i++) {
            prevs[i] = sigOf(slots[i]);
            hadPrevs[i] = prevs[i] != null;
        }
        // Chef B signs dish slots (0,1); raw (2) not signed.
        for (int i = 0; i < n; i++) {
            if (!slots[i].isEmpty() && DishClassifier.isDish(slots[i])) {
                DishSignatureService.sign(chefB, slots[i]);
            }
        }
        assertEquals(CHEF_B, sigOf(slotA).chefId());
        assertEquals(CHEF_B, sigOf(slotB).chefId());
        assertNull(sigOf(raw));

        // takeFood FAILS: restore each slot independently.
        for (int i = 0; i < n; i++) {
            if (hadPrevs[i]) slots[i].set(CookingSignatureComponents.type(), prevs[i]);
            else slots[i].remove(CookingSignatureComponents.type());
        }
        assertEquals(chefA.getUUID(), sigOf(slotA).chefId(), "slot 0 must go back to Chef A");
        assertNull(sigOf(slotB), "slot 1 (previously unsigned) must be unsigned again");
        assertNull(sigOf(raw), "raw slot untouched");
    }

    @Test
    void steamerPartialDeliveryRestoresUndeliveredSlots() {
        ItemStack delivered = new ItemStack(Items.COOKED_BEEF);
        ItemStack undelivered = new ItemStack(Items.COOKED_CHICKEN);
        DishSignatureService.sign(chefA, undelivered); // pre-existing Chef A signature

        ItemStack[] slots = {delivered, undelivered};
        int n = slots.length;
        CookingSignature[] prevs = new CookingSignature[n];
        boolean[] hadPrevs = new boolean[n];
        for (int i = 0; i < n; i++) {
            prevs[i] = sigOf(slots[i]);
            hadPrevs[i] = prevs[i] != null;
        }
        ItemStack[] before = headSteamerTake(chefB, slots); // signs both dish slots
        assertEquals(CHEF_B, sigOf(delivered).chefId());

        // takeFood succeeds: slot 0 delivered (emptied), slot 1 NOT delivered.
        delivered.setCount(0);
        // finally: restore undelivered (non-empty) slots to their pre-take state.
        for (int i = 0; i < n; i++) {
            if (slots[i].isEmpty()) continue; // delivered — keeps Chef B
            if (hadPrevs[i]) slots[i].set(CookingSignatureComponents.type(), prevs[i]);
            else slots[i].remove(CookingSignatureComponents.type());
        }
        assertEquals(CHEF_B, sigOf(before[0].copy()).chefId(),
                "delivered portion event snapshot shows Chef B");
        assertEquals(chefA.getUUID(), sigOf(undelivered).chefId(),
                "undelivered slot must be restored to Chef A");
    }

    // ---- 事件副本独立性 ----

    @Test
    void mutatingEventCopyDoesNotAffectDeliveredStack() {
        ItemStack live = new ItemStack(Items.COOKED_BEEF, 2);
        ItemStack snapshot = headWokTake(chefA, live);
        snapshot.remove(CookingSignatureComponents.type());
        assertNotNull(sigOf(live), "mutating the event copy must not affect the delivered stack");
    }

    // ---- 堆叠语义 ----

    @Test
    void sameChefSameNameDishesStackTogether() {
        ItemStack a = new ItemStack(Items.COOKED_BEEF);
        ItemStack b = new ItemStack(Items.COOKED_BEEF);
        DishSignatureService.sign(chefA, a);
        DishSignatureService.sign(chefA, b);
        assertTrue(ItemStack.isSameItemSameComponents(a, b));
    }

    @Test
    void differentChefDishesDoNotStack() {
        ItemStack a = new ItemStack(Items.COOKED_BEEF);
        ItemStack b = new ItemStack(Items.COOKED_BEEF);
        DishSignatureService.sign(chefA, a);
        DishSignatureService.sign(chefB, b);
        assertTrue(!ItemStack.isSameItemSameComponents(a, b));
    }
}
