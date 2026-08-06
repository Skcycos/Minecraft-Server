package com.tanrunn.tcth.impl.compat.scguns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.impl.compat.scguns.NiamiArrowRegistry;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.Level;

import top.ribs.scguns.common.Gun;
import top.ribs.scguns.common.network.ServerPlayHandler;

/**
 * Conditional mixin: registers the vanilla {@link Arrow} spawned by SG's
 * Niami gun at <em>birth</em> (phase 5A.1).
 *
 * <p>SG fires Niami as a vanilla {@code Arrow} (gun config
 * {@code "item": "minecraft:arrow", "firesArrows": true}) from the private
 * static {@code ServerPlayHandler.getArrow(ServerPlayer, Level, Gun.Projectile)}.
 * We inject at RETURN, take the frozen main-hand weapon snapshot and hand it to
 * {@link NiamiArrowRegistry}. The weapon snapshot is captured at firing time,
 * so switching items afterwards cannot change attribution.
 *
 * <p>This mixin config is registered in {@code neoforge.mods.toml} with
 * {@code requiredMods=["scguns"]} — it is never applied (and the class never
 * resolved) when Scorched Guns is absent. If the injection cannot be applied
 * on the installed SG 1.5 JAR the mixin fails loudly at startup (never falls
 * back to a permissive "player holds a gun" heuristic).
 */
@Mixin(ServerPlayHandler.class)
public abstract class NiamiArrowSpawnMixin {

    @Inject(method = "getArrow(Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/world/level/Level;"
            + "Ltop/ribs/scguns/common/Gun$Projectile;)"
            + "Lnet/minecraft/world/entity/projectile/Arrow;",
            at = @At("RETURN"))
    private static void tcth$registerNiamiArrow(ServerPlayer player, Level level,
                                                Gun.Projectile projectile,
                                                CallbackInfoReturnable<Arrow> cir) {
        // RETURN-injection callback signature: target args + CallbackInfoReturnable
        // ONLY. Never declare the return value as an extra parameter (this
        // previously caused InvalidInjectionException — the value comes from
        // cir.getReturnValue()).
        Arrow created = cir.getReturnValue();
        if (created != null) {
            NiamiArrowRegistry.register(created, player);
        }
    }
}
