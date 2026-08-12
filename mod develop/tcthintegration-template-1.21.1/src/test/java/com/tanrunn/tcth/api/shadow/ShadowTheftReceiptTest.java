package com.tanrunn.tcth.api.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;

/**
 * Unit tests for {@link ShadowTheftReceipt} (phase 8B).
 *
 * <p>Covers: scalar validation, per-type field exclusivity, empty receipt
 * defaults.
 */
class ShadowTheftReceiptTest {

    private static final ResourceLocation ITEM = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath("minecraft", "speed");

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void emptyReceiptHasAllDefaults() {
        ShadowTheftReceipt receipt = ShadowTheftReceipt.empty();
        assertTrue(receipt.isEmpty());
        assertEquals(null, receipt.itemId());
        assertEquals(0, receipt.itemCount());
        assertEquals(0.0d, receipt.numericAmount());
        assertEquals(0, receipt.effectDurationTicks());
        assertEquals(null, receipt.effectId());
    }

    @Test
    void negativeCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(ITEM, -1, 0.0d, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, 0.0d, -1, EFFECT));
    }

    @Test
    void nonFiniteAndNegativeAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, Double.NaN, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, Double.POSITIVE_INFINITY, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, -1.0d, 0, null));
    }

    @Test
    void countWithoutIdAndDurationWithoutEffectAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 3, 0.0d, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, 0.0d, 10, null));
    }

    @Test
    void itemReceiptMatchesOnlyItemType() {
        ShadowTheftReceipt receipt = ShadowTheftReceipt.item(ITEM, 1);
        assertTrue(receipt.matches(ShadowTheftType.ITEM));
        assertFalse(receipt.matches(ShadowTheftType.COIN));
        assertFalse(receipt.matches(ShadowTheftType.HEALTH));
        assertFalse(receipt.matches(ShadowTheftType.HUNGER));
        assertFalse(receipt.matches(ShadowTheftType.EFFECT));
    }

    @Test
    void numericReceiptMatchesScalarTypesOnly() {
        ShadowTheftReceipt receipt = ShadowTheftReceipt.numeric(12.5d);
        assertFalse(receipt.matches(ShadowTheftType.ITEM));
        assertTrue(receipt.matches(ShadowTheftType.COIN));
        assertTrue(receipt.matches(ShadowTheftType.HEALTH));
        assertTrue(receipt.matches(ShadowTheftType.HUNGER));
        assertFalse(receipt.matches(ShadowTheftType.EFFECT));
    }

    @Test
    void effectReceiptMatchesOnlyEffectType() {
        ShadowTheftReceipt receipt = ShadowTheftReceipt.effect(EFFECT, 200);
        assertFalse(receipt.matches(ShadowTheftType.ITEM));
        assertFalse(receipt.matches(ShadowTheftType.COIN));
        assertFalse(receipt.matches(ShadowTheftType.HEALTH));
        assertFalse(receipt.matches(ShadowTheftType.HUNGER));
        assertTrue(receipt.matches(ShadowTheftType.EFFECT));
    }

    @Test
    void zeroNumericAmountDoesNotMatchScalarTypes() {
        ShadowTheftReceipt receipt = new ShadowTheftReceipt(null, 0, 0.0d, 0, null);
        assertFalse(receipt.matches(ShadowTheftType.COIN), "zero amount must not count as a COIN transfer");
        assertFalse(receipt.matches(ShadowTheftType.ITEM), "zero count must not count as an ITEM transfer");
    }

    @Test
    void recordsAreImmutableByConstruction() {
        ShadowTheftReceipt receipt = ShadowTheftReceipt.item(ITEM, 4);
        assertEquals(4, receipt.itemCount());
        assertEquals(ITEM, receipt.itemId());
    }

    // ---- 8B.1 cross-field consistency ----

    @Test
    void itemIdWithoutPositiveCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(ITEM, 0, 0.0d, 0, null));
    }

    @Test
    void effectIdWithoutPositiveDurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftReceipt(null, 0, 0.0d, 0, EFFECT));
    }

    @Test
    void scalarExclusivityIsEnforcedByMatches() {
        // A receipt may only carry fields of one theft type; the item/effect
        // factories already guarantee this, matches() re-verifies it.
        assertFalse(ShadowTheftReceipt.item(ITEM, 1).matches(ShadowTheftType.EFFECT));
        assertFalse(ShadowTheftReceipt.effect(EFFECT, 200).matches(ShadowTheftType.ITEM));
        assertFalse(ShadowTheftReceipt.effect(EFFECT, 200).matches(ShadowTheftType.COIN));
    }
}
