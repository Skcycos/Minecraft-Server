package com.tanrunn.tcth.impl.compat.scguns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link NiamiArrowRegistry} (phase 5A.1).
 *
 * <p>Covers: birth registration, validator rejection, one-shot consumption,
 * TTL expiry, removal on leave/logout/stop, capacity bound, integration
 * switch off.
 */
class NiamiArrowRegistryTest {

    private ServerPlayer player;
    private UUID playerId;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        NiamiArrowRegistry.resetForTesting();
        NiamiArrowRegistry.setIntegrationEnabledSupplierForTesting(() -> true);
        NiamiArrowRegistry.setArrowGunValidatorForTesting(stack -> true);
        playerId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
    }

    @AfterEach
    void tearDown() {
        NiamiArrowRegistry.resetForTesting();
    }

    private Arrow newArrow() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUUID()).thenReturn(UUID.randomUUID());
        return arrow;
    }

    @Test
    void registerCreatesRecord() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        assertTrue(NiamiArrowRegistry.register(arrow, player));
        assertEquals(1, NiamiArrowRegistry.sizeForTesting());
        NiamiArrowRegistry.ArrowRecord record = NiamiArrowRegistry.take(arrow.getUUID());
        assertNotNull(record);
        assertEquals(playerId, record.shooterUuid());
        assertEquals("minecraft:diamond_sword", record.weaponId().toString());
    }

    @Test
    void takeConsumesRecord() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        NiamiArrowRegistry.register(arrow, player);
        assertNotNull(NiamiArrowRegistry.take(arrow.getUUID()));
        assertNull(NiamiArrowRegistry.take(arrow.getUUID()),
                "an arrow record must settle at most once");
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    @Test
    void validatorRejectionDoesNotRegister() {
        NiamiArrowRegistry.setArrowGunValidatorForTesting(stack -> false);
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        assertFalse(NiamiArrowRegistry.register(arrow, player));
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    @Test
    void ttlExpiryCleansRecords() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        NiamiArrowRegistry.register(arrow, player);
        for (int i = 0; i <= NiamiArrowRegistry.ARROW_TTL_TICKS; i++) {
            NiamiArrowRegistry.onServerTick();
        }
        assertNull(NiamiArrowRegistry.take(arrow.getUUID()), "expired record must be dropped");
    }

    @Test
    void removeDropsRecord() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        NiamiArrowRegistry.register(arrow, player);
        NiamiArrowRegistry.remove(arrow.getUUID());
        assertNull(NiamiArrowRegistry.take(arrow.getUUID()));
    }

    @Test
    void logoutClearsShooterRecords() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow a1 = newArrow();
        Arrow a2 = newArrow();
        NiamiArrowRegistry.register(a1, player);
        NiamiArrowRegistry.register(a2, player);
        assertEquals(2, NiamiArrowRegistry.sizeForTesting());
        NiamiArrowRegistry.removeForShooter(playerId);
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    @Test
    void serverStopClearsEverything() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        NiamiArrowRegistry.register(newArrow(), player);
        NiamiArrowRegistry.register(newArrow(), player);
        assertEquals(2, NiamiArrowRegistry.sizeForTesting());
        NiamiArrowRegistry.onServerStopping();
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
        assertEquals(0, NiamiArrowRegistry.currentTickForTesting());
    }

    @Test
    void capacityIsBounded() {
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        int cap = NiamiArrowRegistry.MAX_ARROW_ENTRIES;
        for (int i = 0; i < cap + 10; i++) {
            NiamiArrowRegistry.register(newArrow(), player);
        }
        assertTrue(NiamiArrowRegistry.sizeForTesting() <= cap);
    }

    @Test
    void integrationDisabledDoesNotRegister() {
        NiamiArrowRegistry.setIntegrationEnabledSupplierForTesting(() -> false);
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        Arrow arrow = newArrow();
        assertFalse(NiamiArrowRegistry.register(arrow, player));
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertFalse(NiamiArrowRegistry.register(null, player));
        assertFalse(NiamiArrowRegistry.register(newArrow(), null));
        assertNull(NiamiArrowRegistry.take(null));
    }
}
