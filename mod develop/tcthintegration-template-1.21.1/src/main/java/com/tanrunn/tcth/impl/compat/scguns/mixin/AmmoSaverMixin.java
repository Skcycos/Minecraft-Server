package com.tanrunn.tcth.impl.compat.scguns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.impl.compat.jobsplus.powerup.GunnerAbilityModule;
import com.tanrunn.tcth.impl.compat.scguns.AmmoSaverBeamGate;
import com.tanrunn.tcth.impl.compat.scguns.AmmoSaverLogic;
import com.tanrunn.tcth.impl.compat.scguns.AmmoSaverStackRead;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import top.ribs.scguns.common.network.ServerPlayHandler;
import top.ribs.scguns.network.message.C2SMessageShoot;

/**
 * Conditional mixin: ammo-saver route (phase 5B / 5B.1 / 5B.1.1).
 *
 * <p><strong>Two distinct real-deduction entry points exist in SG 1.5</strong>
 * (confirmed by {@code javap -p -c} on
 * {@code Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar}):
 *
 * <pre>
 * handleShoot
 *  ├─ BEAM / SEMI_BEAM → handleBeamWeapon
 *  ├─ 普通弹丸 / Niami → 创建并发射弹丸 (addFreshEntity / fireProjectiles)
 *  └─ 所有成功 handleShoot 分支汇合到公共内联扣弹块 (offset ~408+)
 *       !creative ∧ !IgnoreAmmo ∧ Reclaimed miss
 *       → AmmoCount = Math.max(0, AmmoCount - 1)   ← @Redirect
 *
 * handleBeamWeapon (FireMode.BEAM only, after consumption delay):
 *  └─ consumeAmmo(player, stack)                   ← @Inject HEAD
 *     (FireMode.SEMI_BEAM never enters this branch)
 * </pre>
 *
 * <p>Projectile / beam handling runs <em>before</em> the common inline
 * deduction block. A single BEAM {@code handleShoot} may therefore hit both
 * real-deduction entries (common {@code Math.max} plus a periodic
 * {@code consumeAmmo}); each real entry rolls at most once. Do not claim
 * "exactly one roll per shot" across every fire mode.
 *
 * <ol>
 *   <li><b>Beam consumption period</b> — {@code handleBeamWeapon} calls
 *       {@code consumeAmmo} only when {@code FireMode.BEAM} and the
 *       consumption delay has elapsed (unique call site). HEAD inject is
 *       cancellable: preconditions must match SG's real-deduction path
 *       ({@code !creative}, {@code !IgnoreAmmo}, {@code AmmoCount > 0})
 *       before any probability roll; see {@link AmmoSaverBeamGate}.</li>
 *   <li><b>Common inline deduction</b> — every successful
 *       {@code handleShoot} (ordinary guns, shotguns, rockets, grenades,
 *       Niami, BEAM start, SEMI_BEAM) reaches the shared
 *       {@code Math.max(0, AmmoCount - 1)} (unique in handleShoot). Redirect
 *       returns {@code oldCount} on a successful roll so the subsequent
 *       {@code clearLoadedProjectileItem} is skipped even at 1 round left.
 *       Creative / {@code IgnoreAmmo} / Reclaimed-hit jump over this block
 *       entirely (never reach the Redirect).</li>
 * </ol>
 *
 * <p>Requires <em>both</em> {@code scguns} and {@code jobsplus} (the roll goes
 * through {@link GunnerAbilityModule}); registered behind
 * {@code requiredMods=["scguns","jobsplus"]} in its own mixin config.
 */
@Mixin(ServerPlayHandler.class)
public abstract class AmmoSaverMixin {

    // ---- beam consumption period (consumeAmmo only; BEAM delay path) ----

    @Inject(method = "consumeAmmo(Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private static void tcth$beamPeriodSave(ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        // Fail-closed outer shell: any unexpected error leaves SG to run.
        try {
            boolean cancel = AmmoSaverBeamGate.shouldCancelConsumeAmmo(
                    player != null,
                    stack != null && !stack.isEmpty(),
                    player != null && player.isCreative(),
                    () -> AmmoSaverStackRead.readFields(stack),
                    () -> GunnerAbilityModule.ammoSaverShouldSave(player));
            if (cancel) {
                ci.cancel();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Do not cancel; SG original consumeAmmo continues.
        }
    }

    // ---- ordinary / common handleShoot deduction (unique Math.max) ----

    @Redirect(method = "handleShoot(Ltop/ribs/scguns/network/message/C2SMessageShoot;"
            + "Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private static int tcth$ordinaryShotSave(int a, int b, C2SMessageShoot message, ServerPlayer player) {
        // Mixin @Redirect handler signature: redirected-call args FIRST, then the
        // target method's args. javap: this Math.max is handleShoot's
        // `Math.max(0, AmmoCount - 1)`; b = AmmoCount - 1. On a successful roll
        // return the original AmmoCount (b + 1); on failure the original value.
        // Creative / IgnoreAmmo never reach this instruction in SG bytecode.
        int oldCount = b + 1;
        boolean save = oldCount >= 1 && GunnerAbilityModule.ammoSaverShouldSave(player);
        return AmmoSaverLogic.newAmmoCount(oldCount, save);
    }
}
