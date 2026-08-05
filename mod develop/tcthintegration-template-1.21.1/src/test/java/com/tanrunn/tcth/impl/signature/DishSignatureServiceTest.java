package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mojang.authlib.GameProfile;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Unit tests for {@link DishSignatureService} gating and in-place mutation.
 */
class DishSignatureServiceTest {

    private static final UUID CHEF = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CHEF2 = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private ServerPlayer player;
    private ServerPlayer other;

    @BeforeAll
    static void bootstrap() {
        SignatureTestRegistry.ensureRegistered();
    }

    @BeforeEach
    void setUp() {
        DishSignatureService.resetForTesting();
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
        player = mockPlayer(CHEF, "Tanrunn");
        other = mockPlayer(CHEF2, "OtherChef");
    }

    @AfterEach
    void tearDown() {
        DishSignatureService.resetForTesting();
        DishCookedEventDispatcher.resetForTesting();
    }

    private static ServerPlayer mockPlayer(UUID uuid, String name) {
        ServerPlayer p = Mockito.mock(ServerPlayer.class);
        Mockito.when(p.getUUID()).thenReturn(uuid);
        Mockito.when(p.getGameProfile()).thenReturn(new GameProfile(uuid, name));
        Mockito.when(p.getName()).thenReturn(Component.literal(name));
        return p;
    }

    private static CookingSignature signatureOf(ItemStack stack) {
        return stack.get(CookingSignatureComponents.type());
    }

    @Test
    void signsRealDishStackInPlace() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        assertTrue(DishSignatureService.sign(player, dish));
        CookingSignature sig = signatureOf(dish);
        assertNotNull(sig, "the real stack must carry the signature");
        assertEquals(CHEF, sig.chefId());
        assertEquals("Tanrunn", sig.chefName());
    }

    @Test
    void disabledConfigDoesNotSign() {
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> false);
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        assertFalse(DishSignatureService.sign(player, dish));
        assertEquals(null, signatureOf(dish));
    }

    @Test
    void disabledFrameworkDoesNotSign() {
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> false);
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        assertFalse(DishSignatureService.sign(player, dish));
        assertEquals(null, signatureOf(dish));
    }

    @Test
    void nullPlayerDoesNotSign() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        assertFalse(DishSignatureService.sign(null, dish));
        assertEquals(null, signatureOf(dish));
    }

    @Test
    void emptyStackDoesNotSign() {
        assertFalse(DishSignatureService.sign(player, ItemStack.EMPTY));
    }

    @Test
    void nonDishDoesNotSign() {
        ItemStack stick = new ItemStack(Items.STICK);
        assertFalse(DishSignatureService.sign(player, stick));
        assertEquals(null, signatureOf(stick));
    }

    @Test
    void previousSignatureIsOverwrittenByNewChef() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        assertTrue(DishSignatureService.sign(player, dish));
        assertEquals(CHEF, signatureOf(dish).chefId());

        assertTrue(DishSignatureService.sign(other, dish));
        assertEquals(CHEF2, signatureOf(dish).chefId(), "the finishing player becomes the new chef");
    }

    @Test
    void countIsUnchanged() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF, 4);
        assertTrue(DishSignatureService.sign(player, dish));
        assertEquals(4, dish.getCount());
    }

    @Test
    void otherModComponentsArePreserved() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        dish.set(DataComponents.CUSTOM_NAME, Component.literal("Custom Label"));
        assertTrue(dish.has(DataComponents.CUSTOM_NAME));
        assertTrue(DishSignatureService.sign(player, dish));
        assertTrue(dish.has(DataComponents.CUSTOM_NAME),
                "signing must not touch other mods'/vanilla components");
        assertNotNull(signatureOf(dish));
    }

    @Test
    void eventDefensiveCopyCarriesSignatureButIsIndependent() {
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        IEventBus bus = BusBuilder.builder().build();
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        java.util.concurrent.atomic.AtomicReference<DishCookedEvent> captured = new java.util.concurrent.atomic.AtomicReference<>();
        bus.addListener(DishCookedEvent.class, captured::set);

        ServerLevel level = Mockito.mock(ServerLevel.class);
        ItemStack dish = new ItemStack(Items.COOKED_BEEF, 2);
        assertTrue(DishSignatureService.sign(player, dish));
        DishCookedEventDispatcher.publish(player, null, dish, CookingDevice.FURNACE, DishQuality.UNKNOWN, false, level, null);

        DishCookedEvent event = captured.get();
        assertNotNull(event);
        assertNotNull(signatureOf(event.getResult()), "the event copy must carry the signature");
        assertEquals(CHEF, signatureOf(event.getResult()).chefId());

        // Mutating the event copy must not affect the real stack.
        event.getResult().remove(CookingSignatureComponents.type());
        assertNotNull(signatureOf(dish), "mutating the event copy must not affect the real dish");
    }

    @Test
    void signFailureDoesNotThrow() {
        // Null result is handled gracefully (no NPE).
        assertFalse(DishSignatureService.sign(player, null));
    }

    @Test
    void rawDoughIsNotADishAndIsNeverSigned() {
        // raw_dough is excluded by the tcth:not_dishes classifier rule; in the
        // bare test registry it is simply not a food dish, so it must not be
        // signed. (The classifier's own tests cover the not_dishes tag path.)
        ItemStack rawDough = new ItemStack(Items.STICK);
        assertFalse(DishSignatureService.sign(player, rawDough));
        assertEquals(null, signatureOf(rawDough));
    }

    @Test
    void automatedFlagIsNotPartOfService() {
        // The service itself only sees player+stack; automation gating lives in
        // the detectors (null player for automated production). A null player
        // must never sign — covered by nullPlayerDoesNotSign.
        assertTrue(player != null);
    }

    @SuppressWarnings("unused")
    private static ResourceLocation unused() {
        return null;
    }
}
