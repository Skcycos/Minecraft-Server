package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Unit tests for the production no-op transfer executor (8C.0 §4).
 *
 * <p>Proves that even with every development switch forced on, the executor
 * never plans, commits or rolls back anything.
 */
class NoopShadowTransferExecutorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private ShadowAttemptContext context() {
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        ServerPlayer thief = org.mockito.Mockito.mock(ServerPlayer.class);
        org.mockito.Mockito.when(thief.getUUID()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(thief.getLookAngle()).thenReturn(new Vec3(1.0d, 0.0d, 0.0d));
        org.mockito.Mockito.when(thief.position()).thenReturn(Vec3.ZERO);
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, level, BlockPos.ZERO, 1_000L, false, 2.0d, true);
    }

    @Test
    void prepareNeverPlans() {
        assertNull(NoopShadowTransferExecutor.INSTANCE.prepare(context(),
                ShadowCandidate.plain(ShadowTheftType.ITEM, 30),
                net.minecraft.util.RandomSource.create()),
                "the production executor must never produce a transfer plan");
    }

    @Test
    void commitNeverCommits() {
        ShadowTransferPlan plan = new ShadowTransferPlan.Generic(ShadowTheftType.ITEM);
        ShadowTransferResult result = NoopShadowTransferExecutor.INSTANCE.commit(context(),
                ShadowCandidate.plain(ShadowTheftType.ITEM, 30), plan);
        assertFalse(result.committed(), "no production commit may ever happen");
        assertEquals(NoopShadowTransferExecutor.REASON, result.failureReason());
        assertNull(result.receipt());
    }

    @Test
    void rollbackNeverRestores() {
        assertFalse(NoopShadowTransferExecutor.INSTANCE.rollback(context(),
                ShadowCandidate.plain(ShadowTheftType.ITEM, 30),
                new ShadowTransferPlan.Generic(ShadowTheftType.ITEM)));
    }

    @Test
    void productionWiringStillInertWithSwitchesOn() {
        // Phase-8C.0 production wiring with every switch forced ON: the
        // composite protection denies (no OPAC in a bare test JVM) before
        // any candidate probing, and even if it allowed, the no-op executor
        // refuses. No asset can move.
        ShadowFrameworkSettings enabled = new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ShadowAttemptCoordinator production = new ShadowAttemptCoordinator(
                () -> enabled,
                PlayerReadonlyCandidateProvider.INSTANCE,
                NoopShadowTransferExecutor.INSTANCE,
                new ShadowCompositeProtectionService(null, () -> 0L),
                new ShadowCooldownTracker(),
                new ShadowIdempotencyTracker(),
                level -> new ShadowAuditStore(),
                level -> new FakeDailyLimits(),net.minecraft.util.RandomSource::create,
                System::currentTimeMillis, () -> "2026-08-11");
        ShadowAttemptCoordinator.Result result = production.attempt(context());
        assertTrue(result.outcome() == ShadowTheftOutcome.PROTECTED
                        || result.outcome() == ShadowTheftOutcome.NO_CANDIDATE
                        || result.outcome() == ShadowTheftOutcome.TRANSFER_FAILED,
                "with switches on the production wiring may only yield protected/"
                        + "candidate/failure results, got " + result.outcome());
        assertTrue(result.receipt().isEmpty(), "no production result may carry a receipt");
        assertTrue(ShadowAttemptCoordinator.defaults().attempt(context()).outcome()
                        == ShadowTheftOutcome.FRAMEWORK_DISABLED,
                "the production default master switch stays OFF");
    }
}
