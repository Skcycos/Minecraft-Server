package com.tanrunn.tcth.impl.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Phase 7B.1 — structural regression guards for the Keg pouring mixin: events
 * are published only after the actual delivery completes, at the three real
 * delivery points of {@code lambda$useItemOn$0} (setItemInHand / Inventory.add
 * success / Player.drop), never after {@code List.isEmpty} before forEach.
 */
class KegPouringMixinDeliveryTest {

    private static String src() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/tanrunn/tcth/mixin/brewinandchewin/KegPouringMixin.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void publishesAtSetItemInHandAfter() throws Exception {
        String s = src();
        assertTrue(s.contains("target = \"Lnet/minecraft/world/entity/player/Player;setItemInHand("),
                "must inject at setItemInHand (replacement delivery)");
        assertTrue(s.contains("shift = At.Shift.AFTER"), "must publish AFTER the hand replacement");
    }

    @Test
    void publishesOnInventoryAddSuccessViaRedirect() throws Exception {
        String s = src();
        assertTrue(s.contains("@Redirect(method = \"lambda$useItemOn$0\""),
                "must redirect Inventory.add in the lambda");
        assertTrue(s.contains("target = \"Lnet/minecraft/world/entity/player/Inventory;add("),
                "must wrap Inventory.add");
        assertTrue(s.contains("if (added)"), "must publish only when add succeeded");
    }

    @Test
    void publishesAtPlayerDropAfter() throws Exception {
        String s = src();
        assertTrue(s.contains("target = \"Lnet/minecraft/world/entity/player/Player;drop("),
                "must inject at Player.drop (full-inventory fallback)");
        assertTrue(s.contains("shift = At.Shift.AFTER"), "must publish AFTER the drop");
    }

    @Test
    void noPublishBeforeDelivery() throws Exception {
        String s = src();
        // Must NOT contain the old List.isEmpty-after injection.
        assertTrue(!s.contains("target = \"Ljava/util/List;isEmpty()Z\""),
                "must not publish after List.isEmpty before forEach (7B.1 regression)");
        // And must not reference the old List-based capture.
        assertTrue(!s.contains("List<ItemStack> delivered"),
                "must not capture the pre-delivery list");
    }

    @Test
    void threeDeliveryBranches() throws Exception {
        String s = src();
        int setItemInHand = s.split("setItemInHand", -1).length - 1;
        int invAdd = s.split("Inventory;add", -1).length - 1;
        int drop = s.split("Player;drop", -1).length - 1;
        // Each delivery point appears at least once (injection target + handler).
        assertTrue(setItemInHand >= 1, "setItemInHand delivery point present");
        assertTrue(invAdd >= 1, "Inventory.add delivery point present");
        assertTrue(drop >= 1, "Player.drop delivery point present");
    }
}
