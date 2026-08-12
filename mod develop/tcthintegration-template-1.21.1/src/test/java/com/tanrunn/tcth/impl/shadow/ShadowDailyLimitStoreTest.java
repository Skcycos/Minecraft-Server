package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.shadow.ShadowDailyLimitWriter.ReservationResult;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Unit tests for {@link ShadowDailyLimitStore} (8C.2.1 §2).
 *
 * <p>Covers the reservation protocol (reserve / commit / release, eventId
 * idempotency), the fail-closed inputs, the UTC-day scoping, the bounded
 * caps with deterministic eviction, the save-load round-trip (restart
 * persistence) and the corrupted-NBT defensive loading.
 */
class ShadowDailyLimitStoreTest {

    private static final String DAY = "2026-08-12";
    private static final String NEXT_DAY = "2026-08-13";

    private static HolderLookup.Provider provider() {
        MinecraftTestBootstrap.bootStrap();
        return HolderLookup.Provider.create(Stream.empty());
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        ShadowDailyLimitStore.resetForTesting();
    }

    @Test
    void reservationLifecycleReservesCommitsAndReleases() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();

        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, e1, 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, e2, 3L));
        assertFalse(store.isAtItemLimit(victim, DAY, 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, e3, 3L));
        assertTrue(store.isAtItemLimit(victim, DAY, 3L),
                "the third reservation reaches the cap");
        // A further reservation attempt is refused while at the cap.
        assertEquals(ReservationResult.LIMIT_REACHED,
                store.tryReserve(victim, DAY, UUID.randomUUID(), 3L));

        // Release frees the quota (clean failure / rollback path).
        assertTrue(store.releaseReservation(e1));
        assertFalse(store.isAtItemLimit(victim, DAY, 3L));
        assertFalse(store.releaseReservation(e1), "double release must be refused");

        // Commit keeps the quota occupied (SUCCESS path) — it must NOT free
        // a slot like releaseReservation does.
        assertTrue(store.commitReservation(e2));
        assertFalse(store.commitReservation(e2), "an already-committed id must be refused");
        assertEquals(2, store.occupiedCountForTest(victim, DAY),
                "committing must keep the quota occupied");
        assertTrue(store.isAtItemLimit(victim, DAY, 2L));
    }

    @Test
    void eventIdIdempotencyNeverDoubleCounts() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, eventId, 1L));
        // Same eventId again → still RESERVED, still one quota slot.
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, eventId, 1L));
        assertTrue(store.isAtItemLimit(victim, DAY, 1L));
        assertEquals(ReservationResult.LIMIT_REACHED,
                store.tryReserve(victim, DAY, UUID.randomUUID(), 1L),
                "the idempotent re-reserve must not have pushed the count to 2");
        assertTrue(store.commitReservation(eventId));
        // A committed eventId re-reserves as COMMITTED_EXISTING (idempotent).
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                store.tryReserve(victim, DAY, eventId, 3L));
        assertEquals(1, store.occupiedCountForTest(victim, DAY));
    }

    @Test
    void utcDayChangeResetsTheQuota() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY,
                UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY,
                UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY,
                UUID.randomUUID(), 3L));
        assertTrue(store.isAtItemLimit(victim, DAY, 3L));
        // The UTC day changes → a new bucket is available.
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, NEXT_DAY,
                UUID.randomUUID(), 3L));
        assertFalse(store.isAtItemLimit(victim, NEXT_DAY, 3L));
        assertTrue(store.isAtItemLimit(victim, DAY, 3L), "yesterday's quota stays occupied");
    }

    @Test
    void saveLoadRoundTripSurvivesRestart() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        store.tryReserve(victim, DAY, e1, 3L);
        store.tryReserve(victim, DAY, e2, 3L);
        assertTrue(store.commitReservation(e1));
        // e2 stays RESERVED (outstanding reservation survives the restart).

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertEquals(2, loaded.occupiedCountForTest(victim, DAY),
                "RESERVED and COMMITTED both persist");
        assertFalse(loaded.isAtItemLimit(victim, DAY, 3L));
        // Outstanding reservations stay releasable / committable after load.
        assertTrue(loaded.commitReservation(e2));
        assertEquals(2, loaded.occupiedCountForTest(victim, DAY),
                "the committed reservation keeps occupying the quota");
        assertEquals(ReservationResult.COMMITTED_EXISTING,
                loaded.tryReserve(victim, DAY, e1, 3L));
        // The next day is untouched by yesterday's quota.
        assertFalse(loaded.isAtItemLimit(victim, NEXT_DAY, 3L));
    }

    @Test
    void victimCapIsEnforced() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        for (int i = 0; i < ShadowDailyLimitStore.MAX_VICTIMS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        }
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L),
                "new victims beyond the cap must be refused");
        assertEquals(ShadowDailyLimitStore.MAX_VICTIMS, store.victimCount());
    }

    @Test
    void allReservedIndexFullRejectsFailClosed() {
        // 8C.2.2 §4: a full index with NOTHING settled may never evict (that
        // would reopen someone's quota) — it rejects, fail-closed.
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        for (int i = 0; i < ShadowDailyLimitStore.MAX_RESERVATIONS; i++) {
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(victim, DAY, UUID.randomUUID(), Long.MAX_VALUE));
        }
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS, store.reservationCount());
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(victim, DAY, UUID.randomUUID(), Long.MAX_VALUE),
                "a full all-RESERVED index must reject");
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), Long.MAX_VALUE),
                "new victims must also be rejected while every slot is RESERVED");
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victim, DAY),
                "the occupied aggregate must be untouched by the refusal");
    }

    @Test
    void committedIndexEvictionKeepsOccupiedCounts() {
        // 8C.2.2 §4: only an already-settled COMMITTED index entry may be
        // dropped for space — the occupied aggregate never shrinks.
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victimA = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ShadowDailyLimitStore.MAX_RESERVATIONS; i++) {
            UUID eventId = UUID.randomUUID();
            ids.add(eventId);
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(victimA, DAY, eventId, Long.MAX_VALUE));
        }
        for (UUID eventId : ids) {
            assertTrue(store.commitReservation(eventId));
        }
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS, store.reservationCount(),
                "a settled COMMITTED index stays full");
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victimA, DAY));

        // A new victim's reservation evicts one COMMITTED index entry only.
        UUID victimB = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED,
                store.tryReserve(victimB, DAY, UUID.randomUUID(), 5L));
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS, store.reservationCount(),
                "the index stays bounded");
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victimA, DAY),
                "A's quota must never reopen via index eviction");
        assertTrue(store.isAtItemLimit(victimA, DAY,
                ShadowDailyLimitStore.MAX_RESERVATIONS));
    }

    @Test
    void indexEvictionNeverReopensAnyVictimQuota() {
        // 8C.2.2 §4 stress: limit 10000, aggregates far beyond the 4096
        // index cap across victims — no victim's quota reopens.
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        long limit = 10_000L;
        UUID victimA = UUID.randomUUID();
        UUID victimB = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ShadowDailyLimitStore.MAX_RESERVATIONS; i++) {
            UUID eventId = UUID.randomUUID();
            ids.add(eventId);
            assertEquals(ReservationResult.RESERVED,
                    store.tryReserve(victimA, DAY, eventId, limit));
        }
        for (UUID eventId : ids) {
            assertTrue(store.commitReservation(eventId));
        }
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victimA, DAY));
        // B fills past the cap (each reservation drops one settled entry)
        // and settles its own round, so the index holds only COMMITTED
        // entries again and a further round can keep evicting.
        List<UUID> bIds = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < ShadowDailyLimitStore.MAX_RESERVATIONS; i++) {
                UUID eventId = UUID.randomUUID();
                bIds.add(eventId);
                assertEquals(ReservationResult.RESERVED,
                        store.tryReserve(victimB, DAY, eventId, limit));
            }
            for (UUID eventId : bIds) {
                store.commitReservation(eventId);
            }
            bIds.clear();
        }
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS, store.reservationCount());
        assertEquals(ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victimA, DAY),
                "A's quota must never reopen");
        assertEquals(2L * ShadowDailyLimitStore.MAX_RESERVATIONS,
                store.occupiedCountForTest(victimB, DAY),
                "B's aggregate keeps growing past the index cap");
        assertTrue(store.isAtItemLimit(victimB, DAY, 2L * ShadowDailyLimitStore.MAX_RESERVATIONS));
        assertFalse(store.isAtItemLimit(victimB, DAY,
                2L * ShadowDailyLimitStore.MAX_RESERVATIONS + 1L));
    }

    @Test
    void eventIdReuseWithDifferentVictimOrDayIsRejected() {
        // 8C.2.2 §4: an eventId bound to a different victim or day is a
        // hijack attempt — rejected, never idempotent.
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, eventId, 3L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), DAY, eventId, 3L),
                "same eventId, different victim → rejected");
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(victim, NEXT_DAY, eventId, 3L),
                "same eventId, different day → rejected");
        assertEquals(ReservationResult.RESERVED, store.tryReserve(victim, DAY, eventId, 3L),
                "same eventId, same victim/day stays idempotent");
        assertEquals(1, store.occupiedCountForTest(victim, DAY),
                "the idempotent re-reserve must not double-count");
    }

    @Test
    void corruptedNbtNeverShrinksOccupiedCounts() {
        // 8C.2.2 §4 load hardening: the aggregates bucket is missing entirely
        // (empty victims list) but reservations survive — the load must
        // conservatively back-fill the occupied counts instead of letting
        // the quota shrink to zero.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        tag.put("victims", new ListTag()); // v2 requires the list; empty is legal
        UUID victim = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        ListTag reservations = new ListTag();
        for (UUID eventId : new UUID[] { e1, e2 }) {
            CompoundTag r = new CompoundTag();
            r.putUUID("eventId", eventId);
            r.putUUID("victim", victim);
            r.putString("day", DAY);
            r.putString("state", "RESERVED");
            reservations.add(r);
        }
        tag.put("reservations", reservations);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertEquals(2, loaded.occupiedCountForTest(victim, DAY),
                "loaded reservations must conservatively back-fill the aggregates");
        assertTrue(loaded.isAtItemLimit(victim, DAY, 2L));
        assertTrue(loaded.commitReservation(e1), "the back-filled reservations stay usable");
        assertEquals(2, loaded.occupiedCountForTest(victim, DAY));
        assertFalse(loaded.isAtItemLimit(victim, NEXT_DAY, 1L),
                "the back-fill must be scoped to the reservation's own day");
    }

    @Test
    void invalidInputsFailClosed() {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        UUID victim = UUID.randomUUID();
        assertEquals(ReservationResult.REJECTED, store.tryReserve(null, DAY,
                UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(victim, DAY, null, 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(victim, DAY,
                UUID.randomUUID(), 0L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(victim, "",
                UUID.randomUUID(), 3L));
        assertEquals(ReservationResult.REJECTED, store.tryReserve(victim, "2026-13-99",
                UUID.randomUUID(), 3L), "non-ISO dates must be rejected");
        assertTrue(store.isAtItemLimit(victim, "", 3L),
                "the quota check must fail closed on an invalid date");
        assertTrue(store.isAtItemLimit(null, DAY, 3L));
        assertEquals(0, store.victimCount(), "rejected attempts must leave no state");
    }

    @Test
    void corruptedNbtFailsClosedInsteadOfSkipping() {
        // 8C.2.4 §4: invalid UUIDs, invalid days, negative counts and
        // invalid reservations are storage damage — fail closed, never
        // silently skipped (a skipped entry could reopen quota).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 2);
        ListTag list = new ListTag();
        CompoundTag badUuid = new CompoundTag();
        badUuid.putString("victim", "not-a-uuid");
        list.add(badUuid);
        CompoundTag badDay = new CompoundTag();
        badDay.putUUID("victim", UUID.randomUUID());
        ListTag days = new ListTag();
        CompoundTag dayEntry = new CompoundTag();
        dayEntry.putString("day", "");
        dayEntry.putInt("count", -5); // negative count
        days.add(dayEntry);
        badDay.put("days", days);
        list.add(badDay);
        tag.put("victims", list);
        CompoundTag badReservation = new CompoundTag();
        badReservation.putString("eventId", "not-a-uuid");
        ListTag reservations = new ListTag();
        reservations.add(badReservation);
        tag.put("reservations", reservations);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "any invalid payload must fail the store closed");
        assertTrue(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
        assertEquals(ReservationResult.REJECTED,
                loaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    @Test
    void invalidReservationStateFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.putBoolean("failClosed", false);
        tag.putBoolean("failClosed", false);
        ListTag reservations = new ListTag();
        reservations.add(reservationEntry(UUID.randomUUID(), UUID.randomUUID(), DAY, "NOT_A_STATE"));
        tag.put("reservations", reservations);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "an invalid reservation state must fail closed");
        assertEquals(ReservationResult.REJECTED,
                loaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    @Test
    void missingRequiredFieldsFailClosed() {
        // A victim entry without a days field cannot be expressed → fail closed.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        ListTag list = new ListTag();
        CompoundTag noDays = new CompoundTag();
        noDays.putUUID("victim", UUID.randomUUID());
        list.add(noDays);
        tag.put("victims", list);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a missing days field must fail closed");
        assertTrue(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
    }

    @Test
    void failClosedStoreKeepsEveryItemPathConservative() {
        // 8C.2.4 §4: once fail-closed, query/reserve/commit/release all
        // stay conservatively refused.
        CompoundTag damaged = new CompoundTag();
        damaged.putInt("dataVersion", 99); // future version → fail closed
        ShadowDailyLimitStore store = ShadowDailyLimitStore.load(damaged, provider());
        assertTrue(store.isFailClosed());
        assertTrue(store.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
        assertEquals(ReservationResult.REJECTED,
                store.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        assertFalse(store.commitReservation(UUID.randomUUID()));
        assertFalse(store.releaseReservation(UUID.randomUUID()));
    }

    @Test
    void unknownFutureVersionFailsClosed() {
        // 8C.2.3 §3: a future/illegal version must NEVER load as an empty
        // store (that would silently allow theft against unknown semantics).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 99);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a future version must fail closed");
        assertTrue(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L),
                "fail-closed stores refuse every ITEM check");
        assertEquals(ReservationResult.REJECTED,
                loaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    @Test
    void legacyV1AndV2DataStillLoadsAndWorks() {
        // v1/v2 payloads migrate cleanly under the strict schema: v1 has no
        // reservations/failClosed; v2 must carry a reservations list.
        for (int version : new int[] { 1, 2 }) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("dataVersion", version);
            if (version >= 2) {
                tag.put("reservations", new ListTag()); // required for v2
            }
            UUID victim = UUID.randomUUID();
            ListTag list = new ListTag();
            CompoundTag entry = new CompoundTag();
            entry.putUUID("victim", victim);
            ListTag days = new ListTag();
            CompoundTag dayEntry = new CompoundTag();
            dayEntry.putString("day", DAY);
            dayEntry.putInt("count", 2);
            days.add(dayEntry);
            entry.put("days", days);
            list.add(entry);
            tag.put("victims", list);

            ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
            assertFalse(loaded.isFailClosed(), "legacy v" + version + " data must load cleanly");
            assertEquals(2, loaded.occupiedCountForTest(victim, DAY));
            assertEquals(ReservationResult.RESERVED,
                    loaded.tryReserve(victim, DAY, UUID.randomUUID(), 3L),
                    "a migrated store must still reserve");
        }
    }

    @Test
    void failClosedFlagSurvivesSaveAndReload() {
        CompoundTag damaged = new CompoundTag();
        damaged.putInt("dataVersion", 99); // future version → fail closed
        ShadowDailyLimitStore store = ShadowDailyLimitStore.load(damaged, provider());
        assertTrue(store.isFailClosed());

        CompoundTag tag = store.save(new CompoundTag(), provider());
        ShadowDailyLimitStore reloaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(reloaded.isFailClosed(), "the fail-closed flag must persist");
        assertTrue(reloaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
        assertEquals(ReservationResult.REJECTED,
                reloaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        assertFalse(reloaded.releaseReservation(UUID.randomUUID()),
                "a fail-closed store must never release quota");

        // A healthy store saves and reloads WITHOUT the flag.
        ShadowDailyLimitStore healthy = new ShadowDailyLimitStore();
        assertEquals(ReservationResult.RESERVED,
                healthy.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        ShadowDailyLimitStore healthyReloaded =
                ShadowDailyLimitStore.load(healthy.save(new CompoundTag(), provider()), provider());
        assertFalse(healthyReloaded.isFailClosed());
    }

    @Test
    void duplicateVictimEntriesMergeNeverLastWriteWins() {
        // 8C.2.3 §4: the same UUID in two entries merges — the second entry
        // (count 1) must NOT overwrite the first (count 10 on the same day).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.put("reservations", new ListTag()); // v3 requires the list
        UUID victim = UUID.randomUUID();
        ListTag list = new ListTag();
        list.add(victimEntry(victim, DAY, 10));
        list.add(victimEntry(victim, DAY, 1));
        tag.put("victims", list);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertFalse(loaded.isFailClosed());
        assertEquals(10, loaded.occupiedCountForTest(victim, DAY),
                "duplicate victim entries must merge with max, not last-write-wins");
        assertTrue(loaded.isAtItemLimit(victim, DAY, 10L));
    }

    @Test
    void duplicateDayCountsTakeTheMax() {
        // Same victim entry carrying the same day twice: 10 then 1 → 10.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.put("reservations", new ListTag()); // v3 requires the list
        UUID victim = UUID.randomUUID();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("victim", victim);
        ListTag days = new ListTag();
        days.add(dayEntry(DAY, 10));
        days.add(dayEntry(DAY, 1));
        entry.put("days", days);
        list.add(entry);
        tag.put("victims", list);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertFalse(loaded.isFailClosed());
        assertEquals(10, loaded.occupiedCountForTest(victim, DAY),
                "duplicate days within one entry must take the max");
    }

    @Test
    void conflictingDuplicateEventIdFailsClosedGlobally() {
        // 8C.2.3 §4: the same eventId with a DIFFERENT victim/day/state is
        // storage damage — never "pick the last one"; the whole store fails
        // closed so no quota reopens.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        UUID eventId = UUID.randomUUID();
        ListTag reservations = new ListTag();
        reservations.add(reservationEntry(eventId, UUID.randomUUID(), DAY, "RESERVED"));
        reservations.add(reservationEntry(eventId, UUID.randomUUID(), DAY, "COMMITTED"));
        tag.put("reservations", reservations);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a conflicting duplicate eventId must mark the store damaged");
        assertTrue(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L),
                "after damage every ITEM attempt must be refused");
        assertEquals(ReservationResult.REJECTED,
                loaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
        assertTrue(loaded.reservationCount() <= 1,
                "damaged stores keep at most the already-loaded entries, never the conflicting one");
    }

    @Test
    void reservationVictimsBeyondCapFailClosedBounded() {
        // 8C.2.3 §4: 1024+ distinct reservation victims with empty aggregates
        // — the back-fill must not blow the victim cap; the store fails
        // closed AND stays memory-bounded.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        ListTag reservations = new ListTag();
        for (int i = 0; i <= ShadowDailyLimitStore.MAX_VICTIMS; i++) {
            reservations.add(reservationEntry(UUID.randomUUID(), UUID.randomUUID(), DAY, "RESERVED"));
        }
        tag.put("reservations", reservations);

        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "reservation victims beyond the cap must fail closed");
        assertTrue(loaded.victimCount() <= ShadowDailyLimitStore.MAX_VICTIMS,
                "the aggregates must stay memory-bounded");
        assertTrue(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
        assertEquals(ReservationResult.REJECTED,
                loaded.tryReserve(UUID.randomUUID(), DAY, UUID.randomUUID(), 3L));
    }

    private static CompoundTag victimEntry(UUID victim, String day, int count) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("victim", victim);
        ListTag days = new ListTag();
        days.add(dayEntry(day, count));
        entry.put("days", days);
        return entry;
    }

    private static CompoundTag dayEntry(String day, int count) {
        CompoundTag dayEntry = new CompoundTag();
        dayEntry.putString("day", day);
        dayEntry.putInt("count", count);
        return dayEntry;
    }

    private static CompoundTag reservationEntry(UUID eventId, UUID victim, String day, String state) {
        CompoundTag r = new CompoundTag();
        r.putUUID("eventId", eventId);
        r.putUUID("victim", victim);
        r.putString("day", day);
        r.putString("state", state);
        return r;
    }

    // ---- 8C.2.5 strict versioned schema ----

    @Test
    void missingDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.put("victims", new ListTag());
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a missing dataVersion must fail closed");
    }

    @Test
    void mistypedDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dataVersion", "3"); // TAG_STRING instead of TAG_INT
        tag.put("victims", new ListTag());
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a mistyped dataVersion must fail closed");
    }

    @Test
    void minimalV1V2V3SchemasLoadHealthy() {
        // v1: victims only; v2: + reservations; v3: + failClosed TAG_BYTE.
        for (int version : new int[] { 1, 2, 3 }) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("dataVersion", version);
            tag.put("victims", new ListTag());
            if (version >= 2) {
                tag.put("reservations", new ListTag());
            }
            if (version >= 3) {
                tag.putBoolean("failClosed", false);
            }
            ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
            assertFalse(loaded.isFailClosed(), "the minimal legal v" + version + " schema must load");
            assertFalse(loaded.isAtItemLimit(UUID.randomUUID(), DAY, 1L));
        }
    }

    @Test
    void missingVictimsListFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        // no victims key at all
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a missing victims list must fail closed");
    }

    @Test
    void mistypedVictimsListFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 1);
        tag.putString("victims", "not-a-list");
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a mistyped victims key must fail closed");
    }

    @Test
    void missingCountNeverReadsAsZero() {
        // 8C.2.5 §3: a day entry without a count must fail closed — never
        // default to 0 (which would silently drop quota).
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.put("reservations", new ListTag());
        UUID victim = UUID.randomUUID();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("victim", victim);
        ListTag days = new ListTag();
        CompoundTag dayEntry = new CompoundTag();
        dayEntry.putString("day", DAY);
        // count missing entirely
        days.add(dayEntry);
        entry.put("days", days);
        list.add(entry);
        tag.put("victims", list);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a missing count must fail closed, not read as 0");
    }

    @Test
    void mistypedCountFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.put("reservations", new ListTag());
        UUID victim = UUID.randomUUID();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("victim", victim);
        ListTag days = new ListTag();
        CompoundTag dayEntry = new CompoundTag();
        dayEntry.putString("day", DAY);
        dayEntry.putString("count", "2"); // TAG_STRING instead of TAG_INT
        days.add(dayEntry);
        entry.put("days", days);
        list.add(entry);
        tag.put("victims", list);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a mistyped count must fail closed");
    }

    @Test
    void missingReservationStateFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 3);
        tag.putBoolean("failClosed", false);
        tag.put("victims", new ListTag());
        ListTag reservations = new ListTag();
        CompoundTag r = new CompoundTag();
        r.putUUID("eventId", UUID.randomUUID());
        r.putUUID("victim", UUID.randomUUID());
        r.putString("day", DAY);
        // state missing entirely
        reservations.add(r);
        tag.put("reservations", reservations);
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a missing reservation state must fail closed");
    }

    // ---- 8C.2.6 dataVersion boundary ----

    @Test
    void zeroDataVersionFailsClosed() {
        // 8C.2.6 §1: version 0 is never a valid schema version.
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 0);
        tag.put("victims", new ListTag());
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "dataVersion=0 must fail closed");
    }

    @Test
    void negativeDataVersionFailsClosed() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", -1);
        tag.put("victims", new ListTag());
        ShadowDailyLimitStore loaded = ShadowDailyLimitStore.load(tag, provider());
        assertTrue(loaded.isFailClosed(), "a negative dataVersion must fail closed");
    }
}
