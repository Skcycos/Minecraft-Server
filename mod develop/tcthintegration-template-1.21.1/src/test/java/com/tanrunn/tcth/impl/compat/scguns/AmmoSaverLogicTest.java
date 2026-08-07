package com.tanrunn.tcth.impl.compat.scguns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 5B.1 / 5B.1.1: common {@code handleShoot} ammo-deduction semantics —
 * mirrors the SG shared post-fire deduction block (offset ~470-512,
 * confirmed by {@code javap -p -c}). Projectiles / beam handling already ran
 * earlier in the same method; this block covers ordinary guns, shotguns,
 * rockets, grenades, Niami, BEAM and SEMI_BEAM after those branches rejoin:
 *
 * <pre>
 *   oldCount     = tag.getInt("AmmoCount")
 *   newAmmoCount = Math.max(0, oldCount - 1)
 *   tag.putInt("AmmoCount", newAmmoCount); setCustomData(...)
 *   if (newAmmoCount == 0) Gun.clearLoadedProjectileItem(stack)
 * </pre>
 *
 * <p>Successful saves keep {@code AmmoCount} at its original value and never
 * produce a zero, so {@code clearLoadedProjectileItem} is never invoked (even
 * at 1 round left). Failed rolls reproduce the original SG behavior exactly.
 *
 * <p>Beam-period {@code consumeAmmo} preconditions (creative / IgnoreAmmo /
 * no roll) live in {@link AmmoSaverBeamGateTest}, not here.
 */
class AmmoSaverLogicTest {

    // ---- probability failed: N -> N-1 ----

    @Test
    void failedRollDeductsNormally() {
        assertEquals(9, AmmoSaverLogic.newAmmoCount(10, false), "10 -> 9 on failed roll");
        assertEquals(0, AmmoSaverLogic.newAmmoCount(1, false), "1 -> 0 on failed roll");
        assertEquals(0, AmmoSaverLogic.newAmmoCount(0, false), "0 -> 0 (empty)");
    }

    // ---- probability succeeded: N -> N ----

    @Test
    void successfulRollKeepsAmmoCount() {
        assertEquals(10, AmmoSaverLogic.newAmmoCount(10, true), "10 -> 10 on saved roll");
        assertEquals(5, AmmoSaverLogic.newAmmoCount(5, true));
        assertEquals(2, AmmoSaverLogic.newAmmoCount(2, true));
    }

    // ---- 1 round left ----

    @Test
    void oneRoundLeftSavedStaysOneAndDoesNotClear() {
        int newCount = AmmoSaverLogic.newAmmoCount(1, true);
        assertEquals(1, newCount, "1 round left + saved roll stays 1");
        assertFalse(AmmoSaverLogic.shouldClearLoadedProjectile(newCount),
                "newAmmoCount != 0 -> clearLoadedProjectileItem must NOT run");
    }

    @Test
    void oneRoundLeftFailedClearsLoadedProjectile() {
        int newCount = AmmoSaverLogic.newAmmoCount(1, false);
        assertEquals(0, newCount, "1 round left + failed roll reaches 0");
        assertTrue(AmmoSaverLogic.shouldClearLoadedProjectile(newCount),
                "newAmmoCount == 0 -> clearLoadedProjectileItem must run (SG original)");
    }

    // ---- shotgun / rocket / grenade / Niami: one common Math.max per handleShoot ----
    //
    // The common injection point is handleShoot's single Math.max (javap
    // offset 485). Pellets / rockets / grenades / Niami are spawned earlier
    // (fireProjectiles / getArrow) then the shared deduction block runs once,
    // so that entry rolls at most once per successful handleShoot regardless
    // of pellet count. Structural: GunnerDependencyMatrixTest.

    // ---- beam period (separate entry) ----
    //
    // FireMode.BEAM only: handleBeamWeapon may additionally call consumeAmmo
    // after the consumption delay (unique call site). SEMI_BEAM does not.
    // Both fire modes still pass through the common handleShoot Math.max.
    // Each real entry rolls at most once — not "one roll per shot" for BEAM.
    // Preconditions: AmmoSaverBeamGateTest. Structure: GunnerDependencyMatrixTest.

    // ---- creative / IgnoreAmmo on common path ----
    //
    // handleShoot jumps over the Math.max block for creative / IgnoreAmmo /
    // Reclaimed-hit, so the Redirect never runs. For beam consumeAmmo, the
    // HEAD inject itself gates with AmmoSaverBeamGate (5B.1.1) because SG's
    // creative / IgnoreAmmo checks live inside consumeAmmo after HEAD.

    // ---- config off / query error / no node ----
    //
    // GunnerAbilityModule.ammoSaverShouldSave returns false in all those cases
    // (gated tests in GunnerAbilityModuleTest), so newAmmoCount(old, false) is
    // the pure SG path — covered above.

    // ---- highest tier only ----
    //
    // ammoSaveChance(tier) returns a single probability for the highest active
    // tier (GunnerAbilityModuleTest.ammoSaverBoundaryWithInjectedChanceSource).
}
