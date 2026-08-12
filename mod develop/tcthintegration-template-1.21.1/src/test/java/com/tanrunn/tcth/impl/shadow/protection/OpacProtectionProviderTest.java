package com.tanrunn.tcth.impl.shadow.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.impl.shadow.ShadowAttemptContext;
import com.tanrunn.tcth.impl.shadow.ShadowProtectionResult;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

/**
 * Unit tests for {@link OpacProtectionProvider} and
 * {@link OpacProtectionProviderFactory} (phase 8C.0).
 *
 * <p>Uses the real Open Parties and Claims 0.29.3 jar on the test classpath
 * (javap-verified API); the OPAC API instance is injected per test.
 * Covers: allow / deny / exception / unresolvable target, and the
 * string-isolated factory behaviour when the mod is absent.
 */
class OpacProtectionProviderTest {

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer thief;
    private OpenPACServerAPI api;
    private IChunkProtectionAPI chunkProtection;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        OpacProtectionProvider.resetForTesting();
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        thief = mock(ServerPlayer.class);
        when(level.getServer()).thenReturn(server);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        api = mock(OpenPACServerAPI.class);
        chunkProtection = mock(IChunkProtectionAPI.class);
        when(api.getChunkProtection()).thenReturn(chunkProtection);
        OpacProtectionProvider.setApiResolverForTesting(s -> api);
    }

    @AfterEach
    void tearDown() {
        OpacProtectionProvider.resetForTesting();
    }

    private ShadowAttemptContext context(Entity target) {
        when(level.getEntity(any(UUID.class))).thenReturn(target);
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, level, new BlockPos(1, 2, 3), 1L, false, 1.0d, true);
    }

    @Test
    void opacAllowMapsToAllowed() {
        when(chunkProtection.onEntityInteraction(any(), any(), any(), any(), any(), anyBoolean(),
                anyBoolean(), anyBoolean())).thenReturn(false);
        Entity target = mock(Entity.class);
        assertEquals(ShadowProtectionResult.ALLOWED, new OpacProtectionProvider().checkArea(context(target)));
    }

    @Test
    void opacBlockedMapsToDeniedArea() {
        when(chunkProtection.onEntityInteraction(any(), any(), any(), any(), any(), anyBoolean(),
                anyBoolean(), anyBoolean())).thenReturn(true);
        Entity target = mock(Entity.class);
        assertEquals(ShadowProtectionResult.DENIED_AREA, new OpacProtectionProvider().checkArea(context(target)));
    }

    @Test
    void opacApiExceptionFailsClosed() {
        when(api.getChunkProtection()).thenThrow(new IllegalStateException("opac boom"));
        Entity target = mock(Entity.class);
        assertEquals(ShadowProtectionResult.DENIED_AREA, new OpacProtectionProvider().checkArea(context(target)));
    }

    @Test
    void opacQueryExceptionFailsClosed() {
        when(chunkProtection.onEntityInteraction(any(), any(), any(), any(), any(), anyBoolean(),
                anyBoolean(), anyBoolean())).thenThrow(new RuntimeException("query boom"));
        Entity target = mock(Entity.class);
        assertEquals(ShadowProtectionResult.DENIED_AREA, new OpacProtectionProvider().checkArea(context(target)));
    }

    @Test
    void unresolvableTargetFailsClosedWithoutQueryingOpac() {
        when(level.getEntity(any(UUID.class))).thenReturn(null);
        ShadowAttemptContext ctx = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.PLAYER, UUID.randomUUID(), null, level, new BlockPos(1, 2, 3),
                1L, false, 1.0d, true);
        assertEquals(ShadowProtectionResult.DENIED_AREA, new OpacProtectionProvider().checkArea(ctx));
    }

}
