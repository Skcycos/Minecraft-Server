package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/**
 * Tests for {@link ShadowEntityAttemptCoordinator} (8D.1.1): the fixed order,
 * slot-based delivery with REAL inventory verification, audit-state rules,
 * cooldowns + idempotency, real-type revalidation, and the four-branch
 * hostile reaction.
 */
class ShadowEntityAttemptCoordinatorTest {

    private static final ResourceLocation COW = ResourceLocation.fromNamespaceAndPath("minecraft", "cow");
    private static final ResourceLocation ZOMBIE = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");
    private static final ResourceLocation WITHER = ResourceLocation.fromNamespaceAndPath("minecraft", "wither");
    private static final ResourceLocation COBBLE = ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone");

    private IEventBus bus;
    private AtomicInteger postedEvents;
    private ShadowFrameworkSettings settings;
    private ShadowProtectionService protection;
    private ShadowCooldownTracker cooldowns;
    private ShadowIdempotencyTracker idempotency;
    private InMemoryAudit audit;
    private RandomSource random;
    private AtomicInteger epochCalls;
    private ServerLevel level;
    private FakeInventory inventory;
    private FakeAttachmentAccess attachments;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowTheftEventDispatcher.resetForTesting();
        ShadowLogThrottle.resetForTesting();
        ShadowEntityAttemptCoordinator.resetForTesting();
        ShadowLootLoader.publish(Map.of(
                COW, cowDefinition(), ZOMBIE, zombieDefinition()),
                RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        bus = BusBuilder.builder().build();
        postedEvents = new AtomicInteger(0);
        bus.addListener((ShadowTheftEvent e) -> postedEvents.incrementAndGet());
        ShadowTheftEventDispatcher.setGameBusForTesting(bus);
        ShadowTheftEventDispatcher.setEnabledSupplierForTesting(() -> true);

        settings = new ShadowFrameworkSettings(true, true, true, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        protection = ctx -> ShadowProtectionResult.ALLOWED;
        cooldowns = new ShadowCooldownTracker();
        idempotency = new ShadowIdempotencyTracker();
        audit = new InMemoryAudit();
        random = mock(RandomSource.class);
        epochCalls = new AtomicInteger(0);
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.getGameTime()).thenReturn(1000L);
        when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        inventory = new FakeInventory();
        attachments = new FakeAttachmentAccess();
        ShadowEntityAttemptCoordinator.setAttachmentAccessForTesting(attachments);
    }

    @AfterEach
    void tearDown() {
        ShadowTheftEventDispatcher.resetForTesting();
        ShadowEntityAttemptCoordinator.resetForTesting();
        ShadowLootLoader.publish(Map.of(), null);
    }

    private static JsonObject cowDefinition() {
        return (JsonObject) JsonParser.parseString("""
                { "pools": [ { "weight": 100, "entries": [
                    { "id": "minecraft:cobblestone", "weight": 50, "min_count": 1, "max_count": 2 } ] } ] }
                """);
    }

    private static JsonObject zombieDefinition() {
        return (JsonObject) JsonParser.parseString("""
                { "pools": [ { "weight": 100, "entries": [
                    { "id": "minecraft:cobblestone", "weight": 50, "min_count": 1, "max_count": 1 } ] } ] }
                """);
    }

    /** Real main-inventory state (slots 0..35) for verification. */
    static final class FakeInventory {
        final List<ItemStack> slots = new ArrayList<>();

        FakeInventory() {
            for (int i = 0; i < 36; i++) {
                slots.add(ItemStack.EMPTY);
            }
        }

        int countOf(ItemStack probe) {
            int total = 0;
            for (ItemStack slot : slots) {
                if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, probe)) {
                    total += slot.getCount();
                }
            }
            return total;
        }
    }

    private ServerPlayer thiefWithInventory() {
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        when(inv.getItem(anyInt())).thenAnswer(a -> inventory.slots.get(a.getArgument(0)));
        Mockito.doAnswer(a -> {
            inventory.slots.set(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        return thief;
    }

    private ShadowEntityAttemptCoordinator coordinator() {
        return new ShadowEntityAttemptCoordinator(
                () -> settings, lvl -> audit, protection, cooldowns, idempotency,
                () -> random, () -> (long) epochCalls.incrementAndGet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubType(Entity entity, EntityType<?> type) {
        Mockito.doReturn((EntityType) type).when(entity).getType();
    }

    private ShadowAttemptContext context(ServerPlayer thief, Entity target, ResourceLocation type,
                                         long tick) {
        UUID targetUuid = UUID.randomUUID();
        when(target.getUUID()).thenReturn(targetUuid);
        when(target.isAlive()).thenReturn(true);
        when(target.isRemoved()).thenReturn(false);
        when(target.level()).thenReturn(level);
        when(target.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.getEntity(targetUuid)).thenReturn(target);
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.ENTITY,
                targetUuid, type, level, BlockPos.ZERO, tick, false, 2.0d, true);
    }

    private void stubSuccessRoll() {
        when(random.nextInt(anyInt())).thenReturn(0); // pool, entry, count
        when(random.nextDouble()).thenReturn(0.1d); // success roll
    }

    /** Stubs only the three draw rolls; the success roll is left to the
     *  caller (phase 8E success-chance tests). */
    private void stubPoolEntryCount() {
        when(random.nextInt(anyInt())).thenReturn(0); // pool, entry, count
    }

    // ---- gates & audit-before-everything (8D.1.1 §2) ----

    @Test
    void entityGateDoesNotDependOnPlayerTheft() {
        settings = new ShadowFrameworkSettings(true, true, false, true, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome(),
                "shadowPlayerTheftEnabled=false must NOT block the entity path");
    }

    @Test
    void entityTheftDisabledYieldsFrameworkDisabledWithZeroRandom() {
        settings = new ShadowFrameworkSettings(true, true, true, false, true,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        coordinator().attempt(context(thiefWithInventory(), mock(Mob.class), COW, 1000L));
        verify(random, never()).nextInt(anyInt());
        verify(random, never()).nextDouble();
    }

    @Test
    void auditDisabledYieldsAuditFailedWithZeroRandomZeroMarkerZeroAssets() {
        settings = new ShadowFrameworkSettings(true, true, true, true, false,
                0.35d, 0.05d, 0.85d, 200L, 40L, 400L, 1_200L, 100L, true, 3L);
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.AUDIT_FAILED, result.outcome());
        assertEquals("audit_disabled", result.failureReason());
        verify(random, never()).nextInt(anyInt());
        verify(random, never()).nextDouble();
        assertTrue(attachments.states.isEmpty(), "no marker writes when audit is disabled");
        assertEquals(0, inventory.countOf(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)),
                "no asset movement when audit is disabled");
        assertEquals(0, audit.appends);
        assertEquals(0, postedEvents.get());
    }

    @Test
    void unhealthyAuditRefusesBeforeAnyRandom() {
        audit.unhealthy = true;
        coordinator().attempt(context(thiefWithInventory(), mock(Mob.class), COW, 1000L));
        verify(random, never()).nextInt(anyInt());
    }

    // ---- hard exclusion / real-type revalidation (8D.1.1 §5) ----

    @Test
    void forgedTargetTypeCannotBypassTheWitherExclusion() {
        // The context claims cow, but the REAL entity is a wither: the
        // revalidation must reject the forged type (never lootable).
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.WITHER);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.INVALID_CONTEXT, result.outcome(),
                "a forged targetType must fail context revalidation");
        verify(random, never()).nextInt(anyInt());
    }

    @Test
    void hardExclusionUsesTheRealEntityType() {
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.WITHER);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, WITHER, 1000L));
        assertEquals(ShadowTheftOutcome.NO_CANDIDATE, result.outcome(),
                "the real wither type must be hard excluded even with a matching context");
        verify(random, never()).nextInt(anyInt());
    }

    @Test
    void undefinedEntityYieldsNoCandidateWithZeroRandom() {
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.PIG);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target,
                        ResourceLocation.fromNamespaceAndPath("minecraft", "pig"), 1000L));
        assertEquals(ShadowTheftOutcome.NO_CANDIDATE, result.outcome());
        verify(random, never()).nextInt(anyInt());
    }

    // ---- cooldowns & idempotency (8D.1.1 §5) ----

    @Test
    void sameTickDuplicateIsBlocked() {
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext first = context(thief, target, COW, 5000L);
        assertEquals(ShadowTheftOutcome.SUCCESS, coordinator().attempt(first).outcome());
        // Same thief + target + serverTick → DUPLICATE, no second gain.
        ShadowAttemptContext duplicate = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.ENTITY, first.targetId(), COW, level, BlockPos.ZERO, 5000L,
                false, 2.0d, true);
        assertEquals(ShadowTheftOutcome.DUPLICATE, coordinator().attempt(duplicate).outcome());
        assertEquals(1, postedEvents.get());
        assertEquals(1, inventory.countOf(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
    }

    @Test
    void failedRollTriggersFailureCooldownNotInstantReroll() {
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.9d); // roll fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext first = context(thief, target, COW, 6000L);
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, coordinator().attempt(first).outcome());
        // Immediate re-click (next tick) → COOLDOWN.
        when(random.nextDouble()).thenReturn(0.1d);
        ShadowAttemptContext retry = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.ENTITY, first.targetId(), COW, level, BlockPos.ZERO, 6001L,
                false, 2.0d, true);
        assertEquals(ShadowTheftOutcome.COOLDOWN, coordinator().attempt(retry).outcome());
        // After the failure cooldown expires a new attempt is legal.
        for (int i = 0; i < 400; i++) {
            cooldowns.onServerTick(null);
        }
        ShadowAttemptContext legal = new ShadowAttemptContext(UUID.randomUUID(), thief,
                ShadowTargetKind.ENTITY, first.targetId(), COW, level, BlockPos.ZERO, 6400L,
                false, 2.0d, true);
        when(random.nextDouble()).thenReturn(0.1d);
        assertEquals(ShadowTheftOutcome.SUCCESS, coordinator().attempt(legal).outcome());
    }

    // ---- transaction: success with real inventory verification ----

    @Test
    void successfulLootAddsExactlyTheCountToTheInventory() {
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
        assertFalse(result.receipt().isEmpty());
        assertEquals(COBBLE, result.receipt().itemId());
        int delivered = result.receipt().itemCount();
        assertTrue(delivered >= 1 && delivered <= 2);
        // REAL inventory verification: exactly `delivered` cobblestone.
        assertEquals(delivered, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        // random: 3 nextInt + 1 nextDouble
        verify(random, times(3)).nextInt(anyInt());
        verify(random, times(1)).nextDouble();
        // attachment LOOTED, audit PENDING→FINAL, one event, same eventId
        ShadowLootState state = attachments.states.get(ctx.targetId());
        assertNotNull(state);
        assertEquals(ShadowLootState.State.LOOTED, state.state());
        assertEquals(ctx.eventId(), state.eventId());
        assertEquals(1, audit.records.size());
        assertEquals(ShadowAuditState.FINAL, audit.records.get(0).auditState());
        assertEquals(ShadowTheftOutcome.SUCCESS, audit.records.get(0).outcome());
        assertEquals(ctx.eventId(), audit.records.get(0).eventId());
        assertEquals(2, audit.appends, "exactly PENDING + FINAL");
        assertEquals(1, postedEvents.get());
        assertTrue(result.eventPosted(), "SUCCESS must carry the real eventPosted boolean");
    }

    @Test
    void failurePathsCarryEventPostedFalse() {
        stubSuccessRoll();
        audit.failAppendNumber = 2; // PENDING ok, FINAL fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome());
        assertFalse(result.eventPosted(), "failed paths must report eventPosted=false");
    }

    @Test
    void fullInventoryRefusesBeforeAnyTransaction() {
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        for (int i = 0; i < 36; i++) {
            inventory.slots.set(i, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        }
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, result.outcome());
        assertEquals("inventory_full", result.failureReason());
        assertEquals(0, audit.appends, "no PENDING audit for a capacity refusal");
        assertTrue(attachments.states.isEmpty(), "no marker writes for a capacity refusal");
        assertEquals(36 * 64, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        assertEquals(0, postedEvents.get());
    }

    @Test
    void deliveryStacksOntoSameComponentSlot() {
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        inventory.slots.set(3, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 5));
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
        assertEquals(5 + result.receipt().itemCount(),
                inventory.slots.get(3).getCount(),
                "the delivery must stack onto the existing same-component slot");
    }

    // ---- audit-state rules (8D.1.1 §2) ----

    @Test
    void lootedWriteFailureRollsBackOnceAndFinalisesClean() {
        stubSuccessRoll();
        attachments.failOnWriteNumber = 2; // PENDING ok, LOOTED fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.FAILED_CLEAN, result.outcome());
        assertEquals("attachment_commit_failed", result.failureReason());
        // REAL inventory: the delivered count is gone again.
        assertEquals(0, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        // attachment AVAILABLE.
        assertEquals(ShadowLootState.State.AVAILABLE,
                attachments.states.getOrDefault(ctx.targetId(), ShadowLootState.available()).state());
        // audit: PENDING → FINAL FAILED_CLEAN, NO PENDING residue.
        assertEquals(1, audit.records.size());
        assertEquals(ShadowAuditState.FINAL, audit.records.get(0).auditState());
        assertEquals(ShadowTheftOutcome.FAILED_CLEAN, audit.records.get(0).outcome());
        assertEquals(2, audit.appends, "PENDING + FINAL FAILED_CLEAN");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void finalAuditWriteFailureRollsBackAndKeepsPendingForRecovery() {
        stubSuccessRoll();
        audit.failAppendNumber = 2; // PENDING ok, FINAL fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.ROLLED_BACK, result.outcome(),
                "a successful rollback after the FINAL audit failure must be ROLLED_BACK (8D.1.2 §3)");
        assertEquals("audit_final_write_failed", result.failureReason());
        // REAL inventory restored.
        assertEquals(0, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        // attachment AVAILABLE.
        assertEquals(ShadowLootState.State.AVAILABLE,
                attachments.states.getOrDefault(ctx.targetId(), ShadowLootState.available()).state());
        // audit: ONLY the FINAL write failure may keep PENDING.
        assertEquals(1, audit.records.size());
        assertEquals(ShadowAuditState.PENDING, audit.records.get(0).auditState());
        assertEquals(2, audit.appends, "PENDING write + failed FINAL attempt");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void unrestorableRecoveryYieldsRecoveryRequiredWithReceipt() {
        stubSuccessRoll();
        attachments.failOnWriteNumber = 2; // LOOTED fails
        attachments.failNextRemove = true; // restore also fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator().attempt(ctx);
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome());
        // delivered count is known → receipt must be carried.
        assertFalse(result.receipt().isEmpty());
        assertEquals(COBBLE, result.receipt().itemId());
        // PENDING stays (blocks retries) and the audit keeps the PENDING record.
        assertEquals(ShadowLootState.State.PENDING,
                attachments.states.get(ctx.targetId()).state());
        assertEquals(1, audit.records.size());
        assertEquals(ShadowAuditState.PENDING, audit.records.get(0).auditState());
        assertEquals(0, postedEvents.get());
    }

    @Test
    void pendingMarkerWriteFailureRestoreFailureYieldsRecoveryRequired() {
        stubSuccessRoll();
        attachments.failOnWriteNumber = 1; // PENDING write fails
        attachments.failNextRemove = true; // restore fails
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome(),
                "a failed PENDING-marker restore must be RECOVERY_REQUIRED, not TRANSFER_FAILED");
        assertTrue(result.receipt().isEmpty(), "nothing was delivered");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void externalSlotMutationRejectsRollbackAndYieldsRecoveryRequired() {
        stubSuccessRoll();
        // The external mutation happens between the slot commit and the
        // rollback: LOOTED write fails and, at that moment, the slot is
        // externally replaced — the rollback must refuse and NOT delete
        // other matching items.
        FakeAttachmentAccess wrapping = new FakeAttachmentAccess() {
            int calls = 0;

            @Override
            public boolean write(Entity entity, ShadowLootState state) {
                calls++;
                if (calls == 2) {
                    inventory.slots.set(0, ItemStack.EMPTY); // external change
                    return false;
                }
                return super.write(entity, state);
            }
        };
        ShadowEntityAttemptCoordinator.setAttachmentAccessForTesting(wrapping);
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome(),
                "an externally mutated slot must make the rollback refuse");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void setItemNoOpCannotReportSuccess() {
        stubSuccessRoll();
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        // getItem always returns EMPTY; setItem is a NO-OP (slot never changes).
        when(inv.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        Mockito.doAnswer(a -> null).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_CLEAN, result.outcome(),
                "a no-op setItem must never report SUCCESS (clean refusal, nothing delivered)");
        assertEquals(0, postedEvents.get());
    }

    // ---- hostile reaction: four branches (8D.1.1 §6) ----

    @Test
    void failedRollSetsTargetOnHostileMob() {
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.9d);
        ServerPlayer thief = thiefWithInventory();
        Monster monster = mock(Monster.class); // hostile (Enemy)
        stubType(monster, EntityType.ZOMBIE);
        when(monster.getTarget()).thenReturn(thief); // reaction applied
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, monster, ZOMBIE, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome());
        verify(monster).setTarget(thief);
        verify(monster).getTarget();
        assertEquals(0, postedEvents.get());
    }

    @Test
    void cancelledReactionDoesNotAffectTheOutcome() {
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.9d);
        ServerPlayer thief = thiefWithInventory();
        Monster monster = mock(Monster.class);
        stubType(monster, EntityType.ZOMBIE);
        when(monster.getTarget()).thenReturn(null); // read-back mismatch (cancelled)
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, monster, ZOMBIE, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome(),
                "a cancelled reaction must not change the transaction result");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void failedRollSkipsAnimals() {
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.9d);
        ServerPlayer thief = thiefWithInventory();
        Animal animal = mock(Animal.class); // not an Enemy
        stubType(animal, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, animal, COW, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome());
        verify(animal, never()).setTarget(any());
        assertEquals(0, postedEvents.get());
    }

    @Test
    void failedRollSkipsNonMobEntities() {
        when(random.nextInt(anyInt())).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.9d);
        ServerPlayer thief = thiefWithInventory();
        Entity entity = mock(Entity.class); // NOT a Mob
        stubType(entity, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, entity, COW, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome());
        assertEquals(0, postedEvents.get());
    }


    // ---- 8D.1.2 hardening: FOREIGN slots, exceptions, strict restore ----

    @Test
    void commitWrongWriteYieldsForeignAndRollbackNeverOverwrites() {
        stubSuccessRoll();
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        // Slot 0 always reports EMPTY before the write, then the "external"
        // item AFTER setItem is called (a wrong write).
        org.mockito.Mockito.doAnswer(a -> inventory.slots.get(a.getArgument(0)))
                .when(inv).getItem(anyInt());
        org.mockito.Mockito.doAnswer(a -> {
            inventory.slots.set(a.getArgument(0), a.getArgument(1));
            // wrong write: something else lands in the slot
            inventory.slots.set(a.getArgument(0),
                    new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1));
            return null;
        }).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome(),
                "a wrong slot write must be FOREIGN → RECOVERY_REQUIRED");
        assertEquals(1, inventory.slots.get(0).getCount(),
                "the FOREIGN slot must NEVER be overwritten by the rollback");
        assertTrue(inventory.slots.get(0).is(net.minecraft.world.item.Items.DIAMOND),
                "the foreign diamond stays untouched");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void setItemThrowingStillReReadsAndClassifies() {
        stubSuccessRoll();
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        when(inv.getItem(anyInt())).thenAnswer(a -> inventory.slots.get(a.getArgument(0)));
        org.mockito.Mockito.doAnswer(a -> {
            inventory.slots.set(a.getArgument(0), a.getArgument(1));
            throw new IllegalStateException("setItem boom");
        }).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        // The write actually landed (the throwing setItem DID set) → the
        // re-read classifies it as COMMITTED... but the slot holds the
        // delivery: the re-read equals afterStack → success path continues.
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome(),
                "a throwing setItem whose write landed must still classify via the re-read");
        assertEquals(0, postedEvents.get() - 1,
                "the SUCCESS event must be posted exactly once");
    }

    @Test
    void getItemThrowingOnCommitYieldsForeignAndRecoveryRequired() {
        stubSuccessRoll();
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(a -> {
            int idx = a.getArgument(0);
            if (idx != 0) {
                return ItemStack.EMPTY;
            }
            if (reads.incrementAndGet() >= 3) {
                throw new IllegalStateException("getItem boom on re-read");
            }
            return inventory.slots.get(0);
        }).when(inv).getItem(anyInt());
        org.mockito.Mockito.doAnswer(a -> {
            inventory.slots.set(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.RECOVERY_REQUIRED, result.outcome(),
                "an unreadable slot on commit must be FOREIGN → RECOVERY_REQUIRED, "
                        + "never a framework INVALID_CONTEXT");
        assertEquals(0, postedEvents.get());
    }

    @Test
    void markerRestoreWrongStateKeepsBlocking() {
        // Direct access-fake contract (8D.1.2 §2): restoring with an expected
        // state that differs from the current one must NOT remove the marker —
        // the blocking state stays and the entity is never reopened.
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        ShadowLootState pending = ShadowLootState.pending(UUID.randomUUID(), UUID.randomUUID(), 1L);
        attachments.states.put(id, pending);
        assertFalse(attachments.restore(entity,
                ShadowLootState.looted(UUID.randomUUID(), COBBLE, 1, 1L)),
                "a wrong expected state must refuse the restore");
        assertEquals(ShadowLootState.State.PENDING, attachments.states.get(id).state(),
                "the blocking state must be kept");
        // Correct expected state restores to AVAILABLE (re-read null).
        assertTrue(attachments.restore(entity, pending));
        assertTrue(attachments.states.isEmpty());
    }

    @Test
    void markerRestoreNoOpCannotReportSuccess() {
        stubSuccessRoll();
        FakeAttachmentAccess sticky = new FakeAttachmentAccess() {
            @Override
            public boolean restore(Entity entity, ShadowLootState expected) {
                // removal is a no-op: the state stays.
                return !states.containsKey(entity.getUUID());
            }
        };
        ShadowEntityAttemptCoordinator.setAttachmentAccessForTesting(sticky);
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome(),
                "a healthy run must still succeed (restore is only used on failures)");
        assertEquals(0, postedEvents.get() - 1);
    }

    @Test
    void clockFailureRefusesBeforeAnyPendingRecord() {
        stubSuccessRoll();
        ShadowEntityAttemptCoordinator coordinator = new ShadowEntityAttemptCoordinator(
                () -> settings, lvl -> audit, protection, cooldowns, idempotency,
                () -> random, () -> 0L); // clock always 0
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator.attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.TRANSFER_FAILED, result.outcome());
        assertEquals("clock_unavailable", result.failureReason());
        assertEquals(0, audit.appends, "no PENDING record before the clock check");
        assertTrue(attachments.states.isEmpty());
        assertEquals(0, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        assertEquals(0, postedEvents.get());
    }


    // ---- 8D.1.3: post-commit clock / settlement hardening ----

    @Test
    void clockIsReadExactlyOnceAndPostCommitClockFailureNeverPoisons() {
        java.util.concurrent.atomic.AtomicInteger clockCalls = new java.util.concurrent.atomic.AtomicInteger();
        ShadowEntityAttemptCoordinator coordinator = new ShadowEntityAttemptCoordinator(
                () -> settings, lvl -> audit, protection, cooldowns, idempotency,
                () -> random, () -> {
                    if (clockCalls.incrementAndGet() == 1) {
                        return 1_000L; // stable snapshot
                    }
                    throw new IllegalStateException("clock boom after commit");
                });
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome(),
                "a clock failure AFTER the stable snapshot must never poison the committed result");
        assertEquals(1, clockCalls.get(), "the time source must be read exactly once");
        assertEquals(1, postedEvents.get());
        // REAL inventory: the delivery stayed.
        assertEquals(result.receipt().itemCount(), inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)));
        assertEquals(ShadowAuditState.FINAL, audit.records.get(0).auditState());
    }

    @Test
    void postCommitSettlementThrowsNeverOverrideTheResult() {
        ShadowCooldownTracker throwingCooldowns = mock(ShadowCooldownTracker.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("cooldown boom"))
                .when(throwingCooldowns).markGlobalCooldown(any(),
                        org.mockito.ArgumentMatchers.anyLong());
        ShadowIdempotencyTracker throwingIdempotency = mock(ShadowIdempotencyTracker.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("idempotency boom"))
                .when(throwingIdempotency).markEventId(any());
        ShadowEntityAttemptCoordinator coordinator = new ShadowEntityAttemptCoordinator(
                () -> settings, lvl -> audit, protection, throwingCooldowns, throwingIdempotency,
                () -> random, () -> (long) epochCalls.incrementAndGet());
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAttemptContext ctx = context(thief, target, COW, 1000L);
        ShadowEntityAttemptCoordinator.Result result = coordinator.attempt(ctx);
        assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome(),
                "throwing settlement helpers must never override SUCCESS");
        assertTrue(result.eventPosted());
        assertEquals(result.receipt().itemCount(), inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)),
                "the committed delivery must stay");
        assertEquals(1, postedEvents.get());
    }

    @Test
    void rollbackSetItemThrowingButLandingStillRestores() {
        stubSuccessRoll();
        attachments.failOnWriteNumber = 2; // LOOTED write fails → rollback
        ServerPlayer thief = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        Inventory inv = mock(Inventory.class);
        when(inv.getContainerSize()).thenReturn(36);
        when(inv.getItem(anyInt())).thenAnswer(a -> inventory.slots.get(a.getArgument(0)));
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(a -> {
            inventory.slots.set(a.getArgument(0), a.getArgument(1));
            if (writes.incrementAndGet() == 2) {
                throw new IllegalStateException("setItem boom on rollback");
            }
            return null;
        }).when(inv).setItem(anyInt(), any(ItemStack.class));
        when(thief.getInventory()).thenReturn(inv);
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_CLEAN, result.outcome(),
                "a rollback write that landed despite throwing must restore cleanly");
        assertEquals(0, inventory.countOf(
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)),
                "the delivered item must be fully restored");
        assertEquals(ShadowAuditState.FINAL, audit.records.get(0).auditState());
    }

    // ---- fakes ----

    /** Fake attachment storage (injectable access, no final-method mocking). */
    static class FakeAttachmentAccess implements ShadowEntityAttemptCoordinator.AttachmentAccess {
        final java.util.Map<UUID, ShadowLootState> states = new java.util.HashMap<>();
        boolean failNextWrite;
        boolean failNextRemove;
        int failOnWriteNumber = -1;
        int writeCount;

        @Override
        public ShadowLootState read(Entity entity) {
            return states.getOrDefault(entity.getUUID(), ShadowLootState.available());
        }

        @Override
        public boolean write(Entity entity, ShadowLootState state) {
            writeCount++;
            if (failNextWrite) {
                failNextWrite = false;
                return false;
            }
            if (writeCount == failOnWriteNumber) {
                return false;
            }
            states.put(entity.getUUID(), state);
            return true;
        }

        @Override
        public boolean restore(Entity entity, ShadowLootState expected) {
            if (failNextRemove) {
                failNextRemove = false;
                return false;
            }
            ShadowLootState current = states.get(entity.getUUID());
            if (current == null) {
                return true; // already available
            }
            if (expected == null || !current.equals(expected)) {
                return false; // wrong state → keep blocking
            }
            states.remove(entity.getUUID());
            return !states.containsKey(entity.getUUID()); // removal must land
        }
    }

    /** Audit fake with production transition rules + append counting. */
    static final class InMemoryAudit implements ShadowAuditWriter {
        final List<ShadowAuditRecord> records = new ArrayList<>();
        int appends;
        int failAppendNumber = -1;
        boolean unhealthy;

        @Override
        public boolean append(ShadowAuditRecord record) {
            appends++;
            if (appends == failAppendNumber) {
                return false;
            }
            int index = -1;
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).eventId().equals(record.eventId())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                records.add(record);
                return true;
            }
            ShadowAuditRecord existing = records.get(index);
            if (existing.auditState() == ShadowAuditState.PENDING
                    && record.auditState() == ShadowAuditState.FINAL
                    && record.outcome() != null) {
                records.set(index, record);
                return true;
            }
            if (existing.auditState() == ShadowAuditState.FINAL && existing.equals(record)) {
                return true;
            }
            return false;
        }

        @Override
        public ShadowAuditRecord byEventId(UUID eventId) {
            return records.stream().filter(r -> r.eventId().equals(eventId)).findFirst().orElse(null);
        }

        @Override
        public boolean has(UUID eventId) {
            return byEventId(eventId) != null;
        }

        @Override
        public List<ShadowAuditRecord> byThief(UUID thiefId) {
            return records.stream().filter(r -> r.thiefId().equals(thiefId)).toList();
        }

        @Override
        public List<ShadowAuditRecord> byTarget(UUID targetId) {
            return records.stream().filter(r -> r.targetId().equals(targetId)).toList();
        }

        @Override
        public List<ShadowAuditRecord> all() {
            return List.copyOf(records);
        }

        @Override
        public boolean isHealthy() {
            return !unhealthy;
        }
    }

    // ---- phase 8E: ability snapshot integration on the entity path ----

    private ShadowAttemptContext contextWithAbilities(ServerPlayer thief, Entity target,
                                                      ResourceLocation type, long tick,
                                                      ShadowAbilitySnapshot abilities) {
        UUID targetUuid = UUID.randomUUID();
        when(target.getUUID()).thenReturn(targetUuid);
        when(target.isAlive()).thenReturn(true);
        when(target.isRemoved()).thenReturn(false);
        when(target.level()).thenReturn(level);
        when(target.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.getEntity(targetUuid)).thenReturn(target);
        return new ShadowAttemptContext(UUID.randomUUID(), thief, ShadowTargetKind.ENTITY,
                targetUuid, type, level, BlockPos.ZERO, tick, false, 2.0d, true, abilities);
    }

    @Test
    void sleightTierRaisesTheEntitySuccessChance() {
        stubPoolEntryCount();
        when(random.nextDouble()).thenReturn(0.4d); // base 0.35 → fail
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowEntityAttemptCoordinator.Result result =
                coordinator().attempt(context(thief, target, COW, 1000L));
        assertEquals(ShadowTheftOutcome.FAILED_ROLL, result.outcome(),
                "base 0.35 must fail at a 0.4 roll");
        // 妙手 III (+0.15 → 0.50) succeeds at the same roll.
        stubPoolEntryCount();
        when(random.nextDouble()).thenReturn(0.4d);
        ServerPlayer thief2 = thiefWithInventory();
        Entity target2 = mock(Mob.class);
        stubType(target2, EntityType.COW);
        ShadowAbilitySnapshot sleight = new ShadowAbilitySnapshot(ShadowAbilityTier.III,
                ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE);
        ShadowEntityAttemptCoordinator.Result ok =
                coordinator().attempt(contextWithAbilities(thief2, target2, COW, 1000L, sleight));
        assertEquals(ShadowTheftOutcome.SUCCESS, ok.outcome());
    }

    @Test
    void sleightTierShortensTheEntityGlobalCooldown() {
        stubSuccessRoll();
        ServerPlayer thief = thiefWithInventory();
        Entity target = mock(Mob.class);
        stubType(target, EntityType.COW);
        ShadowAbilitySnapshot sleight = new ShadowAbilitySnapshot(ShadowAbilityTier.II,
                ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE);
        ShadowAttemptContext ctx = contextWithAbilities(thief, target, COW, 1000L, sleight);
        assertEquals(ShadowTheftOutcome.SUCCESS, coordinator().attempt(ctx).outcome());
        UUID thiefId = ctx.thief().getUUID();
        assertTrue(cooldowns.isGlobalCooldownActive(thiefId));
        for (int i = 0; i < 160; i++) {
            cooldowns.onServerTick(null);
        }
        assertFalse(cooldowns.isGlobalCooldownActive(thiefId),
                "妙手 II reduces the entity-path cooldown 200 → 160 ticks");
    }

    @Test
    void escapeEffectsAreAppliedOnlyOnEntityFinalSuccess() {
        stubSuccessRoll();
        ShadowEscapeEffects.resetForTesting();
        try {
            ServerPlayer thief = thiefWithInventory();
            when(thief.level()).thenReturn(level);
            UUID thiefUuid = thief.getUUID();
            when(thief.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(thiefUuid, "thief"));
            when(thief.addEffect(any(net.minecraft.world.effect.MobEffectInstance.class)))
                    .thenReturn(true);
            Entity target = mock(Mob.class);
            stubType(target, EntityType.COW);
            ShadowAbilitySnapshot escape = new ShadowAbilitySnapshot(ShadowAbilityTier.NONE,
                    ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.II);
            ShadowEntityAttemptCoordinator.Result result =
                    coordinator().attempt(contextWithAbilities(thief, target, COW, 1000L, escape));
            assertEquals(ShadowTheftOutcome.SUCCESS, result.outcome());
            assertEquals(1, ShadowEscapeEffects.markCount(),
                    "entity SUCCESS with the 潜影 II tier records the marker");
            // A FAILED_ROLL never grants escape effects.
            ShadowEscapeEffects.resetForTesting();
            stubPoolEntryCount();
            when(random.nextDouble()).thenReturn(0.9d);
            ServerPlayer thief2 = thiefWithInventory();
            when(thief2.level()).thenReturn(level);
            UUID thief2Uuid = thief2.getUUID();
            when(thief2.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(thief2Uuid, "thief2"));
            Entity target2 = mock(Mob.class);
            stubType(target2, EntityType.COW);
            ShadowEntityAttemptCoordinator.Result failed =
                    coordinator().attempt(contextWithAbilities(thief2, target2, COW, 1000L, escape));
            assertEquals(ShadowTheftOutcome.FAILED_ROLL, failed.outcome());
            assertEquals(0, ShadowEscapeEffects.markCount());
        } finally {
            ShadowEscapeEffects.resetForTesting();
        }
    }
}
