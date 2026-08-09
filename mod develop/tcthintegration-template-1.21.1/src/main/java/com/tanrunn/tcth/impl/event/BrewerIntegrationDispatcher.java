package com.tanrunn.tcth.impl.event;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Central publishing entry point for {@link BeveragePreparedEvent} (phase 7B).
 *
 * <p>Rules:
 * <ul>
 *   <li><strong>Total switch</strong>: nothing is posted unless both
 *       {@code Config.ENABLED} and {@code Config.BREWER_INTEGRATION_ENABLED}
 *       are true (fail-closed: any config exception or false switch → no
 *       event);</li>
 *   <li><strong>Server only</strong>: client-side levels are rejected;</li>
 *   <li><strong>Unique event id</strong>: a fresh {@link UUID} per event;</li>
 *   <li><strong>FakePlayer</strong>: a {@link FakePlayer} actor is treated as
 *       {@code automated=true};</li>
 *   <li>No experience, gold, stats or ability-tree logic lives here — the
 *       event carries no reward/settled state.</li>
 * </ul>
 */
public final class BrewerIntegrationDispatcher {

    public enum Result {
        /** Event was actually posted. */
        POSTED,
        /** Framework or brewer switch disabled (fail-closed). */
        DISABLED,
        /** Not a server context. */
        INVALID_CONTEXT,
        /** No dish-like result. */
        NOT_A_BEVERAGE
    }

    private static BooleanSupplier frameworkEnabledSupplier = BrewerIntegrationDispatcher::readFrameworkEnabled;
    private static BooleanSupplier brewerEnabledSupplier = BrewerIntegrationDispatcher::readBrewerEnabled;

    /**
     * Injectable FakePlayer predicate (phase 7B.1). Production default:
     * {@code player instanceof FakePlayer}. Tests replace it to prove both a
     * real player (false) and an automated actor (true).
     */
    private static Predicate<ServerPlayer> fakePlayerPredicate = p -> p instanceof FakePlayer;

    /** Test-injectable game bus; when null, {@link NeoForge#EVENT_BUS} is used. */
    private static IEventBus gameBus;

    private BrewerIntegrationDispatcher() {
    }

    /**
     * Publishes one beverage-prepared event.
     *
     * @param player    the acting player, or {@code null} for automated
     *                  production (FakePlayer is normalised to automated)
     * @param recipeId  recipe id, or {@code null} (e.g. BAC Keg: no pouring
     *                  recipe id)
     * @param result    the prepared beverage stack; must not be null
     * @param device    the producing device; must not be null
     * @param tier      the beverage tier; must not be null
     * @param level     the server level; must not be null
     * @param position  the device position, or {@code null}
     * @return the dispatch result
     */
    public static Result publish(@Nullable ServerPlayer player, @Nullable ResourceLocation recipeId, ItemStack result,
                                 BeverageDevice device, BeverageTier tier, ServerLevel level,
                                 @Nullable BlockPos position) {
        try {
            if (!frameworkEnabledSupplier.getAsBoolean() || !brewerEnabledSupplier.getAsBoolean()) {
                return Result.DISABLED;
            }
            if (level == null || level.isClientSide()) {
                return Result.INVALID_CONTEXT;
            }
            if (result == null || result.isEmpty()) {
                return Result.NOT_A_BEVERAGE;
            }
            boolean automated = player == null || fakePlayerPredicate.test(player);
            ServerPlayer effectivePlayer = automated ? null : player;
            IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
            BeveragePreparedEvent event = new BeveragePreparedEvent(
                    UUID.randomUUID(), effectivePlayer, recipeId, result, device, tier, automated, level, position);
            bus.post(event);
            return Result.POSTED;
        } catch (RuntimeException | LinkageError e) {
            // Fail-closed: never propagate config/bus errors as published events.
            return Result.DISABLED;
        }
    }

    private static boolean readFrameworkEnabled() {
        return Config.ENABLED.get();
    }

    private static boolean readBrewerEnabled() {
        return Config.BREWER_INTEGRATION_ENABLED.get();
    }

    // ---- test hooks (not part of the public API) ----

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setBrewerEnabledSupplierForTesting(BooleanSupplier supplier) {
        brewerEnabledSupplier = supplier;
    }

    static void setFakePlayerPredicateForTesting(Predicate<ServerPlayer> predicate) {
        fakePlayerPredicate = predicate == null ? (p -> p instanceof FakePlayer) : predicate;
    }

    static void setGameBusForTesting(IEventBus bus) {
        gameBus = bus;
    }

    static void resetForTesting() {
        frameworkEnabledSupplier = BrewerIntegrationDispatcher::readFrameworkEnabled;
        brewerEnabledSupplier = BrewerIntegrationDispatcher::readBrewerEnabled;
        fakePlayerPredicate = p -> p instanceof FakePlayer;
        gameBus = null;
    }
}
