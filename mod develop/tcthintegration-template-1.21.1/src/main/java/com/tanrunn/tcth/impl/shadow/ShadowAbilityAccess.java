package com.tanrunn.tcth.impl.shadow;

import java.util.function.Function;

import net.minecraft.server.level.ServerPlayer;

/**
 * Bridge through which the shadow framework queries the ability snapshot
 * (phase 8E).
 *
 * <p>The default provider returns {@link ShadowAbilitySnapshot#none()} —
 * basic behaviour, no Jobs+ anywhere. The conditional Jobs+ compat module
 * installs the real provider ({@code ShadowAbilityModule}) when Jobs+ is
 * loaded; the query is performed <em>at most once per theft attempt</em> by
 * the interaction handler, and the same snapshot flows to the candidate
 * pool, the success chance, the transfer prepare, the cooldown and the
 * feedback layers.
 *
 * <p>Query failures are caught and mapped to {@code none()} (fail-closed).
 * This class is pure TCTH/MC: it never references Jobs+/Arc types.
 */
public final class ShadowAbilityAccess {

    private static Function<ServerPlayer, ShadowAbilitySnapshot> provider =
            player -> ShadowAbilitySnapshot.none();

    private ShadowAbilityAccess() {
    }

    /**
     * @param player the thief; {@code null} is refused with the none snapshot
     * @return the ability snapshot for one attempt, or {@code none()} when no
     *         provider is installed, the player is null or the query failed
     */
    public static ShadowAbilitySnapshot snapshotFor(ServerPlayer player) {
        if (player == null) {
            return ShadowAbilitySnapshot.none();
        }
        try {
            ShadowAbilitySnapshot snapshot = provider.apply(player);
            return snapshot != null ? snapshot : ShadowAbilitySnapshot.none();
        } catch (RuntimeException | LinkageError e) {
            return ShadowAbilitySnapshot.none();
        }
    }

    // ---- test / wiring hooks (not part of the public API) ----

    /**
     * Installs the ability provider. The conditional Jobs+ compat module
     * installs {@code ShadowAbilityModule} when Jobs+ is loaded; tests
     * install counting/fake providers.
     */
    public static void setProvider(Function<ServerPlayer, ShadowAbilitySnapshot> provider) {
        ShadowAbilityAccess.provider = provider != null
                ? provider
                : p -> ShadowAbilitySnapshot.none();
    }

    public static void resetForTesting() {
        provider = p -> ShadowAbilitySnapshot.none();
    }
}
