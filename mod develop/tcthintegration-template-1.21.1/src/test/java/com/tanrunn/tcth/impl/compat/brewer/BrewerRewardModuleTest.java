package com.tanrunn.tcth.impl.compat.brewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.compat.brewer.BrewerRewardModule.BeverageActionSender;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 7C settlement rules for {@link BrewerRewardModule}:
 * three-way switch, actor/automation guards, UNKNOWN/T3 no-settle,
 * idempotency committed only on send success, per-tick rate limit and
 * single-event failure isolation.
 */
class BrewerRewardModuleTest {

    private ServerLevel level;
    private ServerPlayer player;
    private AtomicInteger sendCount;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        BrewerRewardModule.resetForTesting();
        BrewerRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        BrewerRewardModule.setIntegrationEnabledSupplierForTesting(() -> true);
        BrewerRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        BrewerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 20);
        sendCount = new AtomicInteger();
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        });
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getGameProfile())
                .thenReturn(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "tester"));
    }

    @AfterEach
    void tearDown() {
        BrewerRewardModule.resetForTesting();
    }

    private BeveragePreparedEvent event(BeverageTier tier, boolean automated, ServerPlayer p) {
        return new BeveragePreparedEvent(UUID.randomUUID(), p, null, new ItemStack(Items.POTION),
                BeverageDevice.KEG, tier, automated, level, null);
    }

    @Test
    void commonAndT2EachSettleExactlyOnce() {
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, player));
        assertEquals(1, sendCount.get(), "COMMON must settle once");
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.T2, false, player));
        assertEquals(2, sendCount.get(), "T2 must settle once");
    }

    @Test
    void unknownT3AutomatedAndNullPlayerSettleZero() {
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.UNKNOWN, false, player));
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.T3, false, player));
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, true, player));
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, null));
        assertEquals(0, sendCount.get(), "UNKNOWN/T3/automated/null player must settle 0");
    }

    @Test
    void threeWaySwitchFailClosed() {
        BrewerRewardModule.setFrameworkEnabledSupplierForTesting(() -> false);
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, player));
        assertEquals(0, sendCount.get(), "framework off must settle 0");

        BrewerRewardModule.resetForTesting();
        BrewerRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        BrewerRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        BrewerRewardModule.setIntegrationEnabledSupplierForTesting(() -> false);
        BrewerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 20);
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        });
        BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, player));
        assertEquals(0, sendCount.get(), "integration off must settle 0");
    }

    @Test
    void idempotencyCommittedOnlyOnSuccess() {
        // Same event id twice → settle once.
        BeveragePreparedEvent ev = event(BeverageTier.COMMON, false, player);
        BrewerRewardModule.onBeveragePrepared(ev);
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(1, sendCount.get(), "duplicate eventId must settle once");
    }

    @Test
    void sendFailureThenRetrySameEventSettlesOnce() {
        // Same event instance: first send fails, retry with the SAME event
        // must settle and commit idempotency exactly once.
        BeveragePreparedEvent ev = event(BeverageTier.COMMON, false, player);
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> null); // failure
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(0, sendCount.get(), "failed send must not settle");

        // Retry with the SAME event id — must settle exactly once.
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        });
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(1, sendCount.get(), "retry of the same event must settle once");
        // A further duplicate of the same event is suppressed by idempotency.
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(1, sendCount.get(), "duplicate after commit must be suppressed");
    }

    @Test
    void perTickRateLimitDropsExcess() {
        BrewerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 2);
        BeverageActionSender sender = (p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        };
        BrewerRewardModule.setActionSenderForTesting(sender);
        for (int i = 0; i < 5; i++) {
            BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, player));
        }
        assertEquals(2, sendCount.get(), "rate limit 2/tick must drop the rest");
    }

    @Test
    void exceptionIsIsolatedAndSameEventRetryable() {
        BeveragePreparedEvent ev = event(BeverageTier.COMMON, false, player);
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> {
            throw new RuntimeException("boom");
        });
        // Must not throw out of the handler and must not record idempotency.
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(0, sendCount.get());
        // Retry the SAME event with a working sender — must settle once.
        BrewerRewardModule.setActionSenderForTesting((p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        });
        BrewerRewardModule.onBeveragePrepared(ev);
        assertEquals(1, sendCount.get(), "same event must be retryable after an isolated exception");
    }

    @Test
    void idempotencyCacheHardCapNeverExceeds4096() {
        BrewerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 100000);
        BeverageActionSender sender = (p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        };
        BrewerRewardModule.setActionSenderForTesting(sender);
        // Fire far more than the 4096 cap within the same tick window.
        int total = 5000;
        for (int i = 0; i < total; i++) {
            BrewerRewardModule.onBeveragePrepared(event(BeverageTier.COMMON, false, player));
        }
        assertEquals(total, sendCount.get(), "all events settle (rate limit lifted)");
        // The idempotency cache must never exceed the hard cap.
        int cached = BrewerRewardModule.cachedEventIdCountForTesting();
        assertTrue(cached <= BrewerRewardModule.MAX_TRACKED_EVENT_IDS_FOR_TESTING,
                "idempotency cache must never exceed 4096, was " + cached);
    }

    @Test
    void playerLogoutClearsOnlyThatPlayersState() {
        ServerPlayer playerA = player;
        ServerPlayer playerB = Mockito.mock(ServerPlayer.class);
        Mockito.when(playerB.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.when(playerB.getGameProfile())
                .thenReturn(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "playerB"));
        BeverageActionSender sender = (p, e, t) -> {
            sendCount.incrementAndGet();
            return Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class);
        };
        BrewerRewardModule.setActionSenderForTesting(sender);

        BeveragePreparedEvent evA = event(BeverageTier.COMMON, false, playerA);
        BeveragePreparedEvent evB = event(BeverageTier.COMMON, false, playerB);
        BrewerRewardModule.onBeveragePrepared(evA);
        BrewerRewardModule.onBeveragePrepared(evB);
        assertEquals(2, sendCount.get());

        // Player A logs out: A's event id and tick count are cleared, B keeps his.
        net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent logoutA =
                new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(playerA);
        BrewerRewardModule.onPlayerLoggedOut(logoutA);

        // A's event is now retryable (idempotency cleared); B's is not.
        BrewerRewardModule.onBeveragePrepared(evA);
        assertEquals(3, sendCount.get(), "A's event must settle again after logout");
        BrewerRewardModule.onBeveragePrepared(evB);
        assertEquals(3, sendCount.get(), "B's event must remain suppressed (idempotent)");
    }
}
