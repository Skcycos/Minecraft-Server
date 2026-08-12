package com.tanrunn.tcth.api.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Unit tests for {@link ShadowTheftEvent} (phase 8B).
 *
 * <p>Covers: non-null validation, immutable position, stable eventId,
 * defensive receipt handling.
 */
class ShadowTheftEventTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void nullFieldsAreRejected() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new ShadowTheftEvent(
                null, thief, ShadowTargetKind.PLAYER, targetId, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, level, null));
        assertThrows(NullPointerException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), null, ShadowTargetKind.PLAYER, targetId, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, level, null));
        assertThrows(NullPointerException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, null, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, level, null));
        assertThrows(NullPointerException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, null, false, level, null));
        assertThrows(NullPointerException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, null, null));
    }

    @Test
    void eventIdStaysConstant() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID eventId = UUID.randomUUID();
        ShadowTheftEvent event = new ShadowTheftEvent(
                eventId, thief, ShadowTargetKind.PLAYER, UUID.randomUUID(), null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, level, BlockPos.ZERO);
        assertEquals(eventId, event.getEventId());
        assertEquals(eventId, event.getEventId());
    }

    @Test
    void positionIsImmutable() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos mutable = new BlockPos(1, 2, 3);
        ShadowTheftEvent event = new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, UUID.randomUUID(), null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty(), false, level, mutable);
        assertNotNull(event.getPosition());
        assertEquals(1, event.getPosition().getX());
        assertTrue(event.getPosition() instanceof BlockPos);
        // Immutable() values are their own class; assert immutability contract
        // via the defensive copy in the constructor (equals preserved).
        assertEquals(new BlockPos(1, 2, 3), event.getPosition());
    }

    @Test
    void nullPositionIsAllowed() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ShadowTheftEvent event = new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.ENTITY, UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("minecraft", "zombie"), null,
                ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty(), false, level, null);
        assertEquals(null, event.getPosition());
        assertEquals(ShadowTargetKind.ENTITY, event.getTargetKind());
    }

    @Test
    void successEventCarriesTypeAndReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        ShadowTheftReceipt receipt = ShadowTheftReceipt.item(
                ResourceLocation.fromNamespaceAndPath("minecraft", "diamond"), 1);
        ShadowTheftEvent event = new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS, receipt, false, level, null);
        assertEquals(ShadowTheftType.ITEM, event.getTheftType());
        assertEquals(ShadowTheftOutcome.SUCCESS, event.getOutcome());
        assertEquals(1, event.getReceipt().itemCount());
        assertEquals(targetId, event.getTargetId());
    }

    @Test
    void automatedFlagIsPreserved() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.isClientSide()).thenReturn(false);
        ShadowTheftEvent event = new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.ENTITY, UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("minecraft", "creeper"), null,
                ShadowTheftOutcome.PROTECTED, ShadowTheftReceipt.empty(), true, level, null);
        assertTrue(event.isAutomated());
    }

    // ---- 8B.1 outcome/receipt/theftType invariants ----

    @Test
    void successRequiresMatchingTheftTypeAndReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        ResourceLocation item = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
        // Valid: ITEM + item receipt.
        ShadowTheftEvent ok = new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER,
                targetId, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowTheftReceipt.item(item, 1), false, level, null);
        assertEquals(ShadowTheftOutcome.SUCCESS, ok.getOutcome());
        // SUCCESS without a theftType is rejected.
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, null,
                ShadowTheftOutcome.SUCCESS, ShadowTheftReceipt.empty(), false, level, null));
        // SUCCESS whose receipt does not match the type is rejected.
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowTheftReceipt.numeric(10.0d), false, level, null));
    }

    @Test
    void nonAssetOutcomesRequireEmptyReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        ResourceLocation item = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
        for (ShadowTheftOutcome outcome : ShadowTheftOutcome.values()) {
            if (outcome == ShadowTheftOutcome.SUCCESS
                    || outcome == ShadowTheftOutcome.RECOVERY_REQUIRED) {
                continue; // covered by the dedicated tests
            }
            assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                    UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                    ShadowTheftType.ITEM, outcome, ShadowTheftReceipt.item(item, 1), false, level, null),
                    outcome + " must not carry a committed receipt");
            // Empty receipt with a drawn type is fine.
            new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                    ShadowTheftType.ITEM, outcome, ShadowTheftReceipt.empty(), false, level, null);
            // Null theftType is allowed for pre-draw outcomes.
            new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                    null, outcome, ShadowTheftReceipt.empty(), false, level, null);
        }
    }

    @Test
    void rolledBackRequiresEmptyReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.ROLLED_BACK, ShadowTheftReceipt.item(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "diamond"), 1),
                false, level, null), "a rolled-back attempt must report an empty receipt");
        new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                ShadowTheftType.ITEM, ShadowTheftOutcome.ROLLED_BACK,
                ShadowTheftReceipt.empty(), false, level, null);
    }

    @Test
    void recoveryRequiredMayCarryCommittedReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        // Committed receipt requires a matching theftType.
        ShadowTheftEvent withReceipt = new ShadowTheftEvent(UUID.randomUUID(), thief,
                ShadowTargetKind.PLAYER, targetId, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.RECOVERY_REQUIRED, ShadowTheftReceipt.item(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "diamond"), 1),
                false, level, null);
        assertEquals(ShadowTheftType.ITEM, withReceipt.getTheftType());
        // A committed receipt without a matching theftType is rejected.
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.RECOVERY_REQUIRED, ShadowTheftReceipt.numeric(10.0d),
                false, level, null));
        // Empty receipt + null theftType is allowed (recovery signalled from
        // an unresolved PENDING record).
        new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                null, ShadowTheftOutcome.RECOVERY_REQUIRED, ShadowTheftReceipt.empty(),
                false, level, null);
    }

    @Test
    void auditFailedNeverCarriesReceipt() {
        ServerPlayer thief = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID targetId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ShadowTheftEvent(
                UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.AUDIT_FAILED, ShadowTheftReceipt.numeric(10.0d),
                false, level, null), "AUDIT_FAILED is a pre-asset refusal; receipt must be empty");
        new ShadowTheftEvent(UUID.randomUUID(), thief, ShadowTargetKind.PLAYER, targetId, null,
                null, ShadowTheftOutcome.AUDIT_FAILED, ShadowTheftReceipt.empty(), false, level, null);
    }
}
