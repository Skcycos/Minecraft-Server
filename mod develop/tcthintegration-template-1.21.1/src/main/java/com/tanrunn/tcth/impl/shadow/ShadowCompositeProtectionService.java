package com.tanrunn.tcth.impl.shadow;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Composite protection service (phase 8C.0).
 *
 * <p>Check order (all fail-closed):
 * <ol>
 *   <li>self target → {@link ShadowProtectionResult#DENIED_SELF};</li>
 *   <li>FakePlayer (thief or player target) → {@code DENIED_TARGET};</li>
 *   <li>spectator / creative gamemode → {@code DENIED_GAMEMODE};</li>
 *   <li>player target dead / disconnected / unresolvable → {@code DENIED_TARGET};</li>
 *   <li>new-player protection: a target whose verified play time
 *       ({@code Stats.PLAY_TIME}, server-side ticks) is below the configured
 *       threshold → {@code DENIED_NEW_PLAYER};</li>
 *   <li>spawn protection: the vanilla server check
 *       ({@link MinecraftServer#isUnderSpawnProtection}) — a null position
 *       cannot be evaluated and fails closed to {@code DENIED_AREA};</li>
 *   <li>the pluggable area provider (Open Parties and Claims in production,
 *       {@code null} when absent → {@code DENIED_AREA}; UNKNOWN → deny).</li>
 * </ol>
 *
 * <p>Main-city / shop-area protection is <em>not</em> claimed: there is no
 * reliable coordinate source for it, so this service never pretends to
 * support it (stage 8A §6).
 */
public final class ShadowCompositeProtectionService implements ShadowProtectionService {

    private final @Nullable ShadowAreaProtectionProvider areaProvider;
    private final Supplier<Long> newPlayerProtectionTicksSupplier;

    /**
     * @param areaProvider                  the area provider (OPAC in
     *                                      production); {@code null} fails
     *                                      closed to {@code DENIED_AREA}
     * @param newPlayerProtectionTicksSupplier play-time threshold in ticks
     *                                      for the new-player protection
     */
    public ShadowCompositeProtectionService(@Nullable ShadowAreaProtectionProvider areaProvider,
                                            Supplier<Long> newPlayerProtectionTicksSupplier) {
        this.areaProvider = areaProvider;
        this.newPlayerProtectionTicksSupplier = newPlayerProtectionTicksSupplier;
    }

    @Override
    public ShadowProtectionResult check(ShadowAttemptContext context) {
        try {
            Player thief = context.thief();
            if (context.targetKind() == ShadowTargetKind.PLAYER
                    && context.targetId().equals(thief.getUUID())) {
                return ShadowProtectionResult.DENIED_SELF;
            }
            if (thief instanceof FakePlayer || thief.isSpectator() || thief.isCreative()) {
                return ShadowProtectionResult.DENIED_GAMEMODE;
            }
            if (context.targetKind() == ShadowTargetKind.PLAYER) {
                ServerPlayer target = resolvePlayerTarget(context);
                if (target == null) {
                    return ShadowProtectionResult.DENIED_TARGET;
                }
                if (target instanceof FakePlayer) {
                    return ShadowProtectionResult.DENIED_TARGET;
                }
                if (target.isSpectator() || target.isCreative()) {
                    return ShadowProtectionResult.DENIED_GAMEMODE;
                }
                if (target.isDeadOrDying() || target.hasDisconnected()) {
                    return ShadowProtectionResult.DENIED_TARGET;
                }
                if (playTimeTicks(target) < newPlayerProtectionTicks()) {
                    return ShadowProtectionResult.DENIED_NEW_PLAYER;
                }
            }
            if (context.position() == null) {
                return ShadowProtectionResult.DENIED_AREA; // cannot evaluate
            }
            ServerLevel level = context.level();
            MinecraftServer server = level.getServer();
            if (server != null && server.isUnderSpawnProtection(level, context.position(), thief)) {
                return ShadowProtectionResult.DENIED_AREA;
            }
            if (areaProvider == null) {
                return ShadowProtectionResult.DENIED_AREA; // OPAC absent → deny
            }
            return areaProvider.checkArea(context);
        } catch (RuntimeException | LinkageError e) {
            return ShadowProtectionResult.UNKNOWN; // fail closed
        }
    }

    /** Verified server-side play time in ticks (never wall-clock guesses). */
    private static long playTimeTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    private long newPlayerProtectionTicks() {
        try {
            Long value = newPlayerProtectionTicksSupplier.get();
            return value != null && value >= 0L ? value : Long.MAX_VALUE;
        } catch (RuntimeException | LinkageError e) {
            // Config read failure fails CLOSED: treat every player as a new
            // player (deny) instead of silently disabling the protection.
            return Long.MAX_VALUE;
        }
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
