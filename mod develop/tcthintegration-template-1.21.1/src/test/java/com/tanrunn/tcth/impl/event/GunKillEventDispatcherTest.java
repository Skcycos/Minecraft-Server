package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Unit tests for {@link GunKillEventDispatcher} (phase 5A).
 *
 * <p>Covers: framework switch, gunner switch, invalid context (client-side
 * level), idempotency, TTL expiry, capacity cap, stop cleanup.
 */
class GunKillEventDispatcherTest {

    private static final ResourceLocation WEAPON_ID = ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol");
    private static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        GunKillEventDispatcher.resetForTesting();
        bus = BusBuilder.builder().build();
        level = mock(ServerLevel.class); // isClientSide() -> false by default
        player = mock(ServerPlayer.class);
        GunKillEventDispatcher.setGameBusForTesting(bus);
    }

    @AfterEach
    void tearDown() {
        GunKillEventDispatcher.resetForTesting();
    }

    @Test
    void frameworkDisabledRejectsAll() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> false);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        assertEquals(GunKillEventDispatcher.Result.FRAMEWORK_DISABLED, publishEvent(UUID.randomUUID()));
    }

    @Test
    void gunnerDisabledRejectsAll() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> false);
        assertEquals(GunKillEventDispatcher.Result.GUNNER_DISABLED, publishEvent(UUID.randomUUID()));
    }

    @Test
    void clientSideLevelIsInvalidContext() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        when(level.isClientSide()).thenReturn(true);
        assertEquals(GunKillEventDispatcher.Result.INVALID_CONTEXT, publishEvent(UUID.randomUUID()));
    }

    @Test
    void enabledPublishesExactlyOnce() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        UUID eventId = UUID.randomUUID();
        assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(eventId));
        assertEquals(1, GunKillEventDispatcher.trackedKillCountForTesting(),
                "a posted event must enter the idempotency cache");
    }

    @Test
    void duplicateEventIdIsRejected() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        UUID eventId = UUID.randomUUID();
        assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(eventId));
        assertEquals(GunKillEventDispatcher.Result.DUPLICATE, publishEvent(eventId),
                "the same kill must never be posted twice");
    }

    @Test
    void ttlExpiryAllowsRepost() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        UUID eventId = UUID.randomUUID();
        assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(eventId));
        // Advance time past the TTL and repost the same id — allowed again.
        for (int i = 0; i <= GunKillEventDispatcher.IDEMPOTENCY_EXPIRY_TICKS; i++) {
            GunKillEventDispatcher.onServerTick(null);
        }
        assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(eventId));
    }

    @Test
    void capacityCapIsEnforced() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        int max = GunKillEventDispatcher.MAX_TRACKED_KILLS;
        for (int i = 0; i < max + 10; i++) {
            assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(UUID.randomUUID()));
        }
        assertTrue(GunKillEventDispatcher.trackedKillCountForTesting() <= max,
                "the idempotency cache must never exceed its capacity");
    }

    @Test
    void idempotencyExpiryIsPositive() {
        assertTrue(GunKillEventDispatcher.IDEMPOTENCY_EXPIRY_TICKS > 0);
        assertTrue(GunKillEventDispatcher.IDEMPOTENCY_EXPIRY_TICKS <= 200);
    }

    @Test
    void stopCleanupClearsCache() {
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        assertEquals(GunKillEventDispatcher.Result.POSTED, publishEvent(UUID.randomUUID()));
        assertEquals(1, GunKillEventDispatcher.trackedKillCountForTesting());
        GunKillEventDispatcher.onServerStopping(null);
        assertEquals(0, GunKillEventDispatcher.trackedKillCountForTesting());
    }

    private GunKillEventDispatcher.Result publishEvent(UUID eventId) {
        GunKillEvent event = new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                TARGET_ID, UUID.randomUUID(), GunTargetTier.COMMON, 10.0f, false, level, BlockPos.ZERO);
        return GunKillEventDispatcher.publish(event);
    }
}
