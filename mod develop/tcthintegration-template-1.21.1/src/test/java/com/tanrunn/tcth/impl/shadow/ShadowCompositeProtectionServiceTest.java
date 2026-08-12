package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Unit tests for {@link ShadowCompositeProtectionService} (phase 8C.0).
 *
 * <p>Covers: self, FakePlayer, gamemode, dead/unresolvable target, new-player
 * protection (verified play-time stat), spawn protection radius, null
 * position fail-closed, area-provider absent/deny/allow/exception and the
 * UNKNOWN fail-closed mapping.
 */
class ShadowCompositeProtectionServiceTest {

    private ServerLevel level;
    private MinecraftServer server;
    private ServerPlayer thief;
    private ServerPlayer victim;
    private UUID thiefId;
    private UUID victimId;
    private Supplier<Long> newPlayerTicks;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        thief = mock(ServerPlayer.class);
        victim = mock(ServerPlayer.class);
        thiefId = UUID.randomUUID();
        victimId = UUID.randomUUID();
        when(thief.getUUID()).thenReturn(thiefId);
        when(victim.getUUID()).thenReturn(victimId);
        when(level.getPlayerByUUID(victimId)).thenReturn(victim);
        when(thief.isSpectator()).thenReturn(false);
        when(thief.isCreative()).thenReturn(false);
        when(victim.isSpectator()).thenReturn(false);
        when(victim.isCreative()).thenReturn(false);
        when(victim.isDeadOrDying()).thenReturn(false);
        when(victim.hasDisconnected()).thenReturn(false);
        when(server.isUnderSpawnProtection(any(), any(), any())).thenReturn(false);
        net.minecraft.stats.ServerStatsCounter stats = mock(net.minecraft.stats.ServerStatsCounter.class);
        when(victim.getStats()).thenReturn(stats);
        when(stats.getValue(any())).thenReturn(0);
        newPlayerTicks = () -> 0L; // no new-player protection by default here
    }

    private ShadowAttemptContext context(ShadowAreaProtectionProvider area) {
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, victimId,
                null, level, new BlockPos(10, 20, 30), 1_000L, false, 2.0d, true);
    }

    private ShadowCompositeProtectionService service(ShadowAreaProtectionProvider area) {
        return new ShadowCompositeProtectionService(area, newPlayerTicks);
    }

    @Test
    void selfTargetIsDenied() {
        when(level.getPlayerByUUID(thiefId)).thenReturn(thief);
        ShadowAttemptContext ctx = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.PLAYER, thiefId, null, level, new BlockPos(1, 2, 3), 1L, false, 1.0d, true);
        assertEquals(ShadowProtectionResult.DENIED_SELF, service(null).check(ctx));
    }

    @Test
    void fakePlayerThiefIsDenied() {
        FakePlayer fake = mock(FakePlayer.class);
        when(fake.getUUID()).thenReturn(UUID.randomUUID());
        ShadowAttemptContext ctx = new ShadowAttemptContext(UUID.randomUUID(), fake,
                ShadowTargetKind.PLAYER, victimId, null, level, new BlockPos(1, 2, 3), 1L, false, 1.0d, true);
        assertEquals(ShadowProtectionResult.DENIED_GAMEMODE, service(null).check(ctx));
    }

    @Test
    void spectatorThiefIsDenied() {
        when(thief.isSpectator()).thenReturn(true);
        assertEquals(ShadowProtectionResult.DENIED_GAMEMODE, service(null).check(context(null)));
    }

    @Test
    void creativeTargetIsDenied() {
        when(victim.isCreative()).thenReturn(true);
        assertEquals(ShadowProtectionResult.DENIED_GAMEMODE, service(null).check(context(null)));
    }

    @Test
    void deadTargetIsDenied() {
        when(victim.isDeadOrDying()).thenReturn(true);
        assertEquals(ShadowProtectionResult.DENIED_TARGET, service(null).check(context(null)));
    }

    @Test
    void disconnectedTargetIsDenied() {
        when(victim.hasDisconnected()).thenReturn(true);
        assertEquals(ShadowProtectionResult.DENIED_TARGET, service(null).check(context(null)));
    }

    @Test
    void unresolvableTargetIsDenied() {
        when(level.getPlayerByUUID(victimId)).thenReturn(null);
        assertEquals(ShadowProtectionResult.DENIED_TARGET, service(null).check(context(null)));
    }

    @Test
    void newPlayerTargetIsDenied() {
        newPlayerTicks = () -> 72_000L;
        ServerStatsCounter stats = mock(ServerStatsCounter.class);
        when(victim.getStats()).thenReturn(stats);
        when(stats.getValue(any())).thenReturn(1_000); // 1000 ticks of play time
        assertEquals(ShadowProtectionResult.DENIED_NEW_PLAYER, service(null).check(context(null)));
    }

    @Test
    void establishedTargetPassesNewPlayerCheck() {
        newPlayerTicks = () -> 72_000L;
        ServerStatsCounter stats = mock(ServerStatsCounter.class);
        when(victim.getStats()).thenReturn(stats);
        when(stats.getValue(any())).thenReturn(100_000);
        assertEquals(ShadowProtectionResult.ALLOWED, service(ctx -> ShadowProtectionResult.ALLOWED).check(context(null)));
    }

    @Test
    void newPlayerTicksSupplierFailureFailsClosed() {
        newPlayerTicks = () -> {
            throw new IllegalStateException("config boom");
        };
        ServerStatsCounter stats = mock(ServerStatsCounter.class);
        when(victim.getStats()).thenReturn(stats);
        assertEquals(ShadowProtectionResult.DENIED_NEW_PLAYER, service(null).check(context(null)),
                "a config read failure must treat everyone as a new player");
    }

    @Test
    void spawnProtectionDenies() {
        when(server.isUnderSpawnProtection(any(), any(), any())).thenReturn(true);
        assertEquals(ShadowProtectionResult.DENIED_AREA, service(null).check(context(null)));
    }

    @Test
    void nullPositionFailsClosedToDeniedArea() {
        ShadowAttemptContext ctx = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.PLAYER, victimId, null, level, null, 1_000L, false, 2.0d, true);
        assertEquals(ShadowProtectionResult.DENIED_AREA, service(ctx2 -> ShadowProtectionResult.ALLOWED).check(ctx),
                "an unevaluable position must fail closed even with an allowing area provider");
    }

    @Test
    void missingAreaProviderDenies() {
        assertEquals(ShadowProtectionResult.DENIED_AREA, service(null).check(context(null)),
                "OPAC absent → area check denies");
    }

    @Test
    void areaProviderAllowAllows() {
        assertEquals(ShadowProtectionResult.ALLOWED,
                service(ctx -> ShadowProtectionResult.ALLOWED).check(context(null)));
    }

    @Test
    void areaProviderDenyDenies() {
        assertEquals(ShadowProtectionResult.DENIED_AREA,
                service(ctx -> ShadowProtectionResult.DENIED_AREA).check(context(null)));
    }

    @Test
    void areaProviderUnknownIsReturnedAsUnknown() {
        assertEquals(ShadowProtectionResult.UNKNOWN,
                service(ctx -> ShadowProtectionResult.UNKNOWN).check(context(null)),
                "UNKNOWN must propagate; the coordinator treats it as a denial");
    }

    @Test
    void areaProviderExceptionFailsClosedToUnknown() {
        ShadowAreaProtectionProvider throwing = ctx -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(ShadowProtectionResult.UNKNOWN, service(throwing).check(context(null)));
    }

    @Test
    void serviceExceptionNeverAllows() {
        // Even a hostile caller (exception inside the service) must not yield
        // ALLOWED: the try/catch maps it to UNKNOWN (denial upstream).
        when(level.getPlayerByUUID(victimId)).thenThrow(new IllegalStateException("boom"));
        assertEquals(ShadowProtectionResult.UNKNOWN, service(ctx -> ShadowProtectionResult.ALLOWED).check(context(null)));
    }
}
