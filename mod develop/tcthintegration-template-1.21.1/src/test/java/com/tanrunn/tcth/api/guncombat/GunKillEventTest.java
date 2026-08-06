package com.tanrunn.tcth.api.guncombat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link GunKillEvent} (phase 5A).
 *
 * <p>Covers: non-null validation of every required field, defensive ItemStack
 * copies, stable eventId, immutable position, tier accessors.
 */
class GunKillEventTest {

    private static final ResourceLocation WEAPON_ID = ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol");
    private static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");
    private static final float DISTANCE = 25.5f;
    private static final GunTargetTier TIER = GunTargetTier.COMMON;

    private UUID eventId;
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        level = mock(ServerLevel.class);
    }

    private GunKillEvent newEvent() {
        return newEvent(new ItemStack(Items.DIAMOND_SWORD), UUID.randomUUID(), false);
    }

    private GunKillEvent newEvent(ItemStack weapon, UUID targetUuid, boolean automated) {
        return new GunKillEvent(eventId, player, WEAPON_ID, weapon.copy(), TARGET_ID, targetUuid,
                TIER, DISTANCE, automated, level, BlockPos.ZERO);
    }

    @Test
    void eventIdIsStableAndNotNull() {
        GunKillEvent event = newEvent();
        assertNotNull(event.getEventId());
        assertEquals(eventId, event.getEventId());
        assertEquals(eventId, event.getEventId());
    }

    @Test
    void weaponDefensiveCopyOnConstruction() {
        GunKillEvent event = newEvent();
        ItemStack original = new ItemStack(Items.DIAMOND_SWORD, 64);
        ItemStack fromEvent = new GunKillEvent(eventId, player, WEAPON_ID, original, TARGET_ID,
                UUID.randomUUID(), TIER, DISTANCE, false, level, BlockPos.ZERO).getWeapon();
        original.setCount(99);
        assertEquals(64, fromEvent.getCount(), "event weapon must be a copy, not the original");
    }

    @Test
    void weaponDefensiveCopyOnRead() {
        GunKillEvent event = newEvent();
        ItemStack a = event.getWeapon();
        ItemStack b = event.getWeapon();
        assertNotSame(a, b, "each getWeapon() call must return a fresh copy");
        a.setCount(99);
        assertEquals(1, b.getCount());
    }

    @Test
    void distanceMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, -1.0f, false, level, BlockPos.ZERO));
    }

    @Test
    void zeroDistanceIsAllowed() {
        assertDoesNotThrow(() ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, 0.0f, false, level, BlockPos.ZERO));
    }

    @Test
    void nullPlayerThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, null, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullLevelThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, null, BlockPos.ZERO));
    }

    @Test
    void nullWeaponIdThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, null, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullWeaponThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, null, TARGET_ID, UUID.randomUUID(),
                        TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullTargetIdThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        null, UUID.randomUUID(), TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullTargetUuidThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, null, TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullTierThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), null, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullEventIdThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(null, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, level, BlockPos.ZERO));
    }

    @Test
    void nullPositionThrows() {
        assertThrows(NullPointerException.class, () ->
                new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                        TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, level, null));
    }

    @Test
    void positionIsImmutable() {
        GunKillEvent event = new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                TARGET_ID, UUID.randomUUID(), TIER, DISTANCE, false, level, new BlockPos(1, 2, 3));
        BlockPos pos = event.getPosition();
        assertEquals(pos, event.getPosition());
    }

    @Test
    void tierAccessors() {
        assertEquals(GunTargetTier.COMMON, newEventWithTier(GunTargetTier.COMMON).getTargetTier());
        assertEquals(GunTargetTier.ELITE, newEventWithTier(GunTargetTier.ELITE).getTargetTier());
        assertEquals(GunTargetTier.HEAVY, newEventWithTier(GunTargetTier.HEAVY).getTargetTier());
        assertEquals(GunTargetTier.BOSS, newEventWithTier(GunTargetTier.BOSS).getTargetTier());
    }

    private GunKillEvent newEventWithTier(GunTargetTier tier) {
        return new GunKillEvent(eventId, player, WEAPON_ID, new ItemStack(Items.DIAMOND_SWORD),
                TARGET_ID, UUID.randomUUID(), tier, DISTANCE, false, level, BlockPos.ZERO);
    }

    @Test
    void targetUuidIsStored() {
        UUID targetUuid = UUID.randomUUID();
        GunKillEvent event = newEvent(new ItemStack(Items.DIAMOND_SWORD), targetUuid, false);
        assertEquals(targetUuid, event.getTargetUuid());
    }

    @Test
    void automatedFlagStoredCorrectly() {
        GunKillEvent manual = newEvent(new ItemStack(Items.DIAMOND_SWORD), UUID.randomUUID(), false);
        GunKillEvent automated = newEvent(new ItemStack(Items.DIAMOND_SWORD), UUID.randomUUID(), true);
        assertEquals(false, manual.isAutomated());
        assertEquals(true, automated.isAutomated());
    }

    @Test
    void accessorsReturnExpectedValues() {
        GunKillEvent event = newEvent();
        assertEquals(WEAPON_ID, event.getWeaponId());
        assertEquals(TARGET_ID, event.getTargetId());
        assertEquals(DISTANCE, event.getDistance());
        assertEquals(player, event.getPlayer());
        assertEquals(level, event.getLevel());
    }
}
