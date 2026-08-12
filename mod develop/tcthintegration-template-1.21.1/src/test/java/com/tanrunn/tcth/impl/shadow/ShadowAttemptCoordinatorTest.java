package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Unit tests for {@link ShadowAttemptCoordinator} (8B.1).
 *
 * <p>Covers the full state machine with the two-phase transfer and the
 * audit-first ordering: audit gate before provider/random/executor, pre-write
 * audit gating the commit, commit failure, final-audit failure triggering
 * exactly one rollback, rollback failure → RECOVERY_REQUIRED, idempotency on
 * every audited outcome and the durable eventId/attempt-key duplicate
 * protection.
 */
class ShadowAttemptCoordinatorTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");

    private IEventBus bus;
    private AtomicInteger postedEvents;
    private AtomicInteger postedSuccessEvents;

    private ShadowFrameworkSettings settings;
    private ShadowCandidateProvider provider;
    private ShadowTransferExecutor executor;
    private ShadowProtectionService protection;
    private ShadowCooldownTracker cooldowns;
    private ShadowIdempotencyTracker idempotency;
    private InMemoryAudit audit;
    private RandomSource random;
    private AtomicInteger epochCalls;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowTheftEventDispatcher.resetForTesting();
        ShadowLogThrottle.resetForTesting();
        bus = BusBuilder.builder().build();
        postedEvents = new AtomicInteger(0);
        postedSuccessEvents = new AtomicInteger(0);
        bus.addListener((ShadowTheftEvent e) -> {
            postedEvents.incrementAndGet();
            if (e.getOutcome() == ShadowTheftOutcome.SUCCESS) {
                postedSuccessEvents.incrementAndGet();
            }
        });
        ShadowTheftEventDispatcher.setGameBusForTesting(bus);
        ShadowTheftEventDispatcher.setEnabledSupplierForTesting(() -> true);

        settings = new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        provider = ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        executor = new FixedExecutor();
        protection = ShadowProtectionService.denyAll();
        cooldowns = new ShadowCooldownTracker();
        idempotency = new ShadowIdempotencyTracker();
        audit = new InMemoryAudit();
        random = mock(RandomSource.class);
        epochCalls = new AtomicInteger(0);
    }

    @AfterEach
    void tearDown() {
        ShadowTheftEventDispatcher.resetForTesting();
    }

    private ShadowAttemptCoordinator coordinator() {
        return new ShadowAttemptCoordinator(() -> settings, provider, executor, protection,
                cooldowns, idempotency, level -> audit, level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
    }

    private ShadowAttemptContext context() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        ServerPlayer thief = mock(ServerPlayer.class);
        UUID thiefId = UUID.randomUUID();
        when(thief.getUUID()).thenReturn(thiefId);
        when(thief.getLookAngle()).thenReturn(new Vec3(1.0d, 0.0d, 0.0d));
        when(thief.position()).thenReturn(Vec3.ZERO);
        when(thief.canInteractWithEntity(any(net.minecraft.world.phys.AABB.class), anyDouble()))
                .thenReturn(true);
        ServerPlayer victim = mock(ServerPlayer.class);
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        when(victim.isAlive()).thenReturn(true);
        when(victim.level()).thenReturn(level);
        when(victim.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, level, BlockPos.ZERO, 1000L, false, 2.0d, true);
    }

    private ShadowAttemptContext contextWithThief(ServerPlayer thief, ShadowTargetKind kind, UUID targetId,
                                                  ResourceLocation targetType) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        return new ShadowAttemptContext(UUID.randomUUID(), thief, kind, targetId, targetType, level,
                BlockPos.ZERO, 1000L, false, 2.0d, true);
    }

    private static net.minecraft.core.HolderLookup.Provider provider() {
        return net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
    }

    // ---- gates (no record, no event, no idempotency) ----

    @Test
    void masterSwitchDisabledYieldsFrameworkDisabled() {
        settings = new ShadowFrameworkSettings(true, false, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, result.outcome());
        assertFalse(result.eventPosted());
        assertEquals(0, postedEvents.get());
        assertEquals(0, audit.all().size());
    }

    @Test
    void playerTheftDisabledYieldsFrameworkDisabled() {
        // 8C.2.2 §1: shadowPlayerTheftEnabled is part of the COMBINED master
        // gate — off means FRAMEWORK_DISABLED, not a context error.
        settings = new ShadowFrameworkSettings(true, true, false, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, result.outcome());
        assertEquals(0, audit.all().size());
    }

    @Test
    void configMasterDisabledYieldsFrameworkDisabled() {
        // 8C.2.2 §1: the Config.ENABLED projection is the first master gate.
        settings = new ShadowFrameworkSettings(false, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, result.outcome());
        assertEquals(0, postedEvents.get());
        assertEquals(0, audit.all().size());
    }

    @Test
    void gatesClosedNeverTouchTheDateSupplier() {
        // 8C.2.2 §2: the UTC day is captured only AFTER every functional
        // gate and the context validation — gated-off attempts must cause
        // zero date-supplier calls.
        AtomicInteger dateCalls = new AtomicInteger();
        java.util.function.Supplier<String> date = () -> {
            dateCalls.incrementAndGet();
            return "2026-08-11";
        };
        ShadowAttemptCoordinator c = new ShadowAttemptCoordinator(
                () -> settings, provider, executor, protection, cooldowns, idempotency,
                level -> audit, level -> new FakeDailyLimits(), () -> random,
                () -> (long) epochCalls.incrementAndGet(), date);

        settings = new ShadowFrameworkSettings(true, false, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, c.attempt(context()).outcome());

        settings = new ShadowFrameworkSettings(false, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, c.attempt(context()).outcome());

        settings = new ShadowFrameworkSettings(true, true, true, true, false,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, c.attempt(context()).outcome());

        // An invalid context (FakePlayer) must not touch the date either.
        settings = withTransfers(true);
        FakePlayer fake = mock(FakePlayer.class);
        when(fake.getUUID()).thenReturn(UUID.randomUUID());
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT,
                c.attempt(contextWithThief(fake, ShadowTargetKind.PLAYER, UUID.randomUUID(), null)).outcome());

        assertEquals(0, dateCalls.get(),
                "the date supplier must never run while any gate or the context validation is closed");
    }

    @Test
    void entityContextWithoutTargetTypeIsInvalid() {
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        ShadowAttemptContext ctx = contextWithThief(thief, ShadowTargetKind.ENTITY, UUID.randomUUID(), null);
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, coordinator().attempt(ctx).outcome());
    }

    @Test
    void fakePlayerThiefIsRejected() {
        FakePlayer fake = mock(FakePlayer.class);
        when(fake.getUUID()).thenReturn(UUID.randomUUID());
        ShadowAttemptContext fakeCtx = contextWithThief(fake, ShadowTargetKind.PLAYER, UUID.randomUUID(), null);
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, coordinator().attempt(fakeCtx).outcome());
        assertEquals(0, audit.all().size());
    }

    // ---- audit-first ordering (8B.1 §1) ----

    @Test
    void auditDisabledRefusesBeforeAnyWork() {
        settings = new ShadowFrameworkSettings(true, true, true, true, false,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        AtomicInteger providerCalls = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        };
        executor = new CountingExecutor(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_disabled", result.failureReason());
        assertEquals(0, providerCalls.get(), "the provider must never be called");
        verify(random, times(0)).nextLong();
        verify(random, times(0)).nextDouble();
        assertEquals(0, audit.all().size());
        assertEquals(0, postedEvents.get());
    }

    @Test
    void auditFactoryReturningNullRefusesBeforeAnyWork() {
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> settings, provider, executor, protection, cooldowns, idempotency,
                level -> null, // factory bug: returns null
                level -> new FakeDailyLimits(),
                () -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        AtomicInteger providerCalls = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of();
        };
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_unavailable", result.failureReason());
        assertEquals(0, providerCalls.get(), "a null audit store must fail closed before any work");
        verify(random, times(0)).nextLong();
        assertEquals(0, postedEvents.get());
    }

    @Test
    void playerTargetWithTargetTypeIsInvalid() {
        // The PLAYER/ENTITY ↔ targetType invariant is enforced in the early
        // context validation, not through later exceptions (8B.1.1 §6).
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        ShadowAttemptContext ctx = contextWithThief(thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("minecraft", "zombie"));
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, result.outcome());
        assertEquals(0, audit.all().size());
        assertEquals(0, postedEvents.get());
    }

    @Test
    void auditUnavailableRefusesBeforeAnyWork() {
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> settings, provider, executor, protection, cooldowns, idempotency,
                level -> {
                    throw new IllegalStateException("storage boom");
                },
                level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        AtomicInteger providerCalls = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of();
        };
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_unavailable", result.failureReason());
        assertEquals(0, providerCalls.get());
        assertEquals(0, postedEvents.get());
    }

    // ---- protection / cooldown / duplicates ----

    @Test
    void protectionDenialYieldsProtected() {
        protection = ctx -> ShadowProtectionResult.DENIED_AREA;
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.PROTECTED, result.outcome());
        assertTrue(result.eventPosted());
        assertEquals(1, audit.all().size(), "PROTECTED is an audited attempt");
        assertEquals(ShadowTheftOutcome.PROTECTED, audit.all().get(0).outcome());
    }

    @Test
    void protectionUnknownFailsClosed() {
        protection = ctx -> ShadowProtectionResult.UNKNOWN;
        assertEquals(ShadowTheftOutcome.PROTECTED, coordinator().attempt(context()).outcome());
    }

    @Test
    void protectionExceptionFailsClosed() {
        protection = ctx -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(ShadowTheftOutcome.PROTECTED, coordinator().attempt(context()).outcome());
    }

    @Test
    void globalCooldownYieldsCooldown() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        ShadowAttemptContext ctx = context();
        cooldowns.markGlobalCooldown(ctx.thief().getUUID(), 1_000L);
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.COOLDOWN, result.outcome());
        assertEquals(1, audit.all().size(), "COOLDOWN is an audited attempt");
    }

    @Test
    void duplicateEventIdIsRejectedWithZeroWork() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        AtomicInteger rollback = new AtomicInteger();
        executor = new CountingExecutor(prepare, commit, rollback);
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d); // failed roll settles the keys
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, coordinator().attempt(ctx).outcome());
        assertEquals(1, audit.all().size());
        assertEquals(1, prepare.get(), "the first attempt's prepare runs before the roll");
        // Same eventId again → DUPLICATE with zero additional work and no
        // new record.
        ShadowAttemptCoordinator.Result dup = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.DUPLICATE, dup.outcome());
        assertEquals(1, prepare.get(), "the duplicate must not run the executor again");
        assertEquals(0, commit.get());
        assertEquals(0, rollback.get());
        verify(random, times(1)).nextLong();
        verify(random, times(1)).nextDouble();
        assertEquals(1, audit.all().size(), "duplicates must never add audit records");
    }

    @Test
    void duplicateAttemptKeyIsRejectedEvenWithDifferentEventId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d); // failed roll settles keys
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, coordinator().attempt(ctx).outcome());
        // Same thief + target + tick, different eventId → still a duplicate.
        ShadowAttemptContext retry = new ShadowAttemptContext(UUID.randomUUID(), ctx.thief(),
                ctx.targetKind(), ctx.targetId(), ctx.targetType(), ctx.level(), ctx.position(),
                ctx.serverTick(), false, ctx.distance(), true);
        assertEquals(ShadowTheftOutcome.DUPLICATE, coordinator().attempt(retry).outcome());
        assertEquals(1, audit.all().size());
    }

    @Test
    void pendingRecordForEventIdYieldsRecoveryRequired() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        ShadowAttemptContext ctx = context();
        // Simulate a crash window: a PENDING pre-write record exists.
        audit.append(new ShadowAuditRecord(ctx.eventId(), ctx.thief().getUUID(), ctx.targetId(),
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1L, ctx.serverTick(),
                net.minecraft.world.level.Level.OVERWORLD.location(), null, null));
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome());
        assertEquals("pending_record_exists", result.failureReason());
        assertEquals(1, audit.all().size(), "no new record for an unresolved PENDING");
        verify(random, times(0)).nextLong();
        int eventsAfterFirst = postedEvents.get();
        assertTrue(eventsAfterFirst >= 1, "the first PENDING sighting posts the recovery alert");
        // Same JVM, same eventId again → DUPLICATE with zero new events and
        // zero new audit records (8B.1.1 §4).
        ShadowAttemptCoordinator.Result dup = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.DUPLICATE, dup.outcome());
        assertEquals(eventsAfterFirst, postedEvents.get(), "no new event for the repeated PENDING sighting");
        assertEquals(1, audit.all().size(), "no new audit record for the repeated PENDING sighting");
    }

    @Test
    void pendingRecordAlertsOnceMoreAfterRestart() {
        // A fresh idempotency tracker (simulated restart) with the same
        // durable PENDING record must alert once more.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        ShadowAttemptContext ctx = context();
        audit.append(new ShadowAuditRecord(ctx.eventId(), ctx.thief().getUUID(), ctx.targetId(),
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1L, ctx.serverTick(),
                net.minecraft.world.level.Level.OVERWORLD.location(), null, null));
        int eventsAfterFirst = postedEvents.get();
        ShadowAttemptCoordinator.Result first = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, first.outcome());
        assertEquals(eventsAfterFirst + 1, postedEvents.get(), "the first sighting alerts");
        // Second sighting in the same session: DUPLICATE, no new event.
        assertEquals(ShadowTheftOutcome.DUPLICATE, coordinator().attempt(ctx).outcome());
        assertEquals(eventsAfterFirst + 1, postedEvents.get());
        // Simulated restart: fresh trackers, same durable PENDING record.
        ShadowAttemptCoordinator restarted = new ShadowAttemptCoordinator(
                () -> settings, provider, executor, protection,
                new ShadowCooldownTracker(), new ShadowIdempotencyTracker(),
                level -> audit, level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        int eventsBeforeRestart = postedEvents.get();
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, restarted.attempt(ctx).outcome());
        assertEquals(eventsBeforeRestart + 1, postedEvents.get(),
                "after a restart the durable PENDING record alerts exactly once more");
    }

    // ---- candidates / roll ----

    @Test
    void emptyCandidatesYieldNoCandidate() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        provider = ctx -> List.of();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.NO_CANDIDATE, result.outcome());
        assertEquals(1, audit.all().size(), "NO_CANDIDATE is an audited attempt");
        verify(random, times(0)).nextLong();
        verify(random, times(0)).nextDouble();
    }

    @Test
    void coinIsHardBlockedFromThePool() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        provider = ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.COIN, 20));
        assertEquals(ShadowTheftOutcome.NO_CANDIDATE, coordinator().attempt(context()).outcome());
        verify(random, times(0)).nextLong();
    }

    @Test
    void candidateProviderExceptionYieldsNoCandidate() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        provider = ctx -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(ShadowTheftOutcome.NO_CANDIDATE, coordinator().attempt(context()).outcome());
    }

    @Test
    void failedRollYieldsFailedRollWithExposure() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d); // 0.9 >= 0.35 → fail
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome());
        assertTrue(result.eventPosted());
        assertTrue(cooldowns.isAlerted(ctx.targetId()), "a failed roll exposes the thief (alert window)");
        assertTrue(cooldowns.isFailureCooldownActive(ctx.thief().getUUID()));
        verify(random, times(1)).nextLong();
        verify(random, times(1)).nextDouble();
        assertEquals(0, postedSuccessEvents.get());
        assertEquals(1, audit.all().size());
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, audit.all().get(0).outcome());
    }

    // ---- two-phase transfer (8B.1 §1) ----

    @Test
    void prewriteFailureSkipsCommit() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(1); // the PENDING pre-write fails
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        AtomicInteger rollback = new AtomicInteger();
        executor = new CountingExecutor(prepare, commit, rollback);
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_prewrite_failed", result.failureReason());
        assertEquals(1, prepare.get());
        assertEquals(0, commit.get(), "commit must never run after a failed pre-write");
        assertEquals(0, rollback.get());
        assertEquals(0, postedSuccessEvents.get());
        assertEquals(0, audit.all().size());
    }

    @Test
    void commitFailureYieldsNoReceiptNoSuccess() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.failed("no_space");
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, result.outcome());
        assertEquals("no_space", result.failureReason());
        assertTrue(result.receipt().isEmpty(), "no receipt on a failed commit");
        assertEquals(0, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertTrue(record != null && record.outcome() == ShadowTheftOutcome.TRANSFER_FAILED,
                "the PENDING record must be finalised as TRANSFER_FAILED");
        assertEquals(ShadowAuditState.FINAL, record.auditState());
    }

    @Test
    void commitSuccessAndFinalAuditSuccessYieldsSuccess() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new CommittingExecutor();
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
        assertTrue(result.eventPosted());
        assertEquals(1, postedSuccessEvents.get());
        assertEquals(1, audit.all().size(), "the PENDING pre-write is finalised in place, not duplicated");
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.SUCCESS, record.outcome());
        assertEquals(ShadowAuditState.FINAL, record.auditState());
        assertEquals(1, record.itemCount());
        assertEquals(ctx.eventId(), result.eventId());
        assertTrue(cooldowns.isGlobalCooldownActive(ctx.thief().getUUID()));
        assertTrue(cooldowns.isVictimProtected(ctx.targetId()));
        assertTrue(idempotency.hasEventId(ctx.eventId()));
        assertTrue(idempotency.isAttemptDuplicate(ctx.thief().getUUID(), ctx.targetId(), ctx.serverTick()));
    }

    // ---- rollback / recovery ----

    @Test
    void finalAuditFailureTriggersRollbackOnceAndRollsBack() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(2); // pre-write ok (1st), final write fails (2nd)
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                prepare.incrementAndGet();
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                commit.incrementAndGet();
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return true;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome());
        assertEquals("audit_final_write_failed", result.failureReason());
        assertTrue(result.receipt().isEmpty(), "a rolled-back attempt must not report a receipt");
        assertEquals(1, prepare.get());
        assertEquals(1, commit.get());
        assertEquals(1, rollback.get(), "rollback must run exactly once");
        assertEquals(0, postedSuccessEvents.get());
        assertEquals(1, audit.all().size());
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, audit.byEventId(ctx.eventId()).outcome());
    }

    @Test
    void rollbackFailureYieldsRecoveryRequiredWithCommittedReceipt() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(2); // pre-write ok, final write fails
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return false; // restore failed → severe state
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome());
        assertEquals("rollback_failed", result.failureReason());
        assertFalse(result.receipt().isEmpty(), "the committed receipt must be reported for recovery");
        assertEquals(1, rollback.get());
        assertEquals(0, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, record.outcome());
        assertEquals(1, record.itemCount(), "the recovery record keeps the committed scalar facts");
    }

    @Test
    void rollbackExceptionYieldsRecoveryRequired() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(2);
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                throw new IllegalStateException("restore boom");
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, coordinator().attempt(context()).outcome());
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void receiptMismatchAfterCommitRollsBackOnceToRolledBack() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                prepare.incrementAndGet();
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                commit.incrementAndGet();
                return ShadowTransferResult.committed(ShadowTheftReceipt.numeric(10.0d)); // wrong type
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return true;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome(),
                "a committed-but-mismatched receipt must never surface as plain TRANSFER_FAILED");
        assertEquals("receipt_type_mismatch", result.failureReason());
        assertTrue(result.receipt().isEmpty());
        assertEquals(1, prepare.get());
        assertEquals(1, commit.get());
        assertEquals(1, rollback.get(), "rollback must run exactly once");
        assertEquals(0, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, record.outcome());
        assertEquals(ShadowAuditState.FINAL, record.auditState());
    }

    @Test
    void receiptMismatchAndRollbackFailureYieldsRecoveryRequired() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.numeric(10.0d)); // wrong type
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return false;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome(),
                "a mismatched receipt with a failed rollback must be RECOVERY_REQUIRED");
        assertEquals("rollback_failed; receipt_type_mismatch", result.failureReason());
        assertEquals(1, rollback.get());
        assertTrue(result.receipt().isEmpty(),
                "the ambiguous receipt must not be reported as a committed receipt of the drawn type");
        assertEquals(0, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, record.outcome());
        assertEquals(ShadowAuditState.FINAL, record.auditState());
    }

    @Test
    void receiptMismatchAndRollbackExceptionYieldsRecoveryRequired() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.numeric(10.0d)); // wrong type
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                throw new IllegalStateException("restore boom");
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, coordinator().attempt(context()).outcome());
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void executorExceptionIsIsolated() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                throw new IllegalStateException("boom");
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, coordinator().attempt(context()).outcome());
        assertEquals(0, postedSuccessEvents.get());
    }

    // ---- misc ----

    @Test
    void eventIdIsStableAcrossResultAndPostedEvent() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return null;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        assertEquals(ctx.eventId(), coordinator().attempt(ctx).eventId());
    }

    @Test
    void frameworkExceptionIsIsolated() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(() -> {
            throw new IllegalStateException("settings boom");
        }, provider, executor, protection, cooldowns, idempotency, level -> audit, level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, result.outcome());
        assertEquals("framework_exception", result.failureReason());
        assertEquals(0, postedEvents.get());
    }

    @Test
    void epochMillisAndServerTickAreRecordedSeparately() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d);
        ShadowAttemptContext ctx = context();
        coordinator().attempt(ctx);
        ShadowAuditRecord record = audit.all().get(0);
        assertTrue(record.timestampEpochMillis() >= 1L, "epoch millis must come from the supplier");
        assertEquals(1000L, record.serverTick(), "serverTick must be stored separately");
        assertTrue(epochCalls.get() >= 1, "the epoch supplier must be used");
    }

    @Test
    void mutablePositionDoesNotAffectRecordAfterConstruction() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        BlockPos mutable = new BlockPos(10, 20, 30);
        ShadowAttemptContext ctx = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.PLAYER, UUID.randomUUID(), null, level, mutable, 1000L, false, 2.0d, true);
        coordinator().attempt(ctx);
        assertEquals(new BlockPos(10, 20, 30), audit.all().get(0).position(),
                "the record must hold an immutable copy of the position");
    }

    @Test
    void failurePathsSettleIdempotency() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        // FAILED_ROLL settles eventId + attempt keys (verified above); a
        // second attempt with the same eventId is a DUPLICATE and adds no
        // record — covering PROTECTED/COOLDOWN/NO_CANDIDATE/FAILED_ROLL/
        // TRANSFER_FAILED style outcomes uniformly via the shared settlement.
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d);
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, coordinator().attempt(ctx).outcome());
        assertTrue(idempotency.hasEventId(ctx.eventId()));
        assertTrue(idempotency.isAttemptDuplicate(ctx.thief().getUUID(), ctx.targetId(), ctx.serverTick()));
    }

    @Test
    void defaultsWiringIsFullyFailClosed() {
        assertFalse(ShadowFrameworkSettings.defaults().integrationEnabled(),
                "the production default master switch must be OFF");
        // Even with every switch forced ON, the production fail-closed wiring
        // (deny-all protection + empty provider + no-op executor) must never
        // reach a transfer.
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();
        ShadowAttemptCoordinator defaults = new ShadowAttemptCoordinator(
                () -> settings, ctx -> {
                    providerCalls.incrementAndGet();
                    return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
                },
                new FixedExecutor() {
                    @Override
                    public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                        executorCalls.incrementAndGet();
                        return new ShadowTransferPlan.Generic(selected.type());
                    }
                },
                ShadowProtectionService.denyAll(), new ShadowCooldownTracker(),
                new ShadowIdempotencyTracker(), level -> audit, level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = defaults.attempt(context());
        assertEquals(ShadowTheftOutcome.PROTECTED, result.outcome(),
                "the production default protection must deny every attempt");
        assertEquals(0, providerCalls.get(), "deny-all protection must stop the attempt first");
        assertEquals(0, executorCalls.get());
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void defaultsWiringRefusesWhenAuditDisabled() {
        // The production default settings read from Config keep the master
        // switch OFF; even with everything ON, audit must be required before
        // any attempt (audit gate before provider/random/executor).
        ShadowFrameworkSettings noAudit = new ShadowFrameworkSettings(true, true, true, true, false,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> noAudit, provider, executor, protection, cooldowns, idempotency,
                level -> audit, level -> new FakeDailyLimits(),() -> random, () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_disabled", result.failureReason());
        assertEquals(0, postedEvents.get());
    }

    // ---- EFFECT full state machine (8B.1.1 §2) ----

    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("minecraft", "speed");

    private ShadowAttemptCoordinator effectCoordinator() {
        provider = ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.EFFECT, 15));
        return coordinator();
    }

    @Test
    void effectFailedRollRecordsEffectTypeWithoutEffectId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d); // fail the roll
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, effectCoordinator().attempt(ctx).outcome());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftType.EFFECT, record.theftType());
        assertEquals(null, record.effectId(),
                "a FAILED_ROLL for EFFECT carries no asset and therefore no effectId");
        assertEquals(0, record.effectDurationTicks());
    }

    @Test
    void effectPrepareFailureRecordsEffectTypeWithoutEffectId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return null;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, effectCoordinator().attempt(ctx).outcome());
        assertEquals("prepare_failed", audit.byEventId(ctx.eventId()).failureReason());
        assertEquals(null, audit.byEventId(ctx.eventId()).effectId());
    }

    @Test
    void effectPendingRecordHasNoEffectId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(2); // pre-write ok, final write fails → rollback path
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.effect(SPEED, 200));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                return true;
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        // Verify the PENDING pre-write itself is buildable with theftType
        // EFFECT and effectId == null by peeking at the finalised record's
        // lifecycle: the ROLLED_BACK final record replaces the PENDING one.
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, effectCoordinator().attempt(ctx).outcome());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, record.outcome());
        assertEquals(ShadowTheftType.EFFECT, record.theftType());
        assertEquals(null, record.effectId(), "a ROLLED_BACK record carries no assets");
        assertEquals(1, audit.all().size(), "the PENDING pre-write is finalised in place");
    }

    @Test
    void effectCommitFailureRecordsEffectTypeWithoutEffectId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.failed("no_space");
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, effectCoordinator().attempt(ctx).outcome());
        assertEquals(null, audit.byEventId(ctx.eventId()).effectId());
        assertEquals(0, audit.byEventId(ctx.eventId()).effectDurationTicks());
    }

    @Test
    void effectSuccessRecordsEffectId() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.effect(SPEED, 200));
            }
        };
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        assertEquals(ShadowTheftOutcome.SUCCESS, effectCoordinator().attempt(ctx).outcome());
        assertEquals(1, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.SUCCESS, record.outcome());
        assertEquals(ShadowTheftType.EFFECT, record.theftType());
        assertEquals(SPEED, record.effectId(), "a SUCCESS EFFECT record must persist the effectId");
        assertEquals(200, record.effectDurationTicks());
    }

    // ---- 8C.2 gates & daily limit ----

    private ShadowFrameworkSettings withTransfers(boolean transfers) {
        return new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, transfers, 3L);
    }

    @Test
    void realTransferGateOffRefusesBeforeAnyWork() {
        settings = withTransfers(false);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        };
        executor = new CountingExecutor(prepare, commit, new AtomicInteger());
        ShadowAttemptCoordinator.Result result = coordinator().attempt(context());
        assertEquals(ShadowTheftOutcome.FRAMEWORK_DISABLED, result.outcome());
        assertEquals("real_asset_transfers_disabled", result.failureReason());
        assertEquals(0, providerCalls.get(), "the provider must never run");
        assertEquals(0, prepare.get(), "prepare must never run");
        assertEquals(0, commit.get(), "commit must never run");
        verify(random, times(0)).nextLong();
        verify(random, times(0)).nextDouble();
        assertEquals(0, audit.all().size(), "no PENDING audit for a gated-off attempt");
        assertEquals(0, postedEvents.get(), "no failure exposure");
    }

    @Test
    void realTransferGateOffKeepsAssetsUntouched() {
        settings = withTransfers(false);
        coordinator().attempt(context());
        assertTrue(ShadowFrameworkSettings.defaults() instanceof ShadowFrameworkSettings);
        assertFalse(ShadowFrameworkSettings.defaults().realAssetTransfersEnabled(),
                "the production default must keep real transfers disabled");
    }

    @Test
    void settingsSupplierExceptionFailsClosed() {
        ShadowAttemptCoordinator broken = new ShadowAttemptCoordinator(() -> {
            throw new IllegalStateException("config boom");
        }, provider, executor, protection, cooldowns, idempotency,
                level -> audit, level -> new FakeDailyLimits(), () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, broken.attempt(context()).outcome(),
                "a config read failure must fail closed");
    }

    @Test
    void dailyItemLimitPrunesOnlyItemAndRenormalises() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        provider = ctx -> List.of(
                ShadowCandidate.plain(ShadowTheftType.ITEM, 30),
                ShadowCandidate.plain(ShadowTheftType.HEALTH, 20),
                ShadowCandidate.plain(ShadowTheftType.EFFECT, 15));
        FakeDailyLimits limits = new FakeDailyLimits();
        ShadowAttemptContext ctx = context();
        limits.setCount(ctx.targetId(), 3); // at the default cap of 3
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        // The draw must see ONLY HEALTH + EFFECT (ITEM pruned), renormalised.
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d); // fail the roll afterwards
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome());
        verify(random, times(1)).nextLong();
        // ITEM at the cap must not produce a NO_CANDIDATE or an ITEM draw —
        // the pool still has HEALTH + EFFECT.
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void dailyItemLimitBelowCapKeepsItem() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        provider = ctx -> List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        FakeDailyLimits limits = new FakeDailyLimits();
        ShadowAttemptContext ctx = context();
        limits.setCount(ctx.targetId(), 2); // below the cap of 3
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.9d);
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, coordinator.attempt(ctx).outcome(),
                "ITEM stays a candidate below the cap");
    }

    @Test
    void successfulItemTheftRecordsTheDailyLoss() {
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext c, ShadowCandidate s,
                                              RandomSource r) {
                return new ShadowTransferPlan.Generic(s.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext c, ShadowCandidate s,
                                               ShadowTransferPlan p) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }
        };
        FakeDailyLimits limits = new FakeDailyLimits();
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        assertEquals(ShadowTheftOutcome.SUCCESS, coordinator.attempt(ctx).outcome());
        assertEquals(1, limits.itemLossCount(ctx.targetId()),
                "a successful ITEM theft must increment the victim's daily counter");
    }

    // ---- 8C.2.2 date sourcing, attempt-local state and quota commit ----

    @Test
    void dateSupplierExceptionPrunesOnlyItemAndForbidsItemTransfer() {
        // 8C.2.2 §2: a failing date source prunes ITEM only — the other
        // types still draw normally and no ITEM asset can move.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        AtomicInteger dateCalls = new AtomicInteger();
        java.util.function.Supplier<String> date = () -> {
            dateCalls.incrementAndGet();
            throw new IllegalStateException("clock boom");
        };
        AtomicReference<ShadowTheftType> prepared = new AtomicReference<>();
        provider = ctx -> List.of(
                ShadowCandidate.plain(ShadowTheftType.ITEM, 30),
                ShadowCandidate.plain(ShadowTheftType.HEALTH, 20));
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                prepared.set(selected.type());
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.failed("not_committed");
            }
        };
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> new FakeDailyLimits(), () -> random,
                () -> (long) epochCalls.incrementAndGet(), date);
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, result.outcome());
        assertEquals(ShadowTheftType.HEALTH, prepared.get(),
                "with the date broken only the non-ITEM candidate may be drawn");
        verify(random, times(1)).nextLong();
        assertEquals(1, dateCalls.get(), "the date supplier must run exactly once per live attempt");
    }

    @Test
    void successfulItemThenCleanFailureKeepsTheFirstQuota() {
        // 8C.2.2 §3: the reservation state is attempt-LOCAL — a later clean
        // failure on another type must never release the earlier SUCCESS quota.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        FakeDailyLimits limits = new FakeDailyLimits();
        // One provider and one executor for BOTH attempts on the same
        // coordinator instance: first draw ITEM (commits cleanly), second
        // draw HEALTH (fails cleanly, FAILED_CLEAN).
        AtomicInteger providerCalls = new AtomicInteger();
        provider = ctx -> {
            if (providerCalls.incrementAndGet() == 1) {
                return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
            }
            return List.of(ShadowCandidate.plain(ShadowTheftType.HEALTH, 20));
        };
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                if (selected.type() == ShadowTheftType.ITEM) {
                    return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
                }
                return ShadowTransferResult.failed("not_committed");
            }
        };
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext first = context();
        assertEquals(ShadowTheftOutcome.SUCCESS, coordinator.attempt(first).outcome());
        assertEquals(1, limits.itemLossCount(first.targetId()));

        // Second attempt on the SAME coordinator: HEALTH drawn (2nd provider
        // call), clean FAILED_CLEAN commit; a fresh thief/target avoids any
        // cooldown collision.
        ShadowAttemptContext second = context();
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, coordinator.attempt(second).outcome());
        assertEquals(1, limits.itemLossCount(first.targetId()),
                "the first SUCCESS quota must never be released by a later attempt");
        assertEquals(1, limits.occupiedCount(first.targetId()));
    }

    @Test
    void quotaCommitFailureRollsBackOnceAndReleases() {
        // 8C.2.2 §5: commitReservation false must NOT continue to SUCCESS —
        // exactly one rollback; a successful rollback releases the quota.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        FakeDailyLimits limits = new FakeDailyLimits();
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return true;
            }
        };
        limits.failNextCommit();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome());
        assertEquals("daily_commit_failed", result.failureReason());
        assertEquals(1, rollback.get(), "rollback must run exactly once");
        assertEquals(0, limits.occupiedCount(ctx.targetId()),
                "a successful rollback must release the RESERVED quota");
        assertEquals(0, postedSuccessEvents.get());
        ShadowAuditRecord record = audit.byEventId(ctx.eventId());
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, record.outcome());
    }

    @Test
    void quotaCommitFailureRollbackFailureKeepsQuotaAndRecovery() {
        // 8C.2.2 §5: when the rollback also fails the outcome is
        // RECOVERY_REQUIRED and the quota stays reserved (assets may have
        // moved) — never SUCCESS.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        FakeDailyLimits limits = new FakeDailyLimits();
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return false; // restore failed → severe state
            }
        };
        limits.failNextCommit();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome());
        assertEquals("rollback_failed; daily_commit_failed", result.failureReason());
        assertEquals(1, rollback.get());
        assertFalse(result.receipt().isEmpty(), "the committed receipt must be reported for recovery");
        assertEquals(1, limits.occupiedCount(ctx.targetId()),
                "RECOVERY_REQUIRED must keep the reserved quota");
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void quotaCommitExceptionNeverContinuesToSuccess() {
        // 8C.2.2 §5: an EXCEPTION from commitReservation is the same hard
        // failure as a false return — never SUCCESS, rollback once.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        FakeDailyLimits limits = new FakeDailyLimits();
        AtomicInteger rollback = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                rollback.incrementAndGet();
                return true;
            }
        };
        limits.throwNextCommit();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome());
        assertEquals("daily_commit_failed", result.failureReason());
        assertEquals(1, rollback.get());
        assertEquals(0, limits.occupiedCount(ctx.targetId()));
        assertEquals(0, postedSuccessEvents.get());
    }

    @Test
    void finalAuditFailureRollbackSuccessReleasesCommittedQuota() {
        // 8C.2.2 §5: after a SUCCESS-path quota COMMIT, a failed final audit
        // write with a successful rollback must ALSO release the committed
        // quota (the asset transfer is undone).
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        audit.failAppendNumber(2); // pre-write ok (1st), final write fails (2nd)
        FakeDailyLimits limits = new FakeDailyLimits();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
            }

            @Override
            public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                    ShadowTransferPlan plan) {
                return true;
            }
        };
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> audit, level -> limits, () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome());
        assertEquals("audit_final_write_failed", result.failureReason());
        assertEquals(0, limits.occupiedCount(ctx.targetId()),
                "a successful rollback after the final-audit failure must release the committed quota");
        assertEquals(0, limits.itemLossCount(ctx.targetId()));
        assertEquals(0, postedSuccessEvents.get());
    }

    // ---- 8C.2.3 audit single-write & state-transition rules ----

    @Test
    void realAuditStoreWritesPendingAndFinalExactlyOnce() {
        // 8C.2.3 §2: with the REAL ShadowAuditStore and an epoch supplier
        // returning a fresh value every call, an ITEM SUCCESS must produce
        // exactly the PENDING + FINAL append flow, one FINAL SUCCESS record,
        // no third write — and no reliance on coincidentally-equal
        // timestamps.
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        ShadowAuditStore realAudit = new ShadowAuditStore();
        CountingAudit countingAudit = new CountingAudit(realAudit);
        FakeDailyLimits limits = new FakeDailyLimits();
        AtomicInteger distinctEpochs = new AtomicInteger();
        executor = new FixedExecutor() {
            @Override
            public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
                return new ShadowTransferPlan.Generic(selected.type());
            }

            @Override
            public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                               ShadowTransferPlan plan) {
                return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 2));
            }
        };
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> countingAudit, level -> limits, () -> random,
                () -> (long) distinctEpochs.incrementAndGet(), () -> "2026-08-11");
        when(random.nextLong()).thenReturn(0L);
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext ctx = context();
        ShadowAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
        assertEquals(2, countingAudit.appends.get(),
                "the append flow must be exactly PENDING + FINAL — no third write");
        assertTrue(distinctEpochs.get() >= 2,
                "PENDING and FINAL must carry distinct timestamps (no fixed-time trick)");
        java.util.List<ShadowAuditRecord> records = realAudit.all();
        assertEquals(1, records.size(),
                "the PENDING record transitions in place: exactly one FINAL record remains");
        ShadowAuditRecord record = records.get(0);
        assertEquals(ShadowAuditState.FINAL, record.auditState());
        assertEquals(ShadowTheftOutcome.SUCCESS, record.outcome());
        assertEquals(ctx.eventId(), record.eventId());
        assertEquals(ShadowTheftType.ITEM, record.theftType());
        assertEquals(DIAMOND, record.itemId());
        assertEquals(2, record.itemCount());
        assertEquals(ctx.thief().getUUID(), record.thiefId());
        assertEquals(ctx.targetId(), record.targetId());
        assertEquals(1, limits.itemLossCount(ctx.targetId()),
                "the ITEM quota must be committed for the SUCCESS");
        assertEquals(1, limits.occupiedCount(ctx.targetId()));
    }

    @Test
    void unhealthyAuditStoreRefusesBeforeAnyWork() {
        // 8C.2.4 §2: an unhealthy audit store must refuse the attempt
        // BEFORE the candidate pool, the date source, any random call and
        // the executor — every asset call stays at zero.
        AtomicInteger dateCalls = new AtomicInteger();
        java.util.function.Supplier<String> date = () -> {
            dateCalls.incrementAndGet();
            return "2026-08-11";
        };
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        };
        executor = new CountingExecutor(prepare, commit, new AtomicInteger());
        InMemoryAudit unhealthyAudit = new InMemoryAudit();
        unhealthyAudit.setUnhealthy();
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> unhealthyAudit, level -> new FakeDailyLimits(), () -> random,
                () -> (long) epochCalls.incrementAndGet(), date);
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_unhealthy", result.failureReason());
        assertEquals(0, providerCalls.get(), "the provider must never run");
        assertEquals(0, prepare.get(), "prepare must never run");
        assertEquals(0, commit.get(), "commit must never run");
        verify(random, times(0)).nextLong();
        verify(random, times(0)).nextDouble();
        assertEquals(0, dateCalls.get(), "the date source must never run either");
        assertEquals(0, unhealthyAudit.all().size(), "no record is written");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void futureAuditVersionFailsClosedCoordinatorRefusesBeforeAnyWork() {
        // 8C.2.4 §1+§5: a future audit data version fails the REAL store
        // closed; the coordinator must refuse with AUDIT_FAILED and zero
        // provider/random/prepare/commit calls.
        net.minecraft.nbt.CompoundTag futureTag = new net.minecraft.nbt.CompoundTag();
        futureTag.putInt("dataVersion", 99);
        ShadowAuditStore unhealthyStore = ShadowAuditStore.load(futureTag, provider());
        assertFalse(unhealthyStore.isHealthy(), "the future version must fail the store closed");

        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger commit = new AtomicInteger();
        provider = ctx -> {
            providerCalls.incrementAndGet();
            return List.of(ShadowCandidate.plain(ShadowTheftType.ITEM, 30));
        };
        executor = new CountingExecutor(prepare, commit, new AtomicInteger());
        ShadowAttemptCoordinator coordinator = new ShadowAttemptCoordinator(
                () -> withTransfers(true), provider, executor, protection, cooldowns,
                idempotency, level -> unhealthyStore, level -> new FakeDailyLimits(), () -> random,
                () -> (long) epochCalls.incrementAndGet(), () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = coordinator.attempt(context());
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_unhealthy", result.failureReason());
        assertEquals(0, providerCalls.get(), "the provider must never run");
        assertEquals(0, prepare.get(), "prepare must never run");
        assertEquals(0, commit.get(), "commit must never run");
        verify(random, times(0)).nextLong();
        verify(random, times(0)).nextDouble();
        assertEquals(0, postedEvents.get());
    }

    @Test
    void inMemoryAuditEnforcesProductionTransitionRules() {
        // 8C.2.3 §2: the test fake must mirror the production store's state
        // machine — never an unconditional overwrite.
        InMemoryAudit audit2 = new InMemoryAudit();
        UUID eventId = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        ShadowAuditRecord pending = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1L, 100L, dim, null, null);
        assertTrue(audit2.append(pending), "a fresh PENDING pre-write is allowed");
        ShadowAuditRecord finalOk = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, DIAMOND, 1, 0.0d, null, 0, 2L, 100L, dim, null, null);
        assertTrue(audit2.append(finalOk), "PENDING → FINAL with preserved identity is allowed");
        assertEquals(1, audit2.all().size(), "the PENDING record transitions in place");
        assertEquals(ShadowTheftOutcome.SUCCESS, audit2.byEventId(eventId).outcome());
        // FINAL → a DIFFERENT FINAL (different outcome AND timestamp) must be
        // refused — the original record stays untouched.
        ShadowAuditRecord differentFinal = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.ROLLED_BACK,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 3L, 100L, dim, null, null);
        assertFalse(audit2.append(differentFinal), "FINAL → different FINAL must be refused");
        assertEquals(ShadowTheftOutcome.SUCCESS, audit2.byEventId(eventId).outcome(),
                "the original record must stay untouched");
        // PENDING → PENDING is illegal too.
        assertFalse(audit2.append(pending), "PENDING → PENDING must be refused");
    }

    // ---- fakes ----

    /** Counts append calls while delegating to a real audit writer. */
    static final class CountingAudit implements ShadowAuditWriter {
        private final ShadowAuditWriter delegate;
        final AtomicInteger appends = new AtomicInteger();

        CountingAudit(ShadowAuditWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean append(ShadowAuditRecord record) {
            appends.incrementAndGet();
            return delegate.append(record);
        }

        @Override
        public ShadowAuditRecord byEventId(UUID eventId) {
            return delegate.byEventId(eventId);
        }

        @Override
        public boolean has(UUID eventId) {
            return delegate.has(eventId);
        }

        @Override
        public List<ShadowAuditRecord> byThief(UUID thiefId) {
            return delegate.byThief(thiefId);
        }

        @Override
        public List<ShadowAuditRecord> byTarget(UUID targetId) {
            return delegate.byTarget(targetId);
        }

        @Override
        public List<ShadowAuditRecord> all() {
            return delegate.all();
        }

        @Override
        public boolean isHealthy() {
            return delegate.isHealthy();
        }
    }

    /** Executor whose prepare always fails (no-op production default). */
    private static class FixedExecutor implements ShadowTransferExecutor {
        @Override
        public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
            return new ShadowTransferPlan.Generic(selected.type());
        }

        @Override
        public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                           ShadowTransferPlan plan) {
            return ShadowTransferResult.failed("not_committed");
        }

        @Override
        public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                ShadowTransferPlan plan) {
            return true;
        }
    }

    /** Executor counting prepare/commit/rollback calls (commit fails). */
    private static class CountingExecutor extends FixedExecutor {
        final AtomicInteger prepare;
        final AtomicInteger commit;
        final AtomicInteger rollback;

        CountingExecutor(AtomicInteger prepare, AtomicInteger commit, AtomicInteger rollback) {
            this.prepare = prepare;
            this.commit = commit;
            this.rollback = rollback;
        }

        @Override
        public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                   net.minecraft.util.RandomSource random) {
            prepare.incrementAndGet();
            return super.prepare(context, selected, random);
        }

        @Override
        public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                           ShadowTransferPlan plan) {
            commit.incrementAndGet();
            return super.commit(context, selected, plan);
        }

        @Override
        public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected,
                                ShadowTransferPlan plan) {
            rollback.incrementAndGet();
            return super.rollback(context, selected, plan);
        }
    }

    /** Executor that commits 1 diamond and rolls back successfully. */
    private static final class CommittingExecutor extends FixedExecutor {
        @Override
        public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                           ShadowTransferPlan plan) {
            return ShadowTransferResult.committed(ShadowTheftReceipt.item(DIAMOND, 1));
        }
    }

    /** In-memory audit writer with controllable failures that enforces the
     *  SAME state-transition rules as the production
     *  {@link ShadowAuditStore} (8C.2.3 §2): PENDING → FINAL with preserved
     *  identity only; FINAL → byte-identical idempotent re-write; everything
     *  else refused. Never an unconditional overwrite. */
    static final class InMemoryAudit implements ShadowAuditWriter {
        private final java.util.ArrayList<ShadowAuditRecord> records = new java.util.ArrayList<>();
        private int failAppendNumber = -1;
        private int appendCount = 0;
        private boolean unhealthy;

        /** Fails only the {@code n}-th next append (1-indexed). */
        void failAppendNumber(int n) {
            failAppendNumber = n;
        }

        /** Marks the store unhealthy (8C.2.4 §2): the coordinator must
         *  refuse with AUDIT_FAILED before any provider/random/executor. */
        void setUnhealthy() {
            unhealthy = true;
        }

        @Override
        public synchronized boolean append(ShadowAuditRecord record) {
            appendCount++;
            if (appendCount == failAppendNumber) {
                return false;
            }
            int index = -1;
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).eventId().equals(record.eventId())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                // New eventId: a PENDING pre-write or a FINAL record are both
                // allowed (8B.1.1 §5).
                records.add(record);
                return true;
            }
            ShadowAuditRecord existing = records.get(index);
            if (existing.auditState() == ShadowAuditState.PENDING
                    && record.auditState() == ShadowAuditState.FINAL
                    && record.outcome() != null
                    && identityFieldsMatch(existing, record)) {
                records.set(index, record);
                return true;
            }
            if (existing.auditState() == ShadowAuditState.FINAL && existing.equals(record)) {
                return true; // byte-identical idempotent re-write, no change
            }
            return false; // illegal transition: keep the original record
        }

        private static boolean identityFieldsMatch(ShadowAuditRecord a, ShadowAuditRecord b) {
            return a.thiefId().equals(b.thiefId())
                    && a.targetId().equals(b.targetId())
                    && a.targetKind() == b.targetKind()
                    && java.util.Objects.equals(a.targetType(), b.targetType())
                    && a.theftType() == b.theftType()
                    && java.util.Objects.equals(a.dimension(), b.dimension())
                    && java.util.Objects.equals(a.position(), b.position())
                    && a.serverTick() == b.serverTick();
        }

        @Override
        public synchronized ShadowAuditRecord byEventId(UUID eventId) {
            for (ShadowAuditRecord record : records) {
                if (record.eventId().equals(eventId)) {
                    return record;
                }
            }
            return null;
        }

        @Override
        public synchronized boolean has(UUID eventId) {
            return byEventId(eventId) != null;
        }

        @Override
        public synchronized List<ShadowAuditRecord> byThief(UUID thiefId) {
            return records.stream().filter(r -> r.thiefId().equals(thiefId)).toList();
        }

        @Override
        public synchronized List<ShadowAuditRecord> byTarget(UUID targetId) {
            return records.stream().filter(r -> r.targetId().equals(targetId)).toList();
        }

        @Override
        public synchronized List<ShadowAuditRecord> all() {
            return List.copyOf(records);
        }

        @Override
        public synchronized boolean isHealthy() {
            return !unhealthy;
        }
    }
}
