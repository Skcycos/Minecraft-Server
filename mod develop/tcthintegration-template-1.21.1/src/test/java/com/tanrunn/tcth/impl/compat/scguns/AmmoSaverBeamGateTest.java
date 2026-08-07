package com.tanrunn.tcth.impl.compat.scguns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Phase 5B.1.1: beam {@code consumeAmmo} preconditions — probability is only
 * consulted when SG would perform a real ammo deduction. Counting seams prove
 * "no roll" for creative / IgnoreAmmo / empty stack / empty ammo / failures.
 */
class AmmoSaverBeamGateTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private static AtomicInteger counter() {
        return new AtomicInteger(0);
    }

    private static AmmoSaverBeamGate.ProbabilitySource counting(AtomicInteger rolls, boolean result) {
        return () -> {
            rolls.incrementAndGet();
            return result;
        };
    }

    private static AmmoSaverBeamGate.StackSnapshot fields(boolean ignoreAmmo, int ammoCount) {
        return () -> new AmmoSaverBeamGate.StackFields(ignoreAmmo, ammoCount);
    }

    // ---- no real deduction → never roll, never cancel ----

    @Test
    void creativeTrueDoesNotCallProbabilityAndDoesNotCancel() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, true, fields(false, 10), counting(rolls, true));
        assertFalse(cancel, "creative must not cancel");
        assertEquals(0, rolls.get(), "creative must not call probability source");
    }

    @Test
    void ignoreAmmoTrueDoesNotCallProbabilityAndDoesNotCancel() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(true, 10), counting(rolls, true));
        assertFalse(cancel, "IgnoreAmmo must not cancel");
        assertEquals(0, rolls.get(), "IgnoreAmmo must not call probability source");
    }

    @Test
    void emptyStackDoesNotCallProbability() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, false, false, fields(false, 10), counting(rolls, true));
        assertFalse(cancel);
        assertEquals(0, rolls.get(), "empty stack must not call probability source");
    }

    @Test
    void nullPlayerDoesNotCallProbability() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                false, true, false, fields(false, 10), counting(rolls, true));
        assertFalse(cancel);
        assertEquals(0, rolls.get());
    }

    @Test
    void zeroAmmoDoesNotCallProbability() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(false, 0), counting(rolls, true));
        assertFalse(cancel, "AmmoCount<=0 is SG no-op; must not cancel");
        assertEquals(0, rolls.get(), "AmmoCount<=0 must not call probability source");
    }

    // ---- real deduction path → exactly one probability call ----

    @Test
    void survivalIgnoreAmmoFalseCallsProbabilityOnce() {
        AtomicInteger rolls = counter();
        AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(false, 5), counting(rolls, false));
        assertEquals(1, rolls.get(), "real deduction path must call probability exactly once");
    }

    @Test
    void successfulRollCancels() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(false, 3), counting(rolls, true));
        assertTrue(cancel, "successful roll must cancel consumeAmmo");
        assertEquals(1, rolls.get());
    }

    @Test
    void failedRollDoesNotCancel() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(false, 3), counting(rolls, false));
        assertFalse(cancel, "failed roll must leave SG deduction to run");
        assertEquals(1, rolls.get());
    }

    // ---- CustomData missing / read failures ----

    @Test
    void missingCustomDataTreatedAsIgnoreAmmoFalseAndZeroAmmoNoRoll() {
        // Adapter contract: missing CustomData → IgnoreAmmo=false, AmmoCount=0
        // which is an SG no-op → no probability call.
        ItemStack stack = new ItemStack(Items.STICK);
        assertFalse(AmmoSaverStackRead.isIgnoreAmmo(stack));
        assertEquals(0, AmmoSaverStackRead.ammoCount(stack));

        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false,
                () -> AmmoSaverStackRead.readFields(stack),
                counting(rolls, true));
        assertFalse(cancel);
        assertEquals(0, rolls.get(), "missing CustomData (ammo=0) must not roll");
    }

    @Test
    void customDataIgnoreAmmoTrueNoRollViaAdapter() {
        ItemStack stack = new ItemStack(Items.STICK);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("IgnoreAmmo", true);
        tag.putInt("AmmoCount", 12);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);

        assertTrue(AmmoSaverStackRead.isIgnoreAmmo(stack));
        assertEquals(12, AmmoSaverStackRead.ammoCount(stack));

        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false,
                () -> AmmoSaverStackRead.readFields(stack),
                counting(rolls, true));
        assertFalse(cancel);
        assertEquals(0, rolls.get());
    }

    @Test
    void customDataPresentSurvivalRollsOnceViaAdapter() {
        ItemStack stack = new ItemStack(Items.STICK);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("IgnoreAmmo", false);
        tag.putInt("AmmoCount", 7);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);

        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false,
                () -> AmmoSaverStackRead.readFields(stack),
                counting(rolls, true));
        assertTrue(cancel);
        assertEquals(1, rolls.get());
    }

    @Test
    void stackReadDoesNotCreateCustomDataWhenMissing() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertFalse(stack.has(DataComponents.CUSTOM_DATA), "precondition: no CUSTOM_DATA");
        AmmoSaverStackRead.readFields(stack);
        assertFalse(stack.has(DataComponents.CUSTOM_DATA),
                "read-only path must not materialise CUSTOM_DATA on the stack");
    }

    @Test
    void stackSnapshotExceptionFailClosedNoRollNoCancel() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false,
                () -> {
                    throw new IllegalStateException("simulated CustomData failure");
                },
                counting(rolls, true));
        assertFalse(cancel, "read failure must fail-closed (no cancel → SG deducts)");
        assertEquals(0, rolls.get(), "read failure must not call probability source");
    }

    @Test
    void probabilityExceptionFailClosedNoCancel() {
        AtomicInteger rolls = counter();
        boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                true, true, false, fields(false, 4),
                () -> {
                    rolls.incrementAndGet();
                    throw new IllegalStateException("simulated probability failure");
                });
        assertFalse(cancel, "probability failure must fail-closed (no cancel)");
        assertEquals(1, rolls.get(), "probability was reached once before throwing");
    }
}
