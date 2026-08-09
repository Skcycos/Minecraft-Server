package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.brewing.BeverageTierManager;
import com.tanrunn.tcth.impl.compat.brewinandchewin.KegPouringAdapter;
import com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Phase 7B behavior tests: public API, dispatcher guards and the Keg pouring
 * adapter. These do not execute the actual Mixin (validated by smoke test);
 * they verify the shared logic the mixin delegates to.
 */
class BrewerIntegrationTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private AtomicReference<BeveragePreparedEvent> captured;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        BrewerIntegrationDispatcher.resetForTesting();
        BrewerIntegrationDispatcher.setFrameworkEnabledSupplierForTesting(() -> true);
        BrewerIntegrationDispatcher.setBrewerEnabledSupplierForTesting(() -> true);
        bus = BusBuilder.builder().build();
        BrewerIntegrationDispatcher.setGameBusForTesting(bus);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.doReturn(new com.mojang.authlib.GameProfile(player.getUUID(), "Tanrunn"))
                .when(player).getGameProfile();
        captured = new AtomicReference<>();
        bus.addListener((BeveragePreparedEvent e) -> captured.set(e));
    }

    @AfterEach
    void tearDown() {
        BrewerIntegrationDispatcher.resetForTesting();
        BeverageTierManager.resetForTesting();
    }

    // ---- API 非空/防御复制/唯一 eventId ----

    @Test
    void eventRejectsNullAndCopiesResult() {
        ItemStack result = new ItemStack(Items.POTION);
        BeveragePreparedEvent e = new BeveragePreparedEvent(
                UUID.randomUUID(), player, null, result, BeverageDevice.KEG, BeverageTier.COMMON, false, level, null);
        assertThrows(NullPointerException.class, () -> new BeveragePreparedEvent(
                null, player, null, result, BeverageDevice.KEG, BeverageTier.COMMON, false, level, null));
        assertThrows(NullPointerException.class, () -> new BeveragePreparedEvent(
                UUID.randomUUID(), player, null, null, BeverageDevice.KEG, BeverageTier.COMMON, false, level, null));
        // defensive copy: mutating the event result must not affect the original
        e.getResult().setCount(99);
        assertNotEquals(99, result.getCount());
    }

    @Test
    void eventIdsAreUnique() {
        ItemStack r = new ItemStack(Items.POTION);
        UUID a = new BeveragePreparedEvent(UUID.randomUUID(), player, null, r, BeverageDevice.KEG,
                BeverageTier.T2, false, level, null).getEventId();
        UUID b = new BeveragePreparedEvent(UUID.randomUUID(), player, null, r, BeverageDevice.KEG,
                BeverageTier.T2, false, level, null).getEventId();
        assertNotEquals(a, b);
    }

    // ---- dispatcher 开关/服务端 ----

    @Test
    void dispatcherPublishesWhenEnabled() {
        ItemStack r = new ItemStack(Items.POTION);
        assertEquals(BrewerIntegrationDispatcher.Result.POSTED,
                BrewerIntegrationDispatcher.publish(player, null, r, BeverageDevice.KEG, BeverageTier.COMMON, level, new BlockPos(1, 2, 3)));
        assertNotNull(captured.get());
        assertEquals(BeverageDevice.KEG, captured.get().getDevice());
        assertEquals(new BlockPos(1, 2, 3), captured.get().getPosition());
        assertFalse(captured.get().isAutomated());
    }

    @Test
    void dispatcherDisabledIsFailClosed() {
        BrewerIntegrationDispatcher.setFrameworkEnabledSupplierForTesting(() -> false);
        assertEquals(BrewerIntegrationDispatcher.Result.DISABLED,
                BrewerIntegrationDispatcher.publish(player, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNull(captured.get());
    }

    @Test
    void dispatcherClientLevelRejected() {
        Mockito.when(level.isClientSide()).thenReturn(true);
        assertEquals(BrewerIntegrationDispatcher.Result.INVALID_CONTEXT,
                BrewerIntegrationDispatcher.publish(player, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNull(captured.get());
    }

    @Test
    void realPlayerIsNotAutomated() {
        // Injectable predicate: default production behaviour — a real player
        // must NOT be treated as automated.
        BrewerIntegrationDispatcher.setFakePlayerPredicateForTesting(p -> p instanceof FakePlayer);
        assertEquals(BrewerIntegrationDispatcher.Result.POSTED,
                BrewerIntegrationDispatcher.publish(player, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNotNull(captured.get());
        assertFalse(captured.get().isAutomated(), "real player must not be automated");
        assertNotNull(captured.get().getPlayer());
    }

    @Test
    void automatedActorIsAutomatedViaPredicate() {
        // Injectable predicate: simulate an automated actor (e.g. FakePlayer)
        // without constructing one (registries unavailable in tests).
        BrewerIntegrationDispatcher.setFakePlayerPredicateForTesting(p -> true);
        assertEquals(BrewerIntegrationDispatcher.Result.POSTED,
                BrewerIntegrationDispatcher.publish(player, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNotNull(captured.get());
        assertTrue(captured.get().isAutomated(), "automated actor must be flagged automated");
        assertNull(captured.get().getPlayer(), "automated actor normalised to player=null");
    }

    @Test
    void nullPlayerIsAutomated() {
        assertEquals(BrewerIntegrationDispatcher.Result.POSTED,
                BrewerIntegrationDispatcher.publish(null, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNotNull(captured.get());
        assertTrue(captured.get().isAutomated());
        assertNull(captured.get().getPlayer());
    }

    @Test
    void dispatcherConfigExceptionFailsClosed() {
        BrewerIntegrationDispatcher.setFrameworkEnabledSupplierForTesting(() -> {
            throw new RuntimeException("config boom");
        });
        assertEquals(BrewerIntegrationDispatcher.Result.DISABLED,
                BrewerIntegrationDispatcher.publish(player, null, new ItemStack(Items.POTION),
                        BeverageDevice.KEG, BeverageTier.COMMON, level, null));
        assertNull(captured.get());
    }

    // ---- Keg adapter ----

    @Test
    void kegAdapterPublishesForRuntimeTier() {
        BeverageTierManager.setTierMapForTesting(Map.of(
                ResourceLocation.parse("minecraft:potion"), BeverageTier.T2));
        boolean ok = KegPouringAdapter.onPouringDelivered(
                player, new ItemStack(Items.POTION), level, new BlockPos(4, 5, 6));
        assertTrue(ok);
        assertEquals(BeverageTier.T2, captured.get().getTier());
        assertEquals(BeverageDevice.KEG, captured.get().getDevice());
        assertEquals(new BlockPos(4, 5, 6), captured.get().getPosition());
        assertNull(captured.get().getRecipeId(), "recipeId always null for Keg");
    }

    @Test
    void kegAdapterRejectsUnknownTier() {
        BeverageTierManager.setTierMapForTesting(Map.of()); // everything UNKNOWN
        boolean ok = KegPouringAdapter.onPouringDelivered(
                player, new ItemStack(Items.POTION), level, null);
        assertFalse(ok, "UNKNOWN-tier (T3/INGREDIENT/container/EXCLUDED) must not publish");
        assertNull(captured.get());
    }

    @Test
    void kegAdapterRejectsEmptyStack() {
        boolean ok = KegPouringAdapter.onPouringDelivered(player, ItemStack.EMPTY, level, null);
        assertFalse(ok);
        assertNull(captured.get());
    }

    // ---- BeverageTier 枚举（无 T3_CANDIDATE/INGREDIENT）----

    @Test
    void beverageTierEnumIsRuntimeOnly() {
        assertEquals(4, BeverageTier.values().length);
        assertEquals("UNKNOWN", BeverageTier.UNKNOWN.name());
        assertEquals("COMMON", BeverageTier.COMMON.name());
        assertEquals("T2", BeverageTier.T2.name());
        assertEquals("T3", BeverageTier.T3.name());
    }

    @Test
    void beverageDeviceEnumHasExpectedMembers() {
        assertTrue(BeverageDevice.valueOf("KEG") != null);
        assertTrue(BeverageDevice.valueOf("SHAKER") != null);
        assertTrue(BeverageDevice.valueOf("BARREL") != null);
        assertTrue(BeverageDevice.valueOf("BLENDER") != null);
        assertTrue(BeverageDevice.valueOf("OTHER") != null);
    }
}
