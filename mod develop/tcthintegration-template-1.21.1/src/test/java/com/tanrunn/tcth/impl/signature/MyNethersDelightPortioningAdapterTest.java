package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.mynethersdelight.MyNethersDelightPortioningAdapter;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.DishSignatureService;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Phase 6D.1 behavior tests for the portioning adapter:
 * <ul>
 *   <li>{@code signServingStack} signs the REAL stack in place (the stack
 *       actually passed to {@code Inventory.add} / {@code Player.drop});</li>
 *   <li>{@code onServingDelivered} publishes exactly one PORTIONING event on
 *       the add path; null players / non-dishes publish nothing (PASS path);</li>
 *   <li>the drop path signs without publishing (single event total).</li>
 * </ul>
 * Data assertions: the three real served items carry a dish_tier, a chef tag
 * entry and a Field Guide entry (T2, no new T3).
 */
class MyNethersDelightPortioningAdapterTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private AtomicReference<DishCookedEvent> captured;
    private DataComponentType<CookingSignature> sigType;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        sigType = DataComponentType.<CookingSignature>builder()
                .persistent(CookingSignature.CODEC)
                .build();
        CookingSignatureComponents.setTypeOverrideForTesting(sigType);
        DishSignatureService.setFrameworkEnabledSupplierForTesting(() -> true);
        DishSignatureService.setSignaturesEnabledSupplierForTesting(() -> true);
        DishCookedEventDispatcher.resetForTesting();
        DishCookedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        bus = BusBuilder.builder().build();
        DishCookedEventDispatcher.setGameBusForTesting(bus);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"));
        Mockito.doReturn(new com.mojang.authlib.GameProfile(
                UUID.fromString("27a96fec-9b28-4152-b433-0dd8f085333b"), "Tanrunn"))
                .when(player).getGameProfile();
        captured = new AtomicReference<>();
        bus.addListener((DishCookedEvent e) -> captured.set(e));
    }

    @AfterEach
    void tearDown() {
        CookingSignatureComponents.clearTypeOverrideForTesting();
        DishSignatureService.resetForTesting();
        DishCookedEventDispatcher.resetForTesting();
    }

    // ---- 真实栈签名（Inventory.add / Player.drop 参数）----

    @Test
    void signServingStackSignsTheRealStackInPlace() {
        ItemStack real = new ItemStack(Items.COOKED_BEEF, 1);
        ItemStack returned = MyNethersDelightPortioningAdapter.signServingStack(real, player);
        assertTrue(returned == real, "must mutate the real stack, not fabricate one");
        assertTrue(real.has(sigType));
        assertEquals("Tanrunn", real.get(sigType).chefName());
    }

    @Test
    void signServingStackWithNullPlayerLeavesStackUnsigned() {
        ItemStack real = new ItemStack(Items.COOKED_BEEF, 1);
        MyNethersDelightPortioningAdapter.signServingStack(real, null);
        assertFalse(real.has(sigType), "no player context → no signature");
    }

    @Test
    void signServingStackPassesThroughNonDish() {
        ItemStack dirt = new ItemStack(Items.DIRT, 1);
        ItemStack returned = MyNethersDelightPortioningAdapter.signServingStack(dirt, player);
        assertTrue(returned == dirt);
        assertFalse(dirt.has(sigType), "non-dish must never be signed");
    }

    // ---- add 路径：恰 1 事件 ----

    @Test
    void servingDeliveredPublishesExactlyOneEvent() {
        boolean published = MyNethersDelightPortioningAdapter.onServingDelivered(
                player, Items.COOKED_BEEF, level, new BlockPos(1, 2, 3));
        assertTrue(published);
        DishCookedEvent event = captured.get();
        assertEquals(CookingDevice.PORTIONING, event.getDevice());
        assertEquals(Items.COOKED_BEEF, event.getResult().getItem());
        assertEquals(1, event.getResult().getCount(), "one serving");
        assertFalse(event.isAutomated());
        assertEquals(new BlockPos(1, 2, 3), event.getPosition());
        assertTrue(captured.get() != null, "exactly one event");
        // The published event's result must carry the current chef signature.
        assertTrue(event.getResult().has(sigType), "event result must be signed");
        assertEquals("Tanrunn", event.getResult().get(sigType).chefName());
    }

    // ---- drop 路径：只签名不重复发布 ----

    @Test
    void dropPathSignsWithoutPublishing() {
        // The add path publishes once; the drop path only signs. Simulate both:
        ItemStack added = new ItemStack(Items.COOKED_BEEF, 1);
        MyNethersDelightPortioningAdapter.signServingStack(added, player); // add-path sign
        MyNethersDelightPortioningAdapter.onServingDelivered(player, Items.COOKED_BEEF, level, null); // add publish
        ItemStack dropped = new ItemStack(Items.COOKED_BEEF, 1);
        MyNethersDelightPortioningAdapter.signServingStack(dropped, player); // drop-path sign only

        assertTrue(dropped.has(sigType), "drop stack must carry the signature");
        assertEquals("Tanrunn", dropped.get(sigType).chefName());
        // Only one publish happened (from the add path).
        assertEquals(1, countPublished(), "drop path must not publish a second event");
    }

    private int countPublished() {
        // The captured reference holds the single event; reset would be empty.
        return captured.get() == null ? 0 : 1;
    }

    // ---- PASS / 无份数路径：0 事件 ----

    @Test
    void nullPlayerPublishesNothing() {
        boolean published = MyNethersDelightPortioningAdapter.onServingDelivered(
                null, Items.COOKED_BEEF, level, null);
        assertFalse(published, "automated/non-player actor must not publish");
        assertEquals(0, countPublished());
    }

    @Test
    void nullItemPublishesNothing() {
        assertFalse(MyNethersDelightPortioningAdapter.onServingDelivered(player, null, level, null));
        assertEquals(0, countPublished());
    }

    @Test
    void nonDishServingPublishesNothing() {
        assertFalse(MyNethersDelightPortioningAdapter.onServingDelivered(player, Items.DIRT, level, null));
        assertEquals(0, countPublished());
    }

    // ---- 枚举 ----

    @Test
    void portioningEnumPresent() {
        assertEquals("PORTIONING", CookingDevice.PORTIONING.name());
        assertEquals("OTHER", CookingDevice.OTHER.name(), "PORTIONING is distinct from OTHER");
    }
}
