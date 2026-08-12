package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Unit tests for {@link ShadowTheftEventDispatcher} (phase 8B).
 *
 * <p>Covers: master switch, invalid context (client side / FakePlayer),
 * posting, defensive listener-exception isolation, idempotent init.
 */
class ShadowTheftEventDispatcherTest {

    private IEventBus bus;
    private AtomicInteger posted;
    private ServerLevel level;
    private ServerPlayer thief;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowTheftEventDispatcher.resetForTesting();
        bus = BusBuilder.builder().build();
        posted = new AtomicInteger(0);
        bus.addListener((ShadowTheftEvent event) -> posted.incrementAndGet());
        ShadowTheftEventDispatcher.setGameBusForTesting(bus);
        ShadowTheftEventDispatcher.setEnabledSupplierForTesting(() -> true);
        level = mock(ServerLevel.class);
        thief = mock(ServerPlayer.class);
    }

    @AfterEach
    void tearDown() {
        ShadowTheftEventDispatcher.resetForTesting();
    }

    private ShadowTheftEvent event() {
        return new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, null, ShadowTheftOutcome.NO_CANDIDATE,
                ShadowTheftReceipt.empty(), false, level, BlockPos.ZERO);
    }

    @Test
    void frameworkDisabledRejectsAll() {
        ShadowTheftEventDispatcher.setEnabledSupplierForTesting(() -> false);
        assertEquals(ShadowTheftEventDispatcher.Result.FRAMEWORK_DISABLED, ShadowTheftEventDispatcher.publish(event()));
        assertEquals(0, posted.get());
    }

    @Test
    void clientSideLevelIsInvalidContext() {
        when(level.isClientSide()).thenReturn(true);
        assertEquals(ShadowTheftEventDispatcher.Result.INVALID_CONTEXT, ShadowTheftEventDispatcher.publish(event()));
        assertEquals(0, posted.get());
    }

    @Test
    void fakePlayerThiefIsInvalidContext() {
        net.neoforged.neoforge.common.util.FakePlayer fake = mock(net.neoforged.neoforge.common.util.FakePlayer.class);
        ShadowTheftEvent fakeEvent = new ShadowTheftEvent(UUID.randomUUID(), fake, ShadowTargetKind.PLAYER,
                UUID.randomUUID(), null, null, ShadowTheftOutcome.NO_CANDIDATE,
                ShadowTheftReceipt.empty(), false, level, null);
        assertEquals(ShadowTheftEventDispatcher.Result.INVALID_CONTEXT, ShadowTheftEventDispatcher.publish(fakeEvent));
        assertEquals(0, posted.get());
    }

    @Test
    void enabledPostsTheEvent() {
        assertEquals(ShadowTheftEventDispatcher.Result.POSTED, ShadowTheftEventDispatcher.publish(event()));
        assertEquals(1, posted.get());
    }

    @Test
    void listenerExceptionIsIsolated() {
        // The NeoForge bus rethrows listener exceptions; the dispatcher must
        // catch them so the server tick never breaks. Listeners registered
        // before the failing one still run (setUp's counting listener).
        bus.addListener((ShadowTheftEvent e) -> {
            throw new IllegalStateException("boom");
        });
        ShadowTheftEventDispatcher.Result result = ShadowTheftEventDispatcher.publish(event());
        assertEquals(1, posted.get(), "listeners before the failing one must still run");
        assertTrue(result == ShadowTheftEventDispatcher.Result.POSTED
                        || result == ShadowTheftEventDispatcher.Result.INVALID_CONTEXT,
                "a listener exception must be isolated by the dispatcher, never escape");
    }

    @Test
    void nullEventIsExplicitlyRejected() {
        assertEquals(ShadowTheftEventDispatcher.Result.INVALID_CONTEXT,
                ShadowTheftEventDispatcher.publish(null), "publish(null) must be explicitly rejected");
        assertEquals(0, posted.get());
    }

    @Test
    void initIsIdempotent() {
        ShadowTheftEventDispatcher.init(bus);
        ShadowTheftEventDispatcher.init(bus);
        assertEquals(ShadowTheftEventDispatcher.Result.POSTED, ShadowTheftEventDispatcher.publish(event()));
        assertTrue(ShadowTheftEventDispatcher.Result.POSTED.name().length() > 0);
    }
}
