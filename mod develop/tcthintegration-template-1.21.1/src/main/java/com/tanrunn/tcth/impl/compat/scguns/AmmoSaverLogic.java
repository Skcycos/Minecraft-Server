package com.tanrunn.tcth.impl.compat.scguns;

/**
 * Pure ammo-saver deduction semantics for the common {@code handleShoot}
 * inline path (phase 5B.1 / 5B.1.1).
 *
 * <p>Mirrors the shared post-fire deduction block of
 * {@code ServerPlayHandler.handleShoot} (confirmed by {@code javap -p -c} on
 * the server's Scorched Guns 1.5 JAR, bytecode offsets ~470-512). Projectile /
 * beam handling already completed earlier in the same method; this block runs
 * for ordinary guns, shotguns, rockets, grenades, Niami, BEAM and SEMI_BEAM
 * after those branches rejoin:
 *
 * <pre>
 *   AmmoCount      = tag.getInt("AmmoCount")            // old value
 *   newAmmoCount   = Math.max(0, AmmoCount - 1)         // deduction
 *   tag.putInt("AmmoCount", newAmmoCount)
 *   setCustomData(stack, tag)
 *   if (newAmmoCount == 0) Gun.clearLoadedProjectileItem(stack)
 * </pre>
 *
 * <p>When the ammo-saver roll succeeds, {@code newAmmoCount} must stay equal
 * to the old {@code AmmoCount}: the NBT write keeps the original value and
 * {@code newAmmoCount != 0} so {@code clearLoadedProjectileItem} is never
 * called — even when only 1 round was left. Nothing is copied, no gun is
 * repaired, no magazine capacity is touched.
 *
 * <p>Beam <em>periodic</em> consumption uses a separate path
 * ({@code handleBeamWeapon → consumeAmmo}); see {@link AmmoSaverBeamGate}.
 *
 * <p>No Minecraft / SG / Jobs+ dependencies — pure, deterministic, unit-testable.
 */
public final class AmmoSaverLogic {

    private AmmoSaverLogic() {
    }

    /**
     * The new {@code AmmoCount} for an ordinary shot.
     *
     * @param oldCount the current {@code AmmoCount} (>= 0)
     * @param save     whether the ammo-saver roll succeeded
     * @return {@code oldCount} when saving, else {@code Math.max(0, oldCount - 1)}
     */
    public static int newAmmoCount(int oldCount, boolean save) {
        if (save && oldCount >= 1) {
            return oldCount;
        }
        return Math.max(0, oldCount - 1);
    }

    /**
     * Whether SG's {@code Gun.clearLoadedProjectileItem(stack)} runs for the
     * given new count (exactly when the count reached 0).
     */
    public static boolean shouldClearLoadedProjectile(int newCount) {
        return newCount == 0;
    }
}
