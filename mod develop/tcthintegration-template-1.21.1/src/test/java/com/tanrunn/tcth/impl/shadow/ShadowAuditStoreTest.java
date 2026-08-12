package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Unit tests for {@link ShadowAuditStore} (8B.1 schema v1).
 *
 * <p>Covers: save/load round-trip with the new schema (effectId, targetType,
 * epoch/serverTick separation, failureReason), upsert-by-eventId finalisation,
 * the bounded cap (append never evicts PENDING / RECOVERY_REQUIRED, 8C.2.4),
 * strict loading that fails CLOSED on invalid records / conflicting duplicate
 * eventIds / future versions, the persisted failClosed health flag, immutable
 * query results and the no-playerdata boundary.
 */
class ShadowAuditStoreTest {

    private static final ResourceLocation DIM = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation ITEM = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
    private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath("minecraft", "speed");
    private static final ResourceLocation ZOMBIE = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private static HolderLookup.Provider provider() {
        MinecraftTestBootstrap.bootStrap();
        return HolderLookup.Provider.create(Stream.empty());
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private ShadowAuditRecord record() {
        return new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
    }

    private ShadowAuditRecord effectRecord() {
        return new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, null, 0, 0.0d, EFFECT, 200, 1_000L, 5_000L, DIM, null, null);
    }

    private ShadowAuditRecord entityRecord() {
        return new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ShadowTargetKind.ENTITY, ZOMBIE, null, ShadowTheftOutcome.NO_CANDIDATE,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
    }

    @Test
    void saveLoadRoundTripPreservesRecords() {
        ShadowAuditStore store = new ShadowAuditStore();
        ShadowAuditRecord r1 = record();
        ShadowAuditRecord r2 = effectRecord();
        ShadowAuditRecord r3 = entityRecord();
        assertTrue(store.append(r1));
        assertTrue(store.append(r2));
        assertTrue(store.append(r3));

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());

        assertEquals(3, loaded.all().size());
        assertTrue(loaded.has(r1.eventId()));
        assertEquals(ShadowTheftType.ITEM, loaded.byEventId(r1.eventId()).theftType());
        assertEquals(1, loaded.byEventId(r1.eventId()).itemCount());
        // effectId must survive the round-trip (8B.1 §3.1).
        assertEquals(EFFECT, loaded.byEventId(r2.eventId()).effectId());
        assertEquals(200, loaded.byEventId(r2.eventId()).effectDurationTicks());
        // targetType must survive for ENTITY targets (8B.1 §3.2).
        assertEquals(ZOMBIE, loaded.byEventId(r3.eventId()).targetType());
        // epoch and serverTick stay separate.
        assertEquals(1_000L, loaded.byEventId(r1.eventId()).timestampEpochMillis());
        assertEquals(5_000L, loaded.byEventId(r1.eventId()).serverTick());
        assertEquals(new BlockPos(1, 2, 3), loaded.byEventId(r1.eventId()).position());
    }

    @Test
    void upsertByEventIdFinalisesPendingInPlace() {
        ShadowAuditStore store = new ShadowAuditStore();
        UUID eventId = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ShadowAuditRecord pending = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
        assertTrue(store.append(pending));
        ShadowAuditRecord finalRecord = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
        assertTrue(store.append(finalRecord));
        assertEquals(1, store.all().size(), "upsert must not grow the log");
        assertEquals(ShadowAuditState.FINAL, store.byEventId(eventId).auditState());
        assertEquals(ShadowTheftOutcome.SUCCESS, store.byEventId(eventId).outcome());
    }

    @Test
    void stateTransitionsAreRestricted() {
        ShadowAuditStore store = new ShadowAuditStore();
        UUID eventId = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // New eventId: PENDING and FINAL inserts are both allowed.
        ShadowAuditRecord pending = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
        assertTrue(store.append(pending), "a new eventId accepts a PENDING insert");
        assertEquals(1, store.all().size());
        // PENDING → PENDING is illegal.
        ShadowAuditRecord pending2 = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.HEALTH, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
        assertFalse(store.append(pending2), "PENDING → PENDING must be refused");
        assertEquals(1, store.all().size());
        assertEquals(ShadowTheftType.ITEM, store.byEventId(eventId).theftType(),
                "the original record must be preserved");
        // PENDING → FINAL is the allowed finalisation.
        ShadowAuditRecord finalRecord = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, null);
        assertTrue(store.append(finalRecord), "PENDING → FINAL is allowed");
        // FINAL → byte-identical record is an idempotent re-write.
        assertTrue(store.append(finalRecord), "an identical FINAL re-write is idempotent");
        assertEquals(1, store.all().size());
        // FINAL → different FINAL is illegal.
        ShadowAuditRecord differentFinal = new ShadowAuditRecord(eventId, thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.ROLLED_BACK,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, null, "x");
        assertFalse(store.append(differentFinal), "FINAL → different FINAL must be refused");
        assertEquals(ShadowTheftOutcome.SUCCESS, store.byEventId(eventId).outcome(),
                "the original FINAL record must be preserved");
        // FINAL → PENDING is illegal.
        assertFalse(store.append(pending), "FINAL → PENDING must be refused");
        assertEquals(ShadowTheftOutcome.SUCCESS, store.byEventId(eventId).outcome());
    }

    @Test
    void finalInsertWithoutPendingIsAllowed() {
        ShadowAuditStore store = new ShadowAuditStore();
        ShadowAuditRecord finalRecord = record(); // FINAL SUCCESS item
        assertTrue(store.append(finalRecord), "a new eventId accepts a FINAL insert");
        assertEquals(1, store.all().size());
    }

    @Test
    void nonAssetEffectRecordsAreLegal() {
        // 8B.1.1 §2: PENDING / FAILED_ROLL / TRANSFER_FAILED records may carry
        // theftType EFFECT with effectId == null (no asset was moved).
        ShadowAuditStore store = new ShadowAuditStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        ShadowAuditRecord pending = new ShadowAuditRecord(UUID.randomUUID(), thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null);
        assertTrue(store.append(pending), "a PENDING EFFECT record needs no effectId");
        ShadowAuditRecord failedRoll = new ShadowAuditRecord(UUID.randomUUID(), thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT, ShadowTheftOutcome.FAILED_ROLL,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null);
        assertTrue(store.append(failedRoll), "a FAILED_ROLL EFFECT record needs no effectId");
        ShadowAuditRecord transferFailed = new ShadowAuditRecord(UUID.randomUUID(), thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT, ShadowTheftOutcome.TRANSFER_FAILED,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, "prepare_failed");
        assertTrue(store.append(transferFailed), "a TRANSFER_FAILED EFFECT record needs no effectId");
        // SUCCESS EFFECT still requires the effectId.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                UUID.randomUUID(), thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
    }

    @Test
    void pendingToFinalCannotSwapTheAuditSubject() {
        // 8C.0 §5: PENDING → FINAL must keep every identity field identical;
        // a FINAL record may not replace the thief, target, kind, type,
        // dimension, position or serverTick through the same eventId.
        ShadowAuditStore store = new ShadowAuditStore();
        UUID eventId = UUID.randomUUID();
        UUID thiefA = UUID.randomUUID();
        UUID targetA = UUID.randomUUID();
        ShadowAuditRecord pending = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1_000L, 5_000L, DIM, new BlockPos(1, 2, 3), null);
        assertTrue(store.append(pending));

        // Swapped thief.
        ShadowAuditRecord otherThief = new ShadowAuditRecord(eventId, UUID.randomUUID(), targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(otherThief), "the thief identity must not be swappable");
        assertEquals(ShadowAuditState.PENDING, store.byEventId(eventId).auditState(),
                "the original PENDING record must be preserved");

        // Swapped target.
        ShadowAuditRecord otherTarget = new ShadowAuditRecord(eventId, thiefA, UUID.randomUUID(),
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(otherTarget));

        // Swapped targetKind + targetType (ENTITY).
        ShadowAuditRecord entitySwap = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.ENTITY, ZOMBIE, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(entitySwap));

        // Swapped theftType.
        ShadowAuditRecord otherType = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.HEALTH, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, null, 0, 10.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(otherType));

        // Swapped dimension.
        ShadowAuditRecord otherDim = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(otherDim));

        // Swapped position.
        ShadowAuditRecord otherPos = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(9, 9, 9), null);
        assertFalse(store.append(otherPos));

        // Swapped serverTick.
        ShadowAuditRecord otherTick = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 9_999L, DIM,
                new BlockPos(1, 2, 3), null);
        assertFalse(store.append(otherTick));

        // Identical identity + outcome is still allowed.
        ShadowAuditRecord sameIdentity = new ShadowAuditRecord(eventId, thiefA, targetA,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L, 5_000L, DIM,
                new BlockPos(1, 2, 3), null);
        assertTrue(store.append(sameIdentity), "the legitimate PENDING → FINAL transition still works");
        assertEquals(ShadowTheftOutcome.SUCCESS, store.byEventId(eventId).outcome());
    }

    @Test
    void dataVersionIsStored() {
        ShadowAuditStore store = new ShadowAuditStore();
        store.append(record());
        CompoundTag tag = store.save(new CompoundTag(), provider());
        assertEquals(ShadowAuditStore.DATA_VERSION, tag.getInt("dataVersion"));
    }

    @Test
    void unknownFutureVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 99);
        ListTag list = new ListTag();
        list.add(storeRecordToTag(record()));
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(),
                "a future version must fail closed — never an empty store that allows theft");
        assertFalse(loaded.append(record()), "a fail-closed store refuses every append");
    }

    @Test
    void negativeVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", -1);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a negative version must fail closed");
    }

    @Test
    void invalidResourceLocationsFailClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag badItem = storeRecordToTag(record());
        badItem.putString("itemId", "minecraft:..\\..\\evil");
        list.add(badItem);
        CompoundTag badDimension = storeRecordToTag(record());
        badDimension.putString("dimension", "not a valid id with spaces");
        list.add(badDimension);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(),
                "an invalid record is storage damage — fail closed, never silently dropped");
    }

    @Test
    void unknownNonNullEnumsFailClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag badTheftType = storeRecordToTag(record());
        badTheftType.putString("theftType", "HYPOTHETICAL");
        list.add(badTheftType);
        CompoundTag badAuditState = storeRecordToTag(record());
        badAuditState.putString("auditState", "NOT_A_STATE");
        list.add(badAuditState);
        CompoundTag missingOutcomeOnFinal = storeRecordToTag(record());
        missingOutcomeOnFinal.remove("outcome"); // FINAL requires an outcome
        list.add(missingOutcomeOnFinal);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(),
                "unknown or inconsistent records must fail the store closed");
    }

    @Test
    void missingNullableTheftTypeIsAllowed() {
        // A non-asset outcome (NO_CANDIDATE) legitimately has theftType ==
        // null; a missing nullable field is fine on load.
        ShadowAuditRecord base = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ShadowTargetKind.PLAYER, null, null, ShadowTheftOutcome.NO_CANDIDATE,
                ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null);
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag noTheftType = storeRecordToTag(base);
        noTheftType.remove("theftType"); // nullable field → null is fine
        list.add(noTheftType);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertEquals(1, loaded.all().size());
        assertNull(loaded.all().get(0).theftType());
    }

    @Test
    void nonFiniteAndNegativeScalarsFailClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag nan = storeRecordToTag(record());
        nan.putDouble("numericAmount", Double.NaN);
        list.add(nan);
        CompoundTag negativeCount = storeRecordToTag(record());
        negativeCount.putInt("itemCount", -5);
        list.add(negativeCount);
        CompoundTag negativeTick = storeRecordToTag(record());
        negativeTick.putLong("serverTick", -1L);
        list.add(negativeTick);
        CompoundTag negativeEpoch = storeRecordToTag(record());
        negativeEpoch.putLong("timestampEpochMillis", -1L);
        list.add(negativeEpoch);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(),
                "non-finite or negative scalars are storage damage — fail closed");
    }

    @Test
    void failureReasonRoundTrips() {
        ShadowAuditStore store = new ShadowAuditStore();
        ShadowAuditRecord withReason = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.TRANSFER_FAILED, ShadowAuditState.FINAL, null, 0, 0.0d, null, 0,
                1_000L, 5_000L, DIM, null, "no_space");
        store.append(withReason);
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertEquals("no_space", loaded.byEventId(withReason.eventId()).failureReason());
    }

    @Test
    void capacityEvictsOldestOnAppend() {
        ShadowAuditStore store = new ShadowAuditStore();
        ShadowAuditRecord first = record();
        assertTrue(store.append(first));
        for (int i = 1; i < ShadowAuditStore.MAX_RECORDS; i++) {
            store.append(record());
        }
        // Eviction kicks in on the (MAX_RECORDS+1)-th append.
        store.append(record());
        assertEquals(ShadowAuditStore.MAX_RECORDS, store.all().size());
        assertFalse(store.has(first.eventId()), "the oldest record must be evicted");
    }

    @Test
    void loadKeepsNewestTenThousandValidRecordsWithIdentifiableIds() {
        // Build a saved file with MAX_RECORDS + 20 records; the oldest 20
        // must be dropped and the newest must survive — verified by the
        // identifiable eventIds, not just by size.
        ShadowAuditStore store = new ShadowAuditStore();
        List<ShadowAuditRecord> all = new java.util.ArrayList<>();
        for (int i = 0; i < ShadowAuditStore.MAX_RECORDS + 20; i++) {
            UUID eventId = new UUID(0L, i + 1L); // deterministic, identifiable
            all.add(new ShadowAuditRecord(eventId, UUID.randomUUID(), UUID.randomUUID(),
                    ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                    ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0, 1_000L + i, 5_000L, DIM, null, null));
        }
        for (ShadowAuditRecord r : all) {
            store.append(r);
        }
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());

        assertEquals(ShadowAuditStore.MAX_RECORDS, loaded.all().size());
        // The 20 oldest event ids (1..20) must be gone.
        for (int i = 1; i <= 20; i++) {
            assertFalse(loaded.has(new UUID(0L, i)),
                    "the oldest record " + i + " must be evicted on load");
        }
        // The newest 20 (MAX+1 .. MAX+20) must be present.
        for (int i = 0; i < 20; i++) {
            assertTrue(loaded.has(new UUID(0L, ShadowAuditStore.MAX_RECORDS + i + 1L)),
                    "the newest record " + (ShadowAuditStore.MAX_RECORDS + i + 1) + " must survive");
        }
        // Order stays oldest → newest.
        List<ShadowAuditRecord> snapshot = loaded.all();
        for (int i = 1; i < snapshot.size(); i++) {
            assertTrue(snapshot.get(i - 1).timestampEpochMillis() <= snapshot.get(i).timestampEpochMillis(),
                    "the stored order must stay chronological");
        }
    }

    @Test
    void invalidRecordsFailClosedInsteadOfCountingTowardsTheCap() {
        // 8C.2.4: an invalid record no longer "does not count" — it marks
        // the whole store damaged.
        ListTag list = new ListTag();
        for (int i = 0; i < 3; i++) {
            list.add(storeRecordToTag(new ShadowAuditRecord(new UUID(0L, i + 1L),
                    UUID.randomUUID(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null,
                    ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                    ITEM, 1, 0.0d, null, 0, 1_000L + i, 5_000L, DIM, null, null)));
        }
        CompoundTag invalid = storeRecordToTag(record());
        invalid.putInt("itemCount", -3);
        list.add(invalid);
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "an invalid record must fail the store closed");
    }

    @Test
    void queryResultsAreImmutableSnapshots() {
        ShadowAuditStore store = new ShadowAuditStore();
        store.append(record());
        List<ShadowAuditRecord> snapshot = store.all();
        store.append(record());
        assertEquals(1, snapshot.size(), "the snapshot must not change after the store grows");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(record()));
    }

    @Test
    void setDirtyOnlyOnRealWrites() {
        ShadowAuditStore store = new ShadowAuditStore();
        assertFalse(store.isDirty(), "an untouched store must not be dirty");
        store.append(record());
        assertTrue(store.isDirty(), "a real write must mark the store dirty");
    }

    @Test
    void recordValidationRejectsBadCombinations() {
        UUID eventId = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // ENTITY target without targetType.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.ENTITY, null, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
        // PLAYER target with a targetType.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, ZOMBIE, null,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
        // EFFECT theft without an effectId.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.EFFECT,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
        // effectId without the EFFECT type.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                null, 0, 0.0d, EFFECT, 200, 1L, 1L, DIM, null, null));
        // numericAmount combined with an item id.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                ITEM, 1, 5.0d, null, 0, 1L, 1L, DIM, null, null));
        // PENDING with an outcome.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.PENDING,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
        // FINAL without an outcome.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                null, ShadowAuditState.FINAL, null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, null));
        // Negative epoch / serverTick.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                ITEM, 1, 0.0d, null, 0, -1L, 1L, DIM, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                ITEM, 1, 0.0d, null, 0, 1L, -1L, DIM, null, null));
        // Over-long / control-character failure reasons.
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.TRANSFER_FAILED, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, "x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new ShadowAuditRecord(
                eventId, thief, target, ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.TRANSFER_FAILED, ShadowAuditState.FINAL,
                null, 0, 0.0d, null, 0, 1L, 1L, DIM, null, "bad\nreason"));
    }

    @Test
    void positionIsImmutableInRecord() {
        ShadowAuditRecord r = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                ITEM, 1, 0.0d, null, 0, 1L, 1L, DIM, new BlockPos(7, 8, 9), null);
        assertEquals(new BlockPos(7, 8, 9), r.position(), "position must be an immutable copy");
    }

    /** Serialises a record the same way the store does (mirrors save()). */
    private static CompoundTag storeRecordToTag(ShadowAuditRecord r) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("eventId", r.eventId());
        tag.putUUID("thief", r.thiefId());
        tag.putUUID("target", r.targetId());
        tag.putString("targetKind", r.targetKind().name());
        if (r.targetType() != null) {
            tag.putString("targetType", r.targetType().toString());
        }
        if (r.theftType() != null) {
            tag.putString("theftType", r.theftType().name());
        }
        if (r.outcome() != null) {
            tag.putString("outcome", r.outcome().name());
        }
        tag.putString("auditState", r.auditState().name());
        if (r.itemId() != null) {
            tag.putString("itemId", r.itemId().toString());
        }
        tag.putInt("itemCount", r.itemCount());
        tag.putDouble("numericAmount", r.numericAmount());
        if (r.effectId() != null) {
            tag.putString("effectId", r.effectId().toString());
        }
        tag.putInt("effectDurationTicks", r.effectDurationTicks());
        tag.putLong("timestampEpochMillis", r.timestampEpochMillis());
        tag.putLong("serverTick", r.serverTick());
        tag.putString("dimension", r.dimension().toString());
        if (r.position() != null) {
            tag.putInt("posX", r.position().getX());
            tag.putInt("posY", r.position().getY());
            tag.putInt("posZ", r.position().getZ());
        }
        if (r.failureReason() != null) {
            tag.putString("failureReason", r.failureReason());
        }
        return tag;
    }

    // ---- 8C.2.4 health, capacity and migration ----

    @Test
    void conflictingDuplicateEventIdFailsClosed() {
        // The same eventId with DIFFERENT content is storage damage — never
        // "pick the last one" (8C.2.4 §1).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.putBoolean("failClosed", false);
        ListTag list = new ListTag();
        ShadowAuditRecord base = record();
        CompoundTag first = storeRecordToTag(base);
        CompoundTag second = storeRecordToTag(new ShadowAuditRecord(base.eventId(),
                base.thiefId(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL, ITEM, 1, 0.0d, null, 0,
                1_000L, 5_000L, DIM, null, null));
        list.add(first);
        list.add(second);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a conflicting duplicate eventId must fail closed");
    }

    @Test
    void byteIdenticalDuplicateEventIdsAreAccepted() {
        // 8C.2.4 §4: byte-identical duplicates are acceptable.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.putBoolean("failClosed", false);
        ListTag list = new ListTag();
        ShadowAuditRecord base = record();
        list.add(storeRecordToTag(base));
        list.add(storeRecordToTag(base));
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy(), "byte-identical duplicates must not mark the store damaged");
        assertEquals(1, loaded.all().size(), "the duplicate collapses to one record");
    }

    @Test
    void capacityNeverEvictsPendingOrRecoveryRequired() {
        ShadowAuditStore store = new ShadowAuditStore();
        // A PENDING pre-write and a RECOVERY_REQUIRED record are the two
        // critical kinds; everything else is an evictable settled FINAL.
        ShadowAuditRecord pending = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null,
                ShadowAuditState.PENDING, null, 0, 0.0d, null, 0, 1L, 5_000L, DIM, null, null);
        ShadowAuditRecord recovery = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                ShadowTheftOutcome.RECOVERY_REQUIRED, ShadowAuditState.FINAL, null, 0, 0.0d, null, 0,
                2L, 5_000L, DIM, null, null);
        assertTrue(store.append(pending));
        assertTrue(store.append(recovery));
        // Fill the cap with settled FINALs (all evictable).
        for (int i = 2; i < ShadowAuditStore.MAX_RECORDS; i++) {
            store.append(record());
        }
        // One more append evicts the OLDEST settled FINAL — the PENDING and
        // the RECOVERY_REQUIRED records must survive.
        store.append(record());
        assertEquals(ShadowAuditStore.MAX_RECORDS, store.all().size());
        assertTrue(store.has(pending.eventId()),
                "a PENDING pre-write must never be evicted for space");
        assertTrue(store.has(recovery.eventId()),
                "a RECOVERY_REQUIRED record must never be evicted for space");
    }

    @Test
    void allCriticalRecordsRefuseAppend() {
        ShadowAuditStore store = new ShadowAuditStore();
        for (int i = 0; i < ShadowAuditStore.MAX_RECORDS; i++) {
            ShadowAuditRecord critical = new ShadowAuditRecord(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, null,
                    ShadowAuditState.PENDING, null, 0, 0.0d, null, 0, i + 1L, 5_000L, DIM, null, null);
            assertTrue(store.append(critical), "the cap fills with PENDING records");
        }
        assertEquals(ShadowAuditStore.MAX_RECORDS, store.all().size());
        assertFalse(store.append(record()),
                "when every record must be kept the append must refuse — no asset commit");
        assertTrue(store.isHealthy(), "a refusal for capacity is NOT damage");
    }

    @Test
    void loadOverflowKeepsCriticalRecordsElseFailsClosed() {
        // MAX_RECORDS + 2 records where the OLDEST is critical (PENDING):
        // it must survive; the overflow is expressed by evicting settled
        // FINALs instead.
        ListTag list = new ListTag();
        ShadowAuditRecord oldestPending = new ShadowAuditRecord(new UUID(0L, 1L),
                UUID.randomUUID(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM,
                null, ShadowAuditState.PENDING, null, 0, 0.0d, null, 0, 1L, 5_000L, DIM, null, null);
        list.add(storeRecordToTag(oldestPending));
        for (int i = 2; i <= ShadowAuditStore.MAX_RECORDS + 2; i++) {
            list.add(storeRecordToTag(new ShadowAuditRecord(new UUID(0L, i),
                    UUID.randomUUID(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null,
                    ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS, ShadowAuditState.FINAL,
                    ITEM, 1, 0.0d, null, 0, 1_000L + i, 5_000L, DIM, null, null)));
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.putBoolean("failClosed", false);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy());
        assertTrue(loaded.has(oldestPending.eventId()),
                "the oldest PENDING must survive the load overflow (settled FINALs are evicted instead)");
        assertEquals(ShadowAuditStore.MAX_RECORDS, loaded.all().size());
    }

    @Test
    void failClosedFlagSurvivesSaveAndReload() {
        CompoundTag damaged = new CompoundTag();
        damaged.putInt("dataVersion", 99); // future version → fail closed
        ShadowAuditStore store = ShadowAuditStore.load(damaged, provider());
        assertFalse(store.isHealthy());

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowAuditStore reloaded = ShadowAuditStore.load(tag, provider());
        assertFalse(reloaded.isHealthy(), "the fail-closed flag must persist");
        assertFalse(reloaded.append(record()), "the reloaded store keeps refusing writes");

        // A healthy store round-trips WITHOUT the flag.
        ShadowAuditStore healthy = new ShadowAuditStore();
        assertTrue(healthy.append(record()));
        ShadowAuditStore healthyReloaded =
                ShadowAuditStore.load(healthy.save(new CompoundTag(), provider()), provider());
        assertTrue(healthyReloaded.isHealthy());
    }

    @Test
    void legacyV1DataMigratesHealthy() {
        // v1 payloads (no failClosed key) load cleanly and stay healthy.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        list.add(storeRecordToTag(record()));
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy(), "legacy v1 data must not be locked");
        assertEquals(1, loaded.all().size());
        assertTrue(loaded.append(record()), "a migrated store must accept new records");
    }

    // ---- 8C.2.5 strict root schema & record fields ----

    @Test
    void missingDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(storeRecordToTag(record()));
        tag.put("records", list); // no dataVersion at all
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a missing dataVersion must fail closed");
    }

    @Test
    void mistypedDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dataVersion", "1"); // TAG_STRING instead of TAG_INT
        tag.put("records", new ListTag());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped dataVersion must fail closed");
    }

    @Test
    void missingRecordsListFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        // no records key at all
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a missing records list must fail closed");
    }

    @Test
    void mistypedRecordsListFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.putString("records", "not-a-list"); // wrong root type
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped records key must fail closed");
    }

    @Test
    void nonCompoundRecordElementsFailClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("oops"));
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "non-compound record elements must fail closed");
    }

    @Test
    void minimalV1SchemaWithEmptyRecordsLoadsHealthy() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("records", new ListTag()); // empty list is legal
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy());
        assertEquals(0, loaded.all().size());
    }

    @Test
    void v2MissingFailClosedFlagFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.put("records", new ListTag()); // no failClosed TAG_BYTE
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a v2 payload without failClosed must fail closed");
    }

    @Test
    void mistypedFailClosedFlagFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.putString("failClosed", "true"); // TAG_STRING instead of TAG_BYTE
        tag.put("records", new ListTag());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped failClosed flag must fail closed");
    }

    @Test
    void missingRequiredScalarsFailClosed() {
        // Each required scalar removed separately must fail the store.
        for (String key : new String[] { "itemCount", "numericAmount",
                "effectDurationTicks", "timestampEpochMillis", "serverTick" }) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("dataVersion", 1);
            ListTag list = new ListTag();
            CompoundTag bad = storeRecordToTag(record());
            bad.remove(key);
            list.add(bad);
            tag.put("records", list);
            ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
            assertFalse(loaded.isHealthy(), "a record missing " + key + " must fail closed");
        }
    }

    @Test
    void missingTimestampOrServerTickFailsClosed() {
        for (String key : new String[] { "timestampEpochMillis", "serverTick" }) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("dataVersion", 1);
            ListTag list = new ListTag();
            CompoundTag bad = storeRecordToTag(record());
            bad.remove(key);
            list.add(bad);
            tag.put("records", list);
            ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
            assertFalse(loaded.isHealthy(), "a record missing " + key + " must fail closed");
        }
    }

    @Test
    void mistypedRequiredScalarsFailClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putString("itemCount", "1"); // TAG_STRING instead of TAG_INT
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped required scalar must fail closed");
    }

    @Test
    void partialPositionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putInt("posX", 1);
        bad.putInt("posY", 2);
        bad.remove("posZ"); // x/y present, z missing → partial → damage
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "partial coordinates must fail closed");
    }

    @Test
    void mistypedOptionalFieldsFailClosed() {
        // theftType / targetType / failureReason present with the wrong NBT
        // type must fail closed (missing stays fine).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putInt("theftType", 3); // TAG_INT instead of TAG_STRING
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped optional enum must fail closed");

        tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        list = new ListTag();
        bad = storeRecordToTag(record());
        bad.putInt("failureReason", 3);
        list.add(bad);
        tag.put("records", list);
        loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped failureReason must fail closed");

        tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        list = new ListTag();
        bad = storeRecordToTag(record());
        bad.putInt("targetType", 3);
        list.add(bad);
        tag.put("records", list);
        loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a mistyped targetType must fail closed");
    }

    @Test
    void loadDropsSettledFinalBeyondAllCriticalCap() {
        // 8C.2.5 §4: MAX_RECORDS PENDING + one settled FINAL → the FINAL is
        // safely dropped, the store stays healthy, every PENDING survives.
        ListTag list = new ListTag();
        for (int i = 1; i <= ShadowAuditStore.MAX_RECORDS; i++) {
            list.add(storeRecordToTag(new ShadowAuditRecord(new UUID(0L, i),
                    UUID.randomUUID(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null,
                    ShadowTheftType.ITEM, null, ShadowAuditState.PENDING, null, 0, 0.0d, null, 0,
                    i, 5_000L, DIM, null, null)));
        }
        list.add(storeRecordToTag(record())); // the droppable settled FINAL
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy(),
                "a settled FINAL beyond the critical cap must be dropped, not lock the store");
        assertEquals(ShadowAuditStore.MAX_RECORDS, loaded.all().size());
        assertFalse(loaded.has(recordEventIdHint(list)),
                "the settled FINAL must be gone");
        for (int i = 1; i <= ShadowAuditStore.MAX_RECORDS; i++) {
            assertTrue(loaded.has(new UUID(0L, i)), "every PENDING must survive");
        }
    }

    private static UUID recordEventIdHint(ListTag list) {
        // The last (settled FINAL) entry's eventId.
        return net.minecraft.nbt.NbtUtils.loadUUID(
                list.getCompound(list.size() - 1).get("eventId"));
    }

    @Test
    void criticalRecordBeyondAllCriticalCapFailsClosed() {
        // 8C.2.5 §4: MAX_RECORDS PENDING + ANOTHER critical record that
        // cannot be expressed → the store fails closed.
        ListTag list = new ListTag();
        for (int i = 1; i <= ShadowAuditStore.MAX_RECORDS + 1; i++) {
            list.add(storeRecordToTag(new ShadowAuditRecord(new UUID(0L, i),
                    UUID.randomUUID(), UUID.randomUUID(), ShadowTargetKind.PLAYER, null,
                    ShadowTheftType.ITEM, null, ShadowAuditState.PENDING, null, 0, 0.0d, null, 0,
                    i, 5_000L, DIM, null, null)));
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(),
                "a critical record that cannot be expressed must fail closed");
    }

    // ---- 8C.2.6 dataVersion boundary & position matrix ----

    @Test
    void zeroDataVersionFailsClosed() {
        // 8C.2.6 §1: version 0 is never a valid schema version.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 0);
        tag.put("records", new ListTag());
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "dataVersion=0 must fail closed");
    }

    @Test
    void positionAllPresentWrongTypesFailsClosed() {
        // 8C.2.6 §2: all three keys present but NONE is TAG_INT → damage.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putString("posX", "1");
        bad.putString("posY", "2");
        bad.putString("posZ", "3");
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "all-wrong position types must fail closed");
    }

    @Test
    void positionMixedTypesFailsClosed() {
        // 8C.2.6 §2: x/y TAG_INT, z TAG_STRING → mixed → damage.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putInt("posX", 1);
        bad.putInt("posY", 2);
        bad.putString("posZ", "3");
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "mixed position types must fail closed");
    }

    @Test
    void positionPartialFieldFailsClosed() {
        // 8C.2.6 §2: only one coordinate present → partial → damage.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag bad = storeRecordToTag(record());
        bad.putInt("posX", 1);
        bad.remove("posY");
        bad.remove("posZ");
        list.add(bad);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertFalse(loaded.isHealthy(), "a single present coordinate must fail closed");
    }

    @Test
    void positionAllThreeValidIntsLoads() {
        // 8C.2.6 §2: three valid TAG_INT coordinates → legal BlockPos.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag ok = storeRecordToTag(record());
        ok.putInt("posX", 7);
        ok.putInt("posY", 8);
        ok.putInt("posZ", 9);
        list.add(ok);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy());
        assertEquals(new BlockPos(7, 8, 9), loaded.all().get(0).position());
    }

    @Test
    void positionFullyAbsentLoadsNull() {
        // 8C.2.6 §2: no coordinate keys at all → legal, position == null.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        ListTag list = new ListTag();
        CompoundTag noPos = storeRecordToTag(record());
        noPos.remove("posX");
        noPos.remove("posY");
        noPos.remove("posZ");
        list.add(noPos);
        tag.put("records", list);
        ShadowAuditStore loaded = ShadowAuditStore.load(tag, provider());
        assertTrue(loaded.isHealthy());
        assertNull(loaded.all().get(0).position(), "absent coordinates must stay null");
    }
}
