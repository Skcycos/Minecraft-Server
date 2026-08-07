package com.tanrunn.tcth.impl.compat.scguns;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

import top.ribs.scguns.common.FireMode;
import top.ribs.scguns.common.Gun;
import top.ribs.scguns.entity.projectile.ProjectileEntity;
import top.ribs.scguns.init.ModDamageTypes;
import top.ribs.scguns.item.GunItem;

/**
 * Strong-evidence "is this damage from a Scorched Guns firearm?" check used by
 * the gunner ability routes (phase 5B).
 *
 * <p>Reuses the same evidence rules as the 5A.1 kill-confirmation framework
 * without modifying it: a SG {@link ProjectileEntity} whose shooter is a real
 * player (covers bullets, shells, specials, plasma and explosions — the blast
 * body is itself a ProjectileEntity subclass), the SG beam path
 * ({@code scguns:bullet}, null direct entity, beam/semi-beam main-hand gun),
 * and a vanilla {@link Arrow} registered at birth by {@link NiamiArrowRegistry}.
 *
 * <p>Melee, vanilla bows/crossbows, environmental damage, FakePlayers, turrets
 * and unregistered arrows are never classified as SG firearm damage.
 *
 * <p>All Scorched Guns references live exclusively in this package.
 */
public final class SgDamageEvidence {

    /**
     * Test seam: the strong-evidence check itself. Production defaults to the
     * real {@link ProjectileEntity}/beam/Niami rules below; tests inject a
     * predicate to exercise the ability-application layer deterministically.
     */
    public static java.util.function.Predicate<DamageSource> evidenceCheck = SgDamageEvidence::defaultCheck;

    private SgDamageEvidence() {
    }

    /**
     * Whether the damage source can be proven to come from a Scorched Guns
     * firearm fired by a real (non-FakePlayer) player.
     *
     * @param source the damage source to inspect
     * @param victim the damage victim (may be {@code null})
     */
    public static boolean isSgFirearmDamage(DamageSource source, @Nullable Entity victim) {
        try {
            return evidenceCheck.test(source);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean defaultCheck(DamageSource source) {
        if (source == null) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        // 1. SG ProjectileEntity (bullets / shells / specials / plasma / rockets /
        //    grenades — the explosion body is a ProjectileEntity subclass).
        if (direct instanceof ProjectileEntity projectile) {
            return isRealPlayer(projectile.getShooter());
        }
        // 2. SG beam: scguns:bullet with a null direct entity, caused by a real
        //    player whose main hand holds a beam/semi-beam SG gun.
        if (direct == null && source.is(ModDamageTypes.BULLET)) {
            Entity causing = source.getEntity();
            if (causing instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
                ItemStack held = player.getMainHandItem();
                return isBeamGun(held);
            }
            return false;
        }
        // 3. Niami vanilla Arrow: registered at birth and still tracked.
        if (direct instanceof Arrow arrow) {
            return NiamiArrowRegistry.isRegistered(arrow.getUUID());
        }
        return false;
    }

    private static boolean isRealPlayer(@Nullable LivingEntity shooter) {
        return shooter instanceof ServerPlayer && !(shooter instanceof FakePlayer);
    }

    private static boolean isBeamGun(ItemStack held) {
        if (held == null || held.isEmpty() || !(held.getItem() instanceof GunItem gunItem)) {
            return false;
        }
        try {
            Gun gun = gunItem.getModifiedGun(held);
            if (gun == null) {
                return false;
            }
            FireMode fireMode = gun.getGeneral().getFireMode();
            return FireMode.BEAM.equals(fireMode) || FireMode.SEMI_BEAM.equals(fireMode);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Restores the production strong-evidence check (test hook). */
    public static void resetForTesting() {
        evidenceCheck = SgDamageEvidence::defaultCheck;
    }
}
