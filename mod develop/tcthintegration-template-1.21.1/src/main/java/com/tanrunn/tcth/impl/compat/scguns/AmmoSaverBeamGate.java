package com.tanrunn.tcth.impl.compat.scguns;

/**
 * Pure beam-period ammo-saver gate (phase 5B.1.1).
 *
 * <p>Mirrors the real-deduction preconditions of SG 1.5
 * {@code ServerPlayHandler.consumeAmmo(ServerPlayer, ItemStack)} (confirmed by
 * {@code javap -p -c} on the server JAR):
 *
 * <pre>
 *   if (player.isCreative()) return;                 // no-op
 *   tag = getOrCreateCustomData(stack)
 *   if (tag.getBoolean("IgnoreAmmo")) return;        // no-op
 *   ammo = tag.getInt("AmmoCount")
 *   if (ammo &lt;= 0) return;                           // no-op
 *   // real deduction: putInt(AmmoCount, ammo - 1); setCustomData(...)
 * </pre>
 *
 * <p>TCTH must only consult the probability source when that real-deduction
 * path would run. Creative / {@code IgnoreAmmo} / empty ammo / missing inputs
 * never roll. Config or CustomData read failures fail closed (do not cancel;
 * SG original path continues). No Minecraft / SG / Jobs+ types here — pure,
 * unit-testable, counting-seam friendly.
 */
public final class AmmoSaverBeamGate {

    /**
     * Read-only snapshot of the gun stack fields that decide whether
     * {@code consumeAmmo} would perform a real deduction. Implementations
     * must not create CustomData, write NBT, or mutate the stack.
     */
    @FunctionalInterface
    public interface StackSnapshot {
        /**
         * @return {@code IgnoreAmmo} flag (missing CustomData → {@code false})
         *         and current {@code AmmoCount} (missing → {@code 0})
         */
        StackFields read();
    }

    /**
     * Probability seam — only invoked after real-deduction preconditions pass.
     */
    @FunctionalInterface
    public interface ProbabilitySource {
        boolean shouldSave();
    }

    /**
     * Immutable stack fields used by the gate.
     *
     * @param ignoreAmmo SG {@code IgnoreAmmo} boolean
     * @param ammoCount  SG {@code AmmoCount} int
     */
    public record StackFields(boolean ignoreAmmo, int ammoCount) {
    }

    private AmmoSaverBeamGate() {
    }

    /**
     * Whether the beam-period HEAD inject should {@code ci.cancel()} the
     * entire {@code consumeAmmo} call.
     *
     * @param playerPresent           {@code player != null}
     * @param stackPresentAndNonEmpty {@code stack != null && !stack.isEmpty()}
     * @param creative                {@code player.isCreative()}
     * @param stack                   read-only snapshot (may throw → fail-closed)
     * @param probability             roll seam; counted only on the real path
     * @return {@code true} only when a successful save should cancel deduction
     */
    public static boolean shouldCancelConsumeAmmo(
            boolean playerPresent,
            boolean stackPresentAndNonEmpty,
            boolean creative,
            StackSnapshot stack,
            ProbabilitySource probability) {
        try {
            if (!playerPresent || !stackPresentAndNonEmpty || creative) {
                return false;
            }
            StackFields fields;
            try {
                fields = stack.read();
            } catch (RuntimeException | LinkageError e) {
                // CustomData / config-style read failure: fail-closed, no roll.
                return false;
            }
            if (fields == null || fields.ignoreAmmo() || fields.ammoCount() <= 0) {
                return false;
            }
            // Confirmed real deduction path — only now consult probability.
            try {
                return probability.shouldSave();
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
