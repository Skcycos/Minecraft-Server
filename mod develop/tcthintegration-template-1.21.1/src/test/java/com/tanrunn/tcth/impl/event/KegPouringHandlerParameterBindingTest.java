package com.tanrunn.tcth.impl.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Phase 7C.2.1 — parameter-binding regression guards for the Keg delivery
 * handlers.
 *
 * <p>7C.2 live acceptance found that the two {@code @Inject} handlers were
 * publishing the <em>original held stack</em> (lambda param0, empty after the
 * keg shrinks the container) instead of the <em>delivered beverage</em>
 * (lambda param3). This caused the hand-replacement and full-inventory drop
 * branches to publish 0 events. The fix renamed the handler parameters to
 * {@code originalHeldStack / player / hand / deliveredStack} and made both
 * handlers publish {@code deliveredStack}.
 *
 * <p>These tests call the production handlers directly via reflection (they
 * are {@code private static} mixin methods), passing an empty container as
 * {@code originalHeldStack} and a real runtime-tier beverage as
 * {@code deliveredStack}, and assert the published event's result is the
 * {@code deliveredStack} — never the {@code originalHeldStack}. This is not a
 * source-text scan.
 */
class KegPouringHandlerParameterBindingTest {

    private static Method onReplacedInHand;
    private static Method onDropped;

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private AtomicReference<BeveragePreparedEvent> captured;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        MinecraftTestBootstrap.bootStrap();
        Class<?> mixin = Class.forName("com.tanrunn.tcth.mixin.brewinandchewin.KegPouringMixin");
        onReplacedInHand = mixin.getDeclaredMethod("tcth$onReplacedInHand",
                ItemStack.class, net.minecraft.world.entity.player.Player.class, InteractionHand.class, ItemStack.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        onReplacedInHand.setAccessible(true);
        onDropped = mixin.getDeclaredMethod("tcth$onDropped",
                ItemStack.class, net.minecraft.world.entity.player.Player.class, InteractionHand.class, ItemStack.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        onDropped.setAccessible(true);
    }

    @BeforeEach
    void setUp() {
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.resetForTesting();
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher
                .setFrameworkEnabledSupplierForTesting(() -> true);
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher
                .setBrewerEnabledSupplierForTesting(() -> true);
        bus = BusBuilder.builder().build();
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.setGameBusForTesting(bus);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.doReturn(new com.mojang.authlib.GameProfile(player.getUUID(), "Tanrunn"))
                .when(player).getGameProfile();
        Mockito.when(player.level()).thenReturn(level);
        captured = new AtomicReference<>();
        bus.addListener((BeveragePreparedEvent e) -> captured.set(e));
    }

    @AfterEach
    void tearDown() {
        com.tanrunn.tcth.impl.event.BrewerIntegrationDispatcher.resetForTesting();
        BeverageTierManager.resetForTesting();
    }

    private static org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci() {
        return new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("test", false);
    }

    @Test
    void setItemInHandPublishesDeliveredStackNotEmptyOriginal() throws Exception {
        BeverageTierManager.setTierMapForTesting(Map.of(
                ResourceLocation.parse("minecraft:honey_bottle"), BeverageTier.COMMON));
        ItemStack original = new ItemStack(Items.GLASS_BOTTLE, 0);
        ItemStack delivered = new ItemStack(Items.HONEY_BOTTLE, 1);

        onReplacedInHand.invoke(null, original, player, InteractionHand.MAIN_HAND, delivered, ci());

        assertNotNull(captured.get(), "setItemInHand branch must publish exactly one event");
        BeveragePreparedEvent e = captured.get();
        assertEquals(Items.HONEY_BOTTLE, e.getResult().getItem(),
                "result must be the delivered beverage, not the original held stack");
        assertEquals(1, e.getResult().getCount());
        assertEquals(BeverageTier.COMMON, e.getTier());
        assertEquals(BeverageDevice.KEG, e.getDevice());
        assertTrue(original.isEmpty(), "precondition: original held stack is empty");
    }

    @Test
    void dropPublishesDeliveredStackNotRemainingContainer() throws Exception {
        BeverageTierManager.setTierMapForTesting(Map.of(
                ResourceLocation.parse("minecraft:honey_bottle"), BeverageTier.COMMON));
        ItemStack original = new ItemStack(Items.GLASS_BOTTLE, 2);
        ItemStack delivered = new ItemStack(Items.HONEY_BOTTLE, 1);

        onDropped.invoke(null, original, player, InteractionHand.MAIN_HAND, delivered, ci());

        assertNotNull(captured.get(), "drop branch must publish exactly one event");
        BeveragePreparedEvent e = captured.get();
        assertEquals(Items.HONEY_BOTTLE, e.getResult().getItem(),
                "result must be the delivered beverage, not the remaining containers");
        assertEquals(1, e.getResult().getCount());
        assertEquals(BeverageTier.COMMON, e.getTier());
        assertEquals(BeverageDevice.KEG, e.getDevice());
    }

    @Test
    void setItemInHandWithUnknownTierPublishesNothing() throws Exception {
        BeverageTierManager.setTierMapForTesting(Map.of()); // everything UNKNOWN
        ItemStack original = new ItemStack(Items.GLASS_BOTTLE, 0);
        ItemStack delivered = new ItemStack(Items.HONEY_BOTTLE, 1);

        onReplacedInHand.invoke(null, original, player, InteractionHand.MAIN_HAND, delivered, ci());

        assertNull(captured.get(), "UNKNOWN-tier must not publish even on the setItemInHand branch");
    }

    @Test
    void dropWithUnknownTierPublishesNothing() throws Exception {
        BeverageTierManager.setTierMapForTesting(Map.of()); // everything UNKNOWN
        ItemStack original = new ItemStack(Items.GLASS_BOTTLE, 2);
        ItemStack delivered = new ItemStack(Items.HONEY_BOTTLE, 1);

        onDropped.invoke(null, original, player, InteractionHand.MAIN_HAND, delivered, ci());

        assertNull(captured.get(), "UNKNOWN-tier must not publish even on the drop branch");
    }
}
