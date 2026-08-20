package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.shadow.ShadowExperienceLimitStore;
import com.tanrunn.tcth.impl.shadow.ShadowExperienceLimitWriter;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;

/**
 * Tests for {@link ShadowRewardModule} (phase 8E §7-8): the SUCCESS-only
 * gating, the automated/receipt/idempotency guards, the per-pair daily XP cap
 * with the reservation protocol (retry after a failed send, no second send
 * after success, conservative commit), the ENTITY exemption from the pair
 * limit and the logout/stop cleanup.
 */
class ShadowRewardModuleTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");

    private IEventBus bus;
    private final List<ShadowTheftEvent> sent = new ArrayList<>();
    private boolean senderFails;
    private ShadowExperienceLimitStore store;
    private UUID thiefId;
    private UUID targetId;
    private ServerLevel level;
    private ServerPlayer thief;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowRewardModule.resetForTesting();
        bus = BusBuilder.builder().build();
        ShadowRewardModule.init(bus);
        sent.clear();
        senderFails = false;
        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setMaxPerPairPerDaySupplierForTesting(() -> 3L);
        ShadowRewardModule.setDailyDateSupplierForTesting(() -> "2026-08-12");
        store = new ShadowExperienceLimitStore();
        ShadowRewardModule.setExperienceLimitStoreFactoryForTesting(level -> store);
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            if (!senderFails) {
                sent.add(event);
            }
            return senderFails ? ShadowSendResult.CLEAR_FAILURE : ShadowSendResult.SUCCESS;
        });
        level = mock(ServerLevel.class);
        thief = mock(ServerPlayer.class);
        thiefId = UUID.randomUUID();
        when(thief.getUUID()).thenReturn(thiefId);
        targetId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        ShadowRewardModule.resetForTesting();
    }

    private static HolderLookup.Provider provider() {
        return HolderLookup.Provider.create(java.util.stream.Stream.empty());
    }

    private ShadowTheftEvent successEvent(ShadowTargetKind kind, ShadowTheftType type,
                                          ShadowTheftReceipt receipt, boolean automated) {
        return new ShadowTheftEvent(UUID.randomUUID(), thief, kind, targetId,
                kind == ShadowTargetKind.ENTITY ? ResourceLocation.fromNamespaceAndPath("minecraft", "cow") : null,
                type, ShadowTheftOutcome.SUCCESS, receipt, automated, level, new BlockPos(1, 2, 3));
    }

    private ShadowTheftEvent successEventWithId(UUID eventId, ShadowTargetKind kind, ShadowTheftType type,
                                                ShadowTheftReceipt receipt) {
        return new ShadowTheftEvent(eventId, thief, kind, targetId,
                kind == ShadowTargetKind.ENTITY ? ResourceLocation.fromNamespaceAndPath("minecraft", "cow") : null,
                type, ShadowTheftOutcome.SUCCESS, receipt, false, level, new BlockPos(1, 2, 3));
    }

    // ---- gating ----

    @Test
    void switchesOffSendNothing() {
        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> false);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> false);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setRewardsEnabledSupplierForTesting(() -> false);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
    }

    @Test
    void onlySuccessSends() {
        for (ShadowTheftOutcome outcome : ShadowTheftOutcome.values()) {
            if (outcome == ShadowTheftOutcome.SUCCESS) {
                continue;
            }
            ShadowTheftEvent event = new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                    targetId, null, ShadowTheftType.ITEM, outcome, ShadowTheftReceipt.empty(),
                    false, level, new BlockPos(0, 0, 0));
            bus.post(event);
            assertEquals(0, sent.size(), "outcome " + outcome + " must grant 0 XP");
        }
    }

    @Test
    void automatedSuccessSendsNothing() {
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), true));
        assertEquals(0, sent.size());
    }

    @Test
    void emptyOrMismatchedReceiptSendsNothing() {
        // The real ShadowTheftEvent constructor already enforces the
        // SUCCESS/receipt invariant, so the module's receipt guards are
        // exercised through a mocked event that lies about its receipt.
        ShadowTheftEvent empty = mock(ShadowTheftEvent.class);
        when(empty.getEventId()).thenReturn(UUID.randomUUID());
        when(empty.getThief()).thenReturn(thief);
        when(empty.getTargetKind()).thenReturn(ShadowTargetKind.PLAYER);
        when(empty.getTargetId()).thenReturn(targetId);
        when(empty.getTheftType()).thenReturn(ShadowTheftType.ITEM);
        when(empty.getOutcome()).thenReturn(ShadowTheftOutcome.SUCCESS);
        when(empty.getReceipt()).thenReturn(ShadowTheftReceipt.empty());
        when(empty.isAutomated()).thenReturn(false);
        bus.post(empty);
        assertEquals(0, sent.size(), "an empty receipt must grant 0 XP");

        ShadowTheftEvent mismatched = mock(ShadowTheftEvent.class);
        when(mismatched.getEventId()).thenReturn(UUID.randomUUID());
        when(mismatched.getThief()).thenReturn(thief);
        when(mismatched.getTargetKind()).thenReturn(ShadowTargetKind.PLAYER);
        when(mismatched.getTargetId()).thenReturn(targetId);
        when(mismatched.getTheftType()).thenReturn(ShadowTheftType.ITEM);
        when(mismatched.getOutcome()).thenReturn(ShadowTheftOutcome.SUCCESS);
        when(mismatched.getReceipt()).thenReturn(ShadowTheftReceipt.numeric(1.0d));
        when(mismatched.isAutomated()).thenReturn(false);
        bus.post(mismatched);
        assertEquals(0, sent.size(), "a receipt that does not match the theft type must grant 0 XP");
    }

    // ---- eventId idempotency ----

    @Test
    void duplicateEventIdSendsOnce() {
        UUID eventId = UUID.randomUUID();
        bus.post(successEventWithId(eventId, ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1)));
        bus.post(successEventWithId(eventId, ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1)));
        assertEquals(1, sent.size());
    }

    @Test
    void failedSendIsRetryableThenCommitted() {
        UUID eventId = UUID.randomUUID();
        ShadowTheftEvent event = successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1));
        // First attempt: the Arc send fails → the reservation is released.
        senderFails = true;
        bus.post(event);
        assertEquals(0, sent.size(), "a failed send is not counted as granted");
        assertFalse(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 3L),
                "the failed attempt must release its reservation (retryable)");
        // Retry: send succeeds → committed, no second send possible.
        senderFails = false;
        bus.post(event);
        assertEquals(1, sent.size());
        assertEquals(1, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
        bus.post(event);
        assertEquals(1, sent.size(), "a committed eventId never sends twice");
    }

    @Test
    void committedExistingAfterRestartNeverResends() {
        UUID eventId = UUID.randomUUID();
        ShadowTheftEvent event = successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1));
        bus.post(event);
        assertEquals(1, sent.size());
        // Simulate a restart: fresh module, fresh in-memory cache, but the
        // SAME durable store still holds the committed eventId.
        ShadowRewardModule.resetForTesting();
        bus = BusBuilder.builder().build();
        ShadowRewardModule.init(bus);
        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setMaxPerPairPerDaySupplierForTesting(() -> 3L);
        ShadowRewardModule.setDailyDateSupplierForTesting(() -> "2026-08-12");
        ShadowRewardModule.setExperienceLimitStoreFactoryForTesting(level -> store);
        ShadowRewardModule.setActionSenderForTesting((player, e) -> {
            sent.add(e);
            return ShadowSendResult.SUCCESS;
        });
        bus.post(event);
        assertEquals(1, sent.size(), "COMMITTED_EXISTING must block a re-send after restart");
    }

    // ---- per-pair daily cap (PLAYER only) ----

    @Test
    void playerPairDailyCapBlocksTheFourth() {
        for (int i = 0; i < 3; i++) {
            bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(3, sent.size());
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 3L));
        // The 4th successful theft that day grants 0 XP.
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(3, sent.size());
    }

    @Test
    void dayRolloverRestoresTheQuota() {
        for (int i = 0; i < 3; i++) {
            bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(3, sent.size());
        ShadowRewardModule.setDailyDateSupplierForTesting(() -> "2026-08-13");
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(4, sent.size());
    }

    @Test
    void entityTargetsAreNotPairLimited() {
        // Entity targets stay bounded by the LOOTED once-state (8D) — the
        // per-pair daily cap must not apply to them.
        for (int i = 0; i < 5; i++) {
            bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(5, sent.size());
        assertEquals(0, store.pairCount(), "the entity path never touches the pair store");
    }

    @Test
    void unavailableStoreBlocksPlayerXpButNotEntityXp() {
        ShadowRewardModule.setExperienceLimitStoreFactoryForTesting(level -> null);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size(), "an unavailable store fails closed for PLAYER targets");
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(1, sent.size(), "ENTITY targets need no pair store");
    }

    @Test
    void invalidUtcDayBlocksPlayerXp() {
        ShadowRewardModule.setDailyDateSupplierForTesting(() -> "not-a-date");
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
    }

    @Test
    void healthHungerEffectTypesAllSend() {
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.HEALTH,
                ShadowTheftReceipt.numeric(2.0d), false));
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.HUNGER,
                ShadowTheftReceipt.numeric(3.0d), false));
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.EFFECT,
                ShadowTheftReceipt.effect(ResourceLocation.fromNamespaceAndPath("minecraft", "speed"), 200), false));
        assertEquals(3, sent.size());
    }

    // ---- lifecycle ----

    @Test
    void serverStopClearsInMemoryState() {
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(1, ShadowRewardModule.trackedEventCountForTesting());
        ShadowRewardModule.onServerStopping(null);
        assertEquals(0, ShadowRewardModule.trackedEventCountForTesting());
    }

    @Test
    void logoutClearsThatThiefsInMemoryEntries() {
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        ShadowTheftEvent other = successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false);
        bus.post(other);
        assertEquals(2, ShadowRewardModule.trackedEventCountForTesting());
        ShadowRewardModule.onPlayerLogout(new PlayerLoggedOutEvent(thief));
        assertEquals(0, ShadowRewardModule.trackedEventCountForTesting());
    }

    @Test
    void eventIdsExpireAfterTheTickWindow() {
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(1, ShadowRewardModule.trackedEventCountForTesting());
        for (int i = 0; i < ShadowRewardModule.EVENT_ID_EXPIRY_TICKS_FOR_TESTING + 1; i++) {
            ShadowRewardModule.onServerTick(null);
        }
        assertEquals(0, ShadowRewardModule.trackedEventCountForTesting());
    }

    @Test
    void moduleNeverBreaksTheTick() {
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            throw new IllegalStateException("broken sender");
        });
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        // The exception is isolated; the reservation stays conservative.
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 1L));
        assertEquals(1, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
    }

    @Test
    void senderReceivesTheSuccessEvent() {
        ShadowTheftEvent event = successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false);
        bus.post(event);
        assertEquals(1, sent.size());
        assertNotNull(sent.get(0));
        assertEquals(event.getEventId(), sent.get(0).getEventId());
    }

    // ---- 8E.1 §1: RECOVERY crash semantics ----

    @Test
    void recoveryReservationAfterRestartNeverResends() {
        // Build a crash-migrated store: a RESERVED reservation is saved and
        // reloaded → RECOVERY. The same eventId must NEVER be re-sent.
        UUID eventId = UUID.randomUUID();
        ShadowExperienceLimitStore pre = new ShadowExperienceLimitStore();
        assertEquals(ShadowExperienceLimitWriter.ReservationResult.RESERVED,
                pre.tryReserve(thiefId, targetId, "2026-08-12", eventId, 3L));
        store = ShadowExperienceLimitStore.load(pre.save(new CompoundTag(), provider()), provider());
        assertFalse(store.isFailClosed());
        ShadowTheftEvent event = successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1));
        bus.post(event);
        assertEquals(0, sent.size(), "a RECOVERY eventId must never be re-sent");
        assertEquals(1, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"),
                "the unknown outcome keeps the quota occupied");
        assertFalse(store.releaseReservation(eventId),
                "RECOVERY must never be released as a clean failure");
    }

    @Test
    void recoveryAfterRestartDoesNotConsumeAFreshQuotaSlot() {
        // One RECOVERY slot from a crashed session + two fresh successes: the
        // pair limit counts the RECOVERY slot, so the third fresh success is
        // still granted and the fourth is refused.
        UUID crashed = UUID.randomUUID();
        ShadowExperienceLimitStore pre = new ShadowExperienceLimitStore();
        assertEquals(ShadowExperienceLimitWriter.ReservationResult.RESERVED,
                pre.tryReserve(thiefId, targetId, "2026-08-12", crashed, 3L));
        store = ShadowExperienceLimitStore.load(pre.save(new CompoundTag(), provider()), provider());
        for (int i = 0; i < 2; i++) {
            bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(2, sent.size());
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(2, sent.size(), "the RECOVERY slot still counts towards the daily cap of 3");
    }

    // ---- 8E.1 §4: exception isolation ----

    @Test
    void linkageErrorInEachConfigSupplierFailsClosed() {
        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> {
            throw new LinkageError("jobsplus classes missing");
        });
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());

        ShadowRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> {
            throw new LinkageError("integration config broken");
        });
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());

        ShadowRewardModule.setIntegrationEnabledSupplierForTesting(() -> true);
        ShadowRewardModule.setRewardsEnabledSupplierForTesting(() -> {
            throw new LinkageError("rewards config broken");
        });
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
        // ENTITY events are gated by the same switches.
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
    }

    @Test
    void linkageErrorInStoreFactoryFailsClosedForPlayers() {
        ShadowRewardModule.setExperienceLimitStoreFactoryForTesting(level -> {
            throw new LinkageError("store class missing");
        });
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size(), "a throwing store factory fails closed");
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(1, sent.size(), "entity targets do not need the pair store");
    }

    @Test
    void linkageErrorInStoreCallsFailsClosed() {
        ShadowExperienceLimitWriter throwing = new ShadowExperienceLimitWriter() {
            @Override
            public ReservationResult tryReserve(UUID thiefId, UUID targetId, String utcDay,
                                                UUID eventId, long limit) {
                throw new LinkageError("store internals broken");
            }

            @Override
            public boolean commitReservation(UUID eventId) {
                throw new LinkageError("store internals broken");
            }

            @Override
            public boolean releaseReservation(UUID eventId) {
                throw new LinkageError("store internals broken");
            }

            @Override
            public boolean isAtPairLimit(UUID thiefId, UUID targetId, String utcDay, long limit) {
                throw new LinkageError("store internals broken");
            }
        };
        ShadowRewardModule.setExperienceLimitStoreFactoryForTesting(level -> throwing);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size(), "a throwing store fails closed (no XP)");
    }

    @Test
    void linkageErrorInActionSenderKeepsTheQuotaOccupied() {
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            throw new LinkageError("arc classes missing");
        });
        for (int i = 0; i < 100; i++) {
            bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(0, sent.size(), "100 throwing sends still grant 0 XP");
        // The first three unknown outcomes conservatively occupy the quota;
        // everything after the cap is refused.
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 3L));
        assertEquals(3, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
    }

    @Test
    void throwingDispatcherIsIsolatedByTheModule() {
        // The dispatcher itself catches RuntimeException|LinkageError and
        // returns null (clear failure → release → retryable); a sender that
        // lets the error escape is isolated by the module listener.
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            throw new LinkageError("escaped");
        });
        UUID eventId = UUID.randomUUID();
        bus.post(successEventWithId(eventId, ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1)));
        assertEquals(0, sent.size());
        // The module kept running for the next event.
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            sent.add(event);
            return ShadowSendResult.SUCCESS;
        });
        bus.post(successEvent(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(1, sent.size(), "the module survives a throwing sender");
    }

    // ---- 8E.2.2: cross-layer regression ----

    @Test
    void senderNullResultIsTreatedAsUnknown() {
        // 8E.2.2: a sender that returns null (legacy or buggy) must be
        // treated as UNKNOWN — the reservation stays occupied.
        ShadowRewardModule.setActionSenderForTesting((player, event) -> null);
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(0, sent.size());
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 1L),
                "null sender result must occupy the quota");
        assertEquals(1, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
    }

    @Test
    void sendToActionExceptionPreservesQuotaRetryDoesNotResend() {
        // 8E.2.2: when sendToAction throws (UNKNOWN), the reservation is
        // preserved. A retry with the same eventId must find
        // COMMITTED_EXISTING or RESERVED (never send twice).
        ShadowRewardModule.setActionSenderForTesting((player, event) -> ShadowSendResult.UNKNOWN);
        UUID eventId = UUID.randomUUID();
        bus.post(successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1)));
        assertEquals(0, sent.size(), "UNKNOWN → no XP sent");
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 1L),
                "UNKNOWN occupies the quota");
        // Retry with same eventId → the store returns RESERVED (same-JVM
        // retry), the sender is now set to return SUCCESS.
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            sent.add(event);
            return ShadowSendResult.SUCCESS;
        });
        bus.post(successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1)));
        assertEquals(1, sent.size(), "retry with same eventId sends once");
        // Third attempt → committed, no re-send.
        bus.post(successEventWithId(eventId, ShadowTargetKind.PLAYER,
                ShadowTheftType.ITEM, ShadowTheftReceipt.item(DIAMOND, 1)));
        assertEquals(1, sent.size(), "committed eventId never sends twice");
    }

    @Test
    void dispatcherSendToActionExceptionProducesUnknownResult() {
        // 8E.2.2: simulate the real Dispatcher behavior — sendToAction
        // throws → the module catches the exception from the sender and
        // treats it as UNKNOWN.
        ShadowRewardModule.setActionSenderForTesting((player, event) -> {
            throw new RuntimeException("sendToAction exploded");
        });
        for (int i = 0; i < 3; i++) {
            bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                    ShadowTheftReceipt.item(DIAMOND, 1), false));
        }
        assertEquals(0, sent.size());
        assertTrue(store.isAtPairLimit(thiefId, targetId, "2026-08-12", 3L));
        assertEquals(3, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
        // The quota is fully occupied — the 4th is refused.
        bus.post(successEvent(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertEquals(3, store.occupiedCountForTest(thiefId, targetId, "2026-08-12"));
    }

    // ---- action data (dispatcher) is covered by ShadowTheftSuccessActionDispatcherTest ----
}
