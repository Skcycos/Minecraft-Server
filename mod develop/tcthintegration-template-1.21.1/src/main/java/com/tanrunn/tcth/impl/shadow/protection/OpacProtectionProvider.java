package com.tanrunn.tcth.impl.shadow.protection;

import com.tanrunn.tcth.impl.shadow.ShadowAreaProtectionProvider;
import com.tanrunn.tcth.impl.shadow.ShadowAttemptContext;
import com.tanrunn.tcth.impl.shadow.ShadowProtectionResult;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.common.util.FakePlayer;
import xaero.pac.common.server.api.OpenPACServerAPI;

/**
 * Open Parties and Claims area provider (phase 8C.0).
 *
 * <p>Uses OPAC's actual entity-interaction permission query (8A audit,
 * javap-verified): {@code OpenPACServerAPI.get(server).getChunkProtection()
 * .onEntityInteraction(interactor, interactor, target, null, MAIN_HAND,
 * false, false, true)} — the exact call pattern OPAC itself uses from its own entity-interact
 * event handler. {@code true} (blocked)
 * maps to {@link ShadowProtectionResult#DENIED_AREA}.
 *
 * <p>Fail-closed: any API exception, linkage error, unknown state or an
 * unresolvable target maps to {@code DENIED_AREA}.
 *
 * <p><b>Side effect (documented):</b> OPAC's query sends the player its
 * protection message when blocked. The TCTH interaction handler registers at
 * LOW event priority and skips already-cancelled events, so in the normal
 * flow OPAC's own handler has already cancelled and messaged — this re-check
 * only messages when the listener order differs.
 *
 * <p><b>Isolation:</b> this class is only ever loaded (via
 * {@link OpacProtectionProviderFactory}) when Open Parties and Claims is
 * installed; the rest of TCTH carries no OPAC reference.
 */
public final class OpacProtectionProvider implements ShadowAreaProtectionProvider {

    private static java.util.function.Function<MinecraftServer, OpenPACServerAPI> apiResolver =
            OpenPACServerAPI::get;

    public OpacProtectionProvider() {
    }

    @Override
    public ShadowProtectionResult checkArea(ShadowAttemptContext context) {
        try {
            ServerPlayer thief = context.thief();
            if (thief instanceof FakePlayer) {
                return ShadowProtectionResult.DENIED_AREA;
            }
            Entity target = context.level().getEntity(context.targetId());
            if (target == null) {
                return ShadowProtectionResult.DENIED_AREA; // cannot evaluate
            }
            MinecraftServer server = context.level().getServer();
            if (server == null) {
                return ShadowProtectionResult.DENIED_AREA;
            }
            boolean blocked = apiResolver.apply(server)
                    .getChunkProtection()
                    .onEntityInteraction(thief, thief, target, null,
                            InteractionHand.MAIN_HAND, false, false, true);
            return blocked ? ShadowProtectionResult.DENIED_AREA : ShadowProtectionResult.ALLOWED;
        } catch (RuntimeException | LinkageError e) {
            return ShadowProtectionResult.DENIED_AREA; // fail closed
        }
    }

    // ---- test hooks (not part of the public API) ----

    static void setApiResolverForTesting(java.util.function.Function<MinecraftServer, OpenPACServerAPI> resolver) {
        apiResolver = resolver;
    }

    static void resetForTesting() {
        apiResolver = OpenPACServerAPI::get;
    }
}
