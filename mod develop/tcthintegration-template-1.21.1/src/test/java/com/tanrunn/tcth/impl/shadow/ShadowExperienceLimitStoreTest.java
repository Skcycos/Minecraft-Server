package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.shadow.ShadowExperienceLimitWriter.ReservationResult;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Unit tests for {@link ShadowExperienceLimitStore} (phase 8E §8): the
 * per-(thief, target)-pair daily job-experience quota with the eventId-
 * idempotent reservation protocol, the UTC-day scoping, the fail-closed
 * inputs, the bounded caps, the save-load round-trip (restart persistence)
 * and the strict-schema corrupted-NBT handling.
 */
class ShadowExperienceLimitStoreTest {

    private static final String DAY = "2026-08-12";
    private static final String NEXT_DAY = "2026-08-13";

    private static HolderLookup.Provider provider() {
        return HolderLookup.Provider.create(Stream.empty());
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        ShadowExperienceLimitStore.resetForTesting();
    }

    @Test
    void reservationLifecycleReservesCommitsAndReleases() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();

        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, e1, 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, e2, 3L));
        assertFalse(store.isAtPairLimit(thief, target, DAY, 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, e3, 3L));
        assertTrue(store.isAtPairLimit(thief, target, DAY, 3L),
                "the third reservation reaches the cap");
        assertEquals(ReservationResult.LIMIT_REACHED,
                store.tryReserve(thief, target, DAY, UUID.randomUUID(), 3L));

        // Release frees the quota (failed Arc send → retryable path).
        assertTrue(store.releaseReservation(e1));
        assertFalse(store.isAtPairLimit(thief, target, DAY, 3L));
        assertFalse(store.releaseReservation(e1), "double release must be refused");

        // Commit keeps the quota occupied (successful Arc send path).
        assertTrue(store.commitReservation(e2));
        assertFalse(store.commitReservation(e2), "an already-committed id must be refused");
        assertEquals(2, store.occupiedCountForTest(thief, target, DAY),
                "committing must keep the quota occupied");
        assertTrue(store.isAtPairLimit(thief, target, DAY, 2L));
    }

    @Test
    void eventIdIdempotencyNeverDoubleCounts() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 1L));
        // Same eventId again → still RESERVED, still one quota slot.
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 1L));
        assertEquals(1, store.occupiedCountForTest(thief, target, DAY));
        assertTrue(store.commitReservation(eventId));
        // A re-fired event after a restart reports COMMITTED_EXISTING — the
        // reward module must not send a second time.
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                store.tryReserve(thief, target, DAY, eventId, 1L));
        assertEquals(1, store.occupiedCountForTest(thief, target, DAY));
    }

    @Test
    void eventIdReuseWithDifferentPairOrDayIsRejected() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 3L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), DAY, eventId, 3L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), target, DAY, eventId, 3L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, target, NEXT_DAY, eventId, 3L));
    }

    @Test
    void pairsAreIndependent() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, targetA, DAY, UUID.randomUUID(), 1L));
        assertTrue(store.isAtPairLimit(thief, targetA, DAY, 1L));
        assertFalse(store.isAtPairLimit(thief, targetB, DAY, 1L), "another target is unaffected");
        assertFalse(store.isAtPairLimit(UUID.randomUUID(), targetA, DAY, 1L), "another thief is unaffected");
    }

    @Test
    void utcDayRolloverResetsTheQuota() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, UUID.randomUUID(), 1L));
        assertTrue(store.isAtPairLimit(thief, target, DAY, 1L));
        assertFalse(store.isAtPairLimit(thief, target, NEXT_DAY, 1L), "the next UTC day is fresh");
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, NEXT_DAY, UUID.randomUUID(), 1L));
        assertEquals(1, store.occupiedCountForTest(thief, target, DAY));
        assertEquals(1, store.occupiedCountForTest(thief, target, NEXT_DAY));
    }

    @Test
    void invalidInputsFailClosed() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        assertEquals(ReservationResult.REJECTED, store.tryReserve(null, target, DAY, UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(thief, null, DAY, UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(thief, target, "not-a-date", UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(thief, target, "2026/08/12", UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(thief, target, DAY, null, 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(thief, target, DAY, UUID.randomUUID(), 0L));
        assertTrue(store.isAtPairLimit(thief, target, "bad", 3L));
        assertTrue(store.isAtPairLimit(null, target, DAY, 3L));
    }

    @Test
    void restartPersistenceRoundTrips() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 3L));
        assertTrue(store.commitReservation(eventId));
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, NEXT_DAY, UUID.randomUUID(), 3L));

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());

        assertFalse(reloaded.isFailClosed());
        assertEquals(1, reloaded.occupiedCountForTest(thief, target, DAY));
        assertEquals(1, reloaded.occupiedCountForTest(thief, target, NEXT_DAY));
        assertTrue(reloaded.isAtPairLimit(thief, target, DAY, 1L));
        // The committed eventId survives a restart and blocks re-sending.
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                reloaded.tryReserve(thief, target, DAY, eventId, 3L));
        // 8E.1 §1.4: a COMMITTED entry is NEVER releaseable through the
        // failed-send path — the XP was already granted.
        assertFalse(reloaded.releaseReservation(eventId));
        assertTrue(reloaded.isAtPairLimit(thief, target, DAY, 1L), "the quota stays occupied");
    }

    @Test
    void committedAfterRestartIsNeverReleased() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 1L));
        assertTrue(store.commitReservation(eventId));
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed());
        assertFalse(reloaded.releaseReservation(eventId),
                "a COMMITTED reservation must never be released after a restart");
        assertTrue(reloaded.isAtPairLimit(thief, target, DAY, 1L));
        assertFalse(reloaded.commitReservation(eventId), "a COMMITTED entry cannot be committed again");
    }

    @Test
    void reservedOnDiskMigratesToRecoveryAndBlocksResend() {
        // Same-JVM crash window: a RESERVED reservation is saved, the server
        // dies before the Arc send outcome is known, and the store reloads.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 3L));
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed());
        // The migrated entry is conservatively RECOVERY: the same eventId is
        // refused (never re-sent) and the quota stays occupied.
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                reloaded.tryReserve(thief, target, DAY, eventId, 3L));
        assertEquals(1, reloaded.occupiedCountForTest(thief, target, DAY),
                "an unknown outcome must keep the quota occupied");
        // It is never released as a clean failure…
        assertFalse(reloaded.releaseReservation(eventId));
        // …and never committed either.
        assertFalse(reloaded.commitReservation(eventId));
        assertTrue(reloaded.isAtPairLimit(thief, target, DAY, 1L));
    }

    @Test
    void recoveryStateSurvivesAnotherSaveAndLoad() {
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // Produce a RECOVERY entry on disk via the crash-migration path.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 3L));
        ShadowExperienceLimitStore migrated = ShadowExperienceLimitStore.load(
                store.save(new CompoundTag(), provider()), provider());
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                migrated.tryReserve(thief, target, DAY, eventId, 3L));
        // RECOVERY saves and reloads as RECOVERY — still conservatively
        // refused, quota still occupied.
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(
                migrated.save(new CompoundTag(), provider()), provider());
        assertFalse(reloaded.isFailClosed());
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                reloaded.tryReserve(thief, target, DAY, eventId, 3L));
        assertFalse(reloaded.releaseReservation(eventId));
        assertEquals(1, reloaded.occupiedCountForTest(thief, target, DAY));
    }

    @Test
    void v1DataWithReservedMigratesToRecovery() {
        // 8E.1 §1.5: explicit migration test for the previous data version.
        // v1 only ever wrote RESERVED / COMMITTED; a persisted RESERVED must
        // load as RECOVERY (never re-sent, never released).
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.putBoolean("failClosed", false);
        ListTag pairs = new ListTag();
        CompoundTag pair = new CompoundTag();
        pair.putUUID("thiefId", thief);
        pair.putUUID("targetId", target);
        ListTag days = new ListTag();
        CompoundTag day = new CompoundTag();
        day.putString("day", DAY);
        day.putInt("count", 1);
        days.add(day);
        pair.put("days", days);
        pairs.add(pair);
        tag.put("pairs", pairs);
        ListTag reservations = new ListTag();
        CompoundTag r = new CompoundTag();
        r.putUUID("eventId", eventId);
        r.putUUID("thiefId", thief);
        r.putUUID("targetId", target);
        r.putString("day", DAY);
        r.putString("state", "RESERVED");
        reservations.add(r);
        tag.put("reservations", reservations);

        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(store.isFailClosed(), "v1 data must migrate, not fail closed");
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                store.tryReserve(thief, target, DAY, eventId, 3L));
        assertFalse(store.releaseReservation(eventId));
        assertEquals(1, store.occupiedCountForTest(thief, target, DAY));
    }

    // ---- strict schema / fail-closed ----

    @Test
    void missingDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(store.isFailClosed());
        assertTrue(store.isAtPairLimit(UUID.randomUUID(), UUID.randomUUID(), DAY, 3L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    @Test
    void futureVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION + 1);
        tag.putBoolean("failClosed", false);
        tag.put("pairs", new ListTag());
        tag.put("reservations", new ListTag());
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(store.isFailClosed(), "a future version must fail closed, never load empty");
    }

    @Test
    void zeroAndNegativeVersionsFailClosed() {
        for (int version : new int[] { 0, -1 }) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("dataVersion", version);
            tag.putBoolean("failClosed", false);
            tag.put("pairs", new ListTag());
            tag.put("reservations", new ListTag());
            assertTrue(ShadowExperienceLimitStore.load(tag, provider()).isFailClosed(),
                    "version " + version + " must fail closed");
        }
    }

    @Test
    void missingFailClosedFlagFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag.put("pairs", new ListTag());
        tag.put("reservations", new ListTag());
        assertTrue(ShadowExperienceLimitStore.load(tag, provider()).isFailClosed());
    }

    @Test
    void missingPairsOrReservationsFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag.putBoolean("failClosed", false);
        tag.put("pairs", new ListTag());
        assertTrue(ShadowExperienceLimitStore.load(tag, provider()).isFailClosed(),
                "missing reservations list must fail closed");
        CompoundTag tag2 = new CompoundTag();
        tag2.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag2.putBoolean("failClosed", false);
        tag2.put("reservations", new ListTag());
        assertTrue(ShadowExperienceLimitStore.load(tag2, provider()).isFailClosed(),
                "missing pairs list must fail closed");
    }

    @Test
    void corruptedAggregatesFailClosed() {
        // A pair entry with a missing count field must fail closed — never
        // read the count as 0 and silently reopen quota.
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag.putBoolean("failClosed", false);
        ListTag pairs = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("thiefId", thief);
        entry.putUUID("targetId", target);
        ListTag days = new ListTag();
        CompoundTag day = new CompoundTag();
        day.putString("day", DAY);
        days.add(day); // missing count
        entry.put("days", days);
        pairs.add(entry);
        tag.put("pairs", pairs);
        tag.put("reservations", new ListTag());
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(store.isFailClosed());
    }

    @Test
    void corruptedReservationsFailClosed() {
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag.putBoolean("failClosed", false);
        tag.put("pairs", new ListTag());
        ListTag reservations = new ListTag();
        CompoundTag r = new CompoundTag();
        r.putUUID("eventId", UUID.randomUUID());
        r.putUUID("thiefId", thief);
        r.putUUID("targetId", target);
        r.putString("day", DAY);
        r.putString("state", "BOGUS"); // unknown state
        reservations.add(r);
        tag.put("reservations", reservations);
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(store.isFailClosed());
    }

    @Test
    void conflictingDuplicateEventIdsFailClosed() {
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", ShadowExperienceLimitStore.DATA_VERSION);
        tag.putBoolean("failClosed", false);
        tag.put("pairs", new ListTag());
        ListTag reservations = new ListTag();
        CompoundTag r1 = new CompoundTag();
        r1.putUUID("eventId", eventId);
        r1.putUUID("thiefId", thief);
        r1.putUUID("targetId", target);
        r1.putString("day", DAY);
        r1.putString("state", "RESERVED");
        reservations.add(r1);
        CompoundTag r2 = new CompoundTag();
        r2.putUUID("eventId", eventId);
        r2.putUUID("thiefId", thief);
        r2.putUUID("targetId", target);
        r2.putString("day", DAY);
        r2.putString("state", "COMMITTED"); // conflict
        reservations.add(r2);
        tag.put("reservations", reservations);
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(store.isFailClosed(), "conflicting duplicates mark the storage damaged");
    }

    @Test
    void failClosedFlagSurvivesSaveAndReload() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        CompoundTag bad = new CompoundTag(); // no dataVersion
        ShadowExperienceLimitStore damaged = ShadowExperienceLimitStore.load(bad, provider());
        assertTrue(damaged.isFailClosed());
        CompoundTag tag = damaged.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertTrue(reloaded.isFailClosed());
        assertEquals(ReservationResult.REJECTED,
                reloaded.tryReserve(UUID.randomUUID(), UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        assertFalse(reloaded.releaseReservation(UUID.randomUUID()));
        assertFalse(reloaded.commitReservation(UUID.randomUUID()));
    }

    // ---- capacity ----

    @Test
    void pairCapacityFailsClosed() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        }
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    @Test
    void allReservedIndexFullRejectsFailClosed() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
        }
        // Every index entry is still RESERVED: nothing may be evicted, so the
        // store must reject (the aggregate quota must never reopen).
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
    }

    @Test
    void indexFullRejectsNewReservations() {
        // 8E.2.2: when the index reaches MAX_RESERVATIONS, new reservations
        // are rejected (no global COMMITTED eviction).
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, first, 10_000L));
        assertTrue(store.commitReservation(first));
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS - 1; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(thief, target, DAY));
        // Index is full — REJECTED (no eviction).
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
        // Occupied aggregate unchanged.
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(thief, target, DAY));
    }

    @Test
    void dateSupplierIsInjectable() {
        ShadowExperienceLimitStore.setDateSupplierForTesting(() -> LocalDate.of(2026, 8, 12));
        assertEquals("2026-08-12", ShadowExperienceLimitStore.today());
        ShadowExperienceLimitStore.setDateSupplierForTesting(() -> LocalDate.of(2026, 8, 13));
        assertEquals("2026-08-13", ShadowExperienceLimitStore.today());
        ShadowExperienceLimitStore.setDateSupplierForTesting(() -> {
            throw new IllegalStateException("clock broken");
        });
        assertEquals("", ShadowExperienceLimitStore.today(), "a broken clock fails closed to empty");
    }

    // ---- 8E.1 §2: MAX_DAYS_PER_PAIR vs reservation-index consistency ----

    /** ISO date {@code 2026-01-01} + {@code offset} days. */
    private static String day(int offset) {
        return LocalDate.of(2026, 1, 1).plusDays(offset).toString();
    }

    @Test
    void sixtyFiveConsecutiveDaysStayHealthyAcrossSaveLoad() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 65; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, day(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId), "day " + i + " commit");
        }
        // The oldest day (2026-01-01) was evicted with its index entries; the
        // current day's quota is intact.
        assertEquals(0, store.occupiedCountForTest(thief, target, day(0)),
                "the evicted oldest day must not remain in the aggregate");
        assertEquals(1, store.occupiedCountForTest(thief, target, day(64)),
                "the current day's quota must be intact");

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed(),
                "65 consecutive days must survive save/load without corrupting the store");
        assertEquals(1, reloaded.occupiedCountForTest(thief, target, day(64)));
        assertEquals(0, reloaded.occupiedCountForTest(thief, target, day(0)),
                "an evicted old day must never be back-filled by stale reservations");
        assertTrue(reloaded.isAtPairLimit(thief, target, day(64), 1L));
        // The same pair can still reserve fresh days.
        assertEquals(ReservationResult.RESERVED,
                reloaded.tryReserve(thief, target, day(65), UUID.randomUUID(), 1L));
    }

    @Test
    void doubleSaveLoadIsStable() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 70; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, day(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        CompoundTag first = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore once = ShadowExperienceLimitStore.load(first, provider());
        CompoundTag second = once.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore twice = ShadowExperienceLimitStore.load(second, provider());
        assertFalse(once.isFailClosed());
        assertFalse(twice.isFailClosed(), "consecutive save/load rounds must stay healthy");
        assertEquals(once.occupiedCountForTest(thief, target, day(69)),
                twice.occupiedCountForTest(thief, target, day(69)));
        assertEquals(once.reservationCount(), twice.reservationCount(),
                "the reservation index must be stable across save/load rounds");
    }

    @Test
    void oldestDayWithUnsettledReservationRefusesNewReservation() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // Fill 64 days with COMMITTED entries.
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_DAYS_PER_PAIR; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, day(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        // Turn the oldest day's COMMITTED entry into an unsettled RECOVERY
        // via the crash-migration path: rebuild the store from NBT where the
        // oldest day's reservation is persisted as RESERVED (limit 2 so the
        // day aggregate holds 2 slots).
        ShadowExperienceLimitStore rebuilt = new ShadowExperienceLimitStore();
        UUID thief2 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_DAYS_PER_PAIR; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    rebuilt.tryReserve(thief2, target2, day(i), eventId, 2L));
            assertTrue(rebuilt.commitReservation(eventId));
        }
        // A second, still-RESERVED slot on the oldest day.
        UUID unsettled = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                rebuilt.tryReserve(thief2, target2, day(0), unsettled, 2L));
        ShadowExperienceLimitStore migrated = ShadowExperienceLimitStore.load(
                rebuilt.save(new CompoundTag(), provider()), provider());
        assertFalse(migrated.isFailClosed());
        // The oldest day now carries a RECOVERY record; a NEW day must be
        // conservatively refused instead of silently dropping the unsettled
        // record (8E.1 §2.3).
        assertEquals(ReservationResult.REJECTED,
                migrated.tryReserve(thief2, target2, day(64), UUID.randomUUID(), 2L));
        // Nothing was deleted: the RECOVERY entry and the aggregate survive.
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                migrated.tryReserve(thief2, target2, day(0), unsettled, 2L));
        assertEquals(2, migrated.occupiedCountForTest(thief2, target2, day(0)));
    }

    // ---- 8E.2.2: no global COMMITTED eviction, safe date rotation ----

    @Test
    void globalIndexFullWithoutSafeRotationRejects() {
        // 8E.2.2: when the reservation index is at MAX_RESERVATIONS and no
        // date rotation can free entries, new reservations are REJECTED.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
        // Index is full — no date rotation possible (all on same day).
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L),
                "index full without safe date rotation → REJECTED");
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
    }

    @Test
    void committedEventIdIsDurableAcrossSaveLoad() {
        // COMMITTED eventId survives save/load and is correctly identified.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(thief, target, DAY, eventId, 3L));
        assertTrue(store.commitReservation(eventId));
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed());
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                reloaded.tryReserve(thief, target, DAY, eventId, 3L),
                "COMMITTED eventId must be durable across save/load");
    }

    @Test
    void sixtyFifthDayRotationFreesCommittedIndexEntries() {
        // 8E.2.2: the 65th-day rotation frees COMMITTED index entries for
        // the evicted day, allowing the store to continue accepting
        // reservations without global eviction.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 65; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, day(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId), "day " + i + " commit");
        }
        // Day 0 was rotated out; its index entry was freed.
        assertEquals(0, store.occupiedCountForTest(thief, target, day(0)));
        assertEquals(1, store.occupiedCountForTest(thief, target, day(64)));
        // The store has exactly 64 entries (one per active day).
        assertEquals(64, store.reservationCount());
    }

    @Test
    void indexFullAfterRotationStillRejects() {
        // Even after a successful date rotation, if the index is still at
        // MAX_RESERVATIONS (entries from other pairs), the store rejects.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // Fill the index with entries on a single day (no rotation possible).
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, DAY, UUID.randomUUID(), 10_000L));
        }
        // Even though this pair could theoretically rotate, all entries are
        // on the same day and there's nothing to rotate.
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, target, NEXT_DAY, UUID.randomUUID(), 10_000L),
                "index full, no rotation frees entries → REJECTED");
    }

    // ---- 8E.2.3: rejection-path purity — no state mutation on reject ----

    @Test
    void indexFullWithNewPairDoesNotCreatePair() {
        // When the index is full and a brand-new pair tries to reserve,
        // REJECTED must not create an entry in the occupied map.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID existingTarget = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, existingTarget, DAY, UUID.randomUUID(), 10_000L));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
        int pairsBefore = store.pairCount();
        // A brand-new pair tries to reserve — must be REJECTED.
        UUID newTarget = UUID.randomUUID();
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, newTarget, DAY, UUID.randomUUID(), 10_000L));
        assertEquals(pairsBefore, store.pairCount(),
                "REJECTED must not create a new pair in occupied");
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount(),
                "REJECTED must not change reservation count");
    }

    @Test
    void failedReservationsDoNotConsumePairCapacity() {
        // 1024 different new pairs that all fail (index full) must not
        // consume any pair capacity.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID filler = UUID.randomUUID();
        // Fill the index.
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, filler, DAY, UUID.randomUUID(), 10_000L));
        }
        int pairsBefore = store.pairCount();
        // 1024 different new targets — all must be REJECTED without creating pairs.
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            assertEquals(ReservationResult.REJECTED,
                    store.tryReserve(thief, UUID.randomUUID(), DAY, UUID.randomUUID(), 10_000L));
        }
        assertEquals(pairsBefore, store.pairCount(),
                "all rejections must leave pairCount unchanged");
    }

    @Test
    void failedReservationsThenSaveLoadStaysHealthy() {
        // After many rejected requests, save/load must still produce a
        // healthy store.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID filler = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, filler, DAY, UUID.randomUUID(), 10_000L));
        }
        // Attempt many rejections.
        for (int i = 0; i < 100; i++) {
            store.tryReserve(thief, UUID.randomUUID(), DAY, UUID.randomUUID(), 10_000L);
        }
        store.tryReserve(null, null, "bad", null, 0L); // invalid inputs
        // Save/load must be healthy.
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed(),
                "store must stay healthy after rejection-path attempts");
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, reloaded.reservationCount());
    }

    @Test
    void rejectionPathsLeaveNoEmptyDaysMaps() {
        // Invalid-input rejections on a new pair must not create empty
        // entries in the occupied map (verified via save/load).
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        assertEquals(0, store.pairCount());
        // These all reject — no pair should be created.
        store.tryReserve(null, target, DAY, UUID.randomUUID(), 3L);
        store.tryReserve(thief, null, DAY, UUID.randomUUID(), 3L);
        store.tryReserve(thief, target, "bad", UUID.randomUUID(), 3L);
        store.tryReserve(thief, target, DAY, null, 3L);
        store.tryReserve(thief, target, DAY, UUID.randomUUID(), 0L);
        assertEquals(0, store.pairCount(),
                "invalid-input rejections must not create pairs");
        // A successful reservation should create exactly 1 pair.
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, DAY, UUID.randomUUID(), 3L));
        assertEquals(1, store.pairCount());
        // Save/load must be healthy.
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed());
        assertEquals(1, reloaded.pairCount());
    }

    // ---- 8E.2.4: rolling retention window ----

    /** Day offset from a fixed base for retention tests. */
    private static String retentionDay(int offset) {
        return LocalDate.of(2026, 6, 1).plusDays(offset).toString();
    }

    @Test
    void retentionWindowCleansOldCommittedAndFreesCapacity() {
        // Fill MAX_RESERVATIONS with COMMITTED entries on one old date. The
        // count assertion must prove that the capacity is genuinely full
        // before the future request triggers retention cleanup.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String oldDay = retentionDay(0);
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, oldDay, eventId, 10_000L));
            assertTrue(store.commitReservation(eventId));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(thief, target, oldDay));

        String futureDay = retentionDay(5000);
        UUID newTarget = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, newTarget, futureDay, UUID.randomUUID(), 1L),
                "retention cleanup must free index capacity for new reservations");
        // The new reservation is the only one left.
        assertEquals(1, store.reservationCount(),
                "only the new reservation should remain after cleanup");
        assertEquals(1, store.pairCount(),
                "only the new pair should remain after cleanup");
    }

    @Test
    void fullPairCapacityCleansOldCommittedPairsBeforeGuardingCapacity() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        String oldDay = retentionDay(0);
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            UUID target = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, oldDay, eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_PAIRS, store.pairCount());

        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, UUID.randomUUID(), retentionDay(5000),
                        UUID.randomUUID(), 1L));
        assertEquals(1, store.pairCount());
        assertEquals(1, store.reservationCount());
    }

    @Test
    void cleanupCanRemoveTheCurrentPairWithoutStaleRotationState() {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_DAYS_PER_PAIR; i++) {
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, retentionDay(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        assertEquals(1, store.pairCount());

        String futureDay = retentionDay(5000);
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, futureDay, UUID.randomUUID(), 1L));
        assertEquals(1, store.pairCount());
        assertEquals(0, store.occupiedCountForTest(thief, target, retentionDay(0)));
        assertEquals(1, store.occupiedCountForTest(thief, target, futureDay));
        assertEquals(1, store.reservationCount());
    }

    @Test
    void fullUnsettledCapacityRejectsAndLeavesNbtUnchanged() {
        ShadowExperienceLimitStore source = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String oldDay = retentionDay(0);
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    source.tryReserve(thief, target, oldDay, UUID.randomUUID(), 10_000L));
        }
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(
                source.save(new CompoundTag(), provider()), provider());
        assertFalse(store.isFailClosed());
        CompoundTag before = store.save(new CompoundTag(), provider());

        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), retentionDay(5000),
                        UUID.randomUUID(), 1L));
        assertEquals(ShadowExperienceLimitStore.MAX_RESERVATIONS, store.reservationCount());
        assertEquals(before, store.save(new CompoundTag(), provider()),
                "unsettled full capacity must not be cleaned or partially applied");
    }

    @Test
    void cleanupThatCannotFreePairCapacityRejectsWithoutApplyingCleanup() {
        ShadowExperienceLimitStore source = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        String oldDay = retentionDay(0);
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            UUID target = UUID.randomUUID();
            UUID committed = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    source.tryReserve(thief, target, oldDay, committed, 2L));
            assertTrue(source.commitReservation(committed));
            assertEquals(ReservationResult.RESERVED,
                    source.tryReserve(thief, target, oldDay, UUID.randomUUID(), 2L));
        }
        ShadowExperienceLimitStore store = ShadowExperienceLimitStore.load(
                source.save(new CompoundTag(), provider()), provider());
        CompoundTag before = store.save(new CompoundTag(), provider());

        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), retentionDay(5000),
                        UUID.randomUUID(), 1L));
        assertEquals(before, store.save(new CompoundTag(), provider()),
                "remaining RECOVERY records keep every pair occupied");
    }

    @Test
    void recentCommittedEventIdStillReturnsCommittedExisting() {
        // COMMITTED entries within the 64-day window must still be
        // recognized as COMMITTED_EXISTING.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String baseDay = retentionDay(100);
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, baseDay, eventId, 3L));
        assertTrue(store.commitReservation(eventId));
        // Reserve on a day within the 64-day window.
        String recentDay = retentionDay(130);
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                store.tryReserve(thief, target, baseDay, eventId, 3L),
                "recent COMMITTED must still return COMMITTED_EXISTING");
        assertEquals(1, store.occupiedCountForTest(thief, target, baseDay),
                "recent COMMITTED must keep its quota occupied");
    }

    @Test
    void reservedAndRecoveryNeverCleanedByRetention() {
        // RESERVED and RECOVERY entries outside the retention window must
        // survive cleanup — they are never auto-cleaned.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID reservedEventId = UUID.randomUUID();
        UUID recoveryEventId = UUID.randomUUID();
        String oldDay = retentionDay(0);
        // RESERVED entry.
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, oldDay, reservedEventId, 3L));
        // RECOVERY entry (via crash-migration path).
        ShadowExperienceLimitStore pre = new ShadowExperienceLimitStore();
        UUID recoveryTarget = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                pre.tryReserve(thief, recoveryTarget, oldDay, recoveryEventId, 3L));
        store = ShadowExperienceLimitStore.load(pre.save(new CompoundTag(), provider()), provider());
        assertEquals(ReservationResult.RECOVERY_EXISTING,
                store.tryReserve(thief, recoveryTarget, oldDay, recoveryEventId, 3L));
        // Also add a RESERVED entry on the same store.
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, oldDay, reservedEventId, 3L));
        int pairsBefore = store.pairCount();
        int reservationsBefore = store.reservationCount();
        // Trigger cleanup by reserving far in the future.
        String futureDay = retentionDay(5000);
        store.tryReserve(thief, UUID.randomUUID(), futureDay, UUID.randomUUID(), 3L);
        // RESERVED and RECOVERY must survive.
        assertEquals(pairsBefore + 1, store.pairCount(),
                "RESERVED/RECOVERY pairs must survive cleanup");
        assertTrue(reservationsBefore <= store.reservationCount(),
                "RESERVED/RECOVERY entries must survive cleanup");
    }

    @Test
    void retentionCleanupThenSaveLoadStaysHealthy() {
        // After retention cleanup, save/load must produce a healthy store
        // with consistent aggregate, reservation, and pair counts.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            UUID target = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, retentionDay(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        // Trigger cleanup.
        String futureDay = retentionDay(5000);
        UUID newTarget = UUID.randomUUID();
        store.tryReserve(thief, newTarget, futureDay, UUID.randomUUID(), 1L);
        int pairsAfter = store.pairCount();
        int reservationsAfter = store.reservationCount();
        // Save/load.
        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowExperienceLimitStore reloaded = ShadowExperienceLimitStore.load(tag, provider());
        assertFalse(reloaded.isFailClosed(),
                "store must stay healthy after retention cleanup + save/load");
        assertEquals(pairsAfter, reloaded.pairCount(),
                "pair count must be consistent across save/load");
        assertEquals(reservationsAfter, reloaded.reservationCount(),
                "reservation count must be consistent across save/load");
    }

    @Test
    void retentionCleanupDeletesEmptyPairsAndFreesMaxPairsCapacity() {
        // All old pairs cleaned → pair capacity freed → new pairs can use
        // MAX_PAIRS slots. With cleanup on every tryReserve, the pair count
        // stays bounded by the retention window (~64).
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            UUID target = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, retentionDay(i), eventId, 1L));
            assertTrue(store.commitReservation(eventId));
        }
        // The retention cleanup keeps the pair count bounded.
        int pairsAfterFill = store.pairCount();
        assertTrue(pairsAfterFill <= ShadowExperienceLimitStore.RETENTION_DAYS,
                "retention cleanup keeps pair count bounded: " + pairsAfterFill);
        // Trigger full cleanup by advancing far into the future.
        String futureDay = retentionDay(5000);
        UUID newTarget = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, newTarget, futureDay, UUID.randomUUID(), 1L));
        assertEquals(1, store.pairCount(),
                "all old pairs cleaned, only the new one remains");
        // Can fill MAX_PAIRS again with the future day.
        for (int i = 1; i < ShadowExperienceLimitStore.MAX_PAIRS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, UUID.randomUUID(), futureDay, UUID.randomUUID(), 1L));
        }
        assertEquals(ShadowExperienceLimitStore.MAX_PAIRS, store.pairCount());
    }

    @Test
    void dateRollbackInvalidDateAndCleanupStillFullRejectsWithNoStateChange() {
        // Date rollback / invalid date → cleanup skipped → if still full →
        // REJECTED with zero state change.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        // Fill index with entries on a future date (no cleanup possible).
        String futureDay = retentionDay(5000);
        for (int i = 0; i < ShadowExperienceLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(thief, target, futureDay, UUID.randomUUID(), 10_000L));
        }
        int pairsBefore = store.pairCount();
        int reservationsBefore = store.reservationCount();
        // Invalid date → no cleanup.
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), "not-a-date", UUID.randomUUID(), 1L));
        assertEquals(pairsBefore, store.pairCount());
        assertEquals(reservationsBefore, store.reservationCount());
        // Same future date → no cleanup needed, still full.
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(thief, UUID.randomUUID(), futureDay, UUID.randomUUID(), 1L));
        assertEquals(pairsBefore, store.pairCount());
        assertEquals(reservationsBefore, store.reservationCount());
    }

    @Test
    void retentionBoundaryDay63KeepsDay0() {
        // Day 63: cutoff = 63 - 63 = 0. Day 0 is NOT strictly before 0,
        // so it's kept.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(0), eventId, 1L));
        assertTrue(store.commitReservation(eventId));
        // Reserve on day 63 → cutoff = day 0. Day 0 is NOT < day 0, so kept.
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(63), UUID.randomUUID(), 1L));
        assertEquals(1, store.occupiedCountForTest(thief, target, retentionDay(0)),
                "day 0 must be kept at boundary day 63");
        assertEquals(1, store.pairCount(),
                "same pair survives at boundary");
    }

    @Test
    void retentionBoundaryDay64CleansDay0() {
        // Day 64: cutoff = 64 - 63 = 1. Day 0 IS strictly before 1, so cleaned.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(0), eventId, 1L));
        assertTrue(store.commitReservation(eventId));
        // Reserve on day 64 → cutoff = day 1. Day 0 < day 1 → cleaned.
        UUID target2 = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target2, retentionDay(64), UUID.randomUUID(), 1L));
        assertEquals(0, store.occupiedCountForTest(thief, target, retentionDay(0)),
                "day 0 must be cleaned at boundary day 64");
    }

    @Test
    void retentionBoundaryDay65CleansDays0And1() {
        // Day 65: cutoff = 65 - 63 = 2. Days 0 and 1 are strictly before 2.
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID e0 = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(0), e0, 1L));
        assertTrue(store.commitReservation(e0));
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(1), e1, 1L));
        assertTrue(store.commitReservation(e1));
        // Reserve on day 65.
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(thief, target, retentionDay(65), UUID.randomUUID(), 1L));
        assertEquals(0, store.occupiedCountForTest(thief, target, retentionDay(0)));
        assertEquals(0, store.occupiedCountForTest(thief, target, retentionDay(1)));
        assertEquals(1, store.occupiedCountForTest(thief, target, retentionDay(65)));
    }
}
