package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Built-in protection checks plus a pluggable area provider (phase 8B).
 *
 * <p>Check order:
 * <ol>
 *   <li>self target ({@link ShadowProtectionResult#DENIED_SELF});</li>
 *   <li>gamemode of thief and player target (spectator / creative →
 *       {@link ShadowProtectionResult#DENIED_GAMEMODE});</li>
 *   <li>player target state (dead / disconnected / FakePlayer →
 *       {@link ShadowProtectionResult#DENIED_TARGET});</li>
 *   <li>area provider (default deny-all → {@link ShadowProtectionResult#DENIED_AREA}).</li>
 * </ol>
 *
 * <p>With the default deny-all area provider the built-in service denies
 * every attempt; a real area provider is supplied by later compat modules.
 * An exception anywhere fails closed to {@link ShadowProtectionResult#UNKNOWN}.
 */
public final class ShadowBuiltinProtectionService implements ShadowProtectionService {

    private final ShadowAreaProtectionProvider areaProvider;

    public ShadowBuiltinProtectionService(ShadowAreaProtectionProvider areaProvider) {
        this.areaProvider = Objects.requireNonNull(areaProvider, "areaProvider");
    }

    public ShadowBuiltinProtectionService() {
        this(ShadowAreaProtectionProvider.denyAll());
    }

    @Override
    public ShadowProtectionResult check(ShadowAttemptContext context) {
        try {
            if (context.targetKind() == ShadowTargetKind.PLAYER
                    && context.targetId().equals(context.thief().getUUID())) {
                return ShadowProtectionResult.DENIED_SELF;
            }
            if (isGamemodeDenied(context.thief())) {
                return ShadowProtectionResult.DENIED_GAMEMODE;
            }
            if (context.targetKind() == ShadowTargetKind.PLAYER) {
                ServerPlayer target = resolvePlayerTarget(context);
                if (target == null) {
                    return ShadowProtectionResult.DENIED_TARGET;
                }
                if (isGamemodeDenied(target)) {
                    return ShadowProtectionResult.DENIED_GAMEMODE;
                }
                if (target.isDeadOrDying() || target.hasDisconnected()) {
                    return ShadowProtectionResult.DENIED_TARGET;
                }
            }
            return areaProvider.checkArea(context);
        } catch (RuntimeException | LinkageError e) {
            return ShadowProtectionResult.UNKNOWN; // fail closed
        }
    }

    private static boolean isGamemodeDenied(Player player) {
        if (player instanceof FakePlayer) {
            return true;
        }
        return player.isSpectator() || player.isCreative();
    }

    @Nullable
    private static ServerPlayer resolvePlayerTarget(ShadowAttemptContext context) {
        Player player = context.level().getPlayerByUUID(context.targetId());
        if (player instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        }
        return null;
    }
}
