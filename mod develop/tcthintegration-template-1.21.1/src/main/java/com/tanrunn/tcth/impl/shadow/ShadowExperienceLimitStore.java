package com.tanrunn.tcth.impl.shadow;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-(thief, target)-pair daily job-experience quota with an
 * eventId-idempotent reservation protocol (phase 8E §8).
 *
 * <p>Only PLAYER targets are limited by this store (entity targets stay
 * bounded by the LOOTED once-state). Protocol:
 * <ul>
 *   <li>{@link #tryReserve} — called BEFORE the Arc action send; a successful
 *       reservation occupies the quota immediately ({@code RESERVED});</li>
 *   <li>{@link #commitReservation} — called after a successful Arc send; the
 *       reservation moves to {@code COMMITTED} and keeps occupying the
 *       quota;</li>
 *   <li>{@link #releaseReservation} — called when the Arc send <em>clearly
 *       failed</em>; frees the quota so the attempt can be retried. Only a
 *       same-JVM {@code RESERVED} entry is releaseable;</li>
 *   <li>a failed/unknown commit keeps the slot occupied — duplicate XP is
 *       never granted (conservative).</li>
 * </ul>
 *
 * <p><strong>Crash recovery (8E.1):</strong> a {@code RESERVED} entry found
 * on disk at load time describes a send whose outcome is UNKNOWN (the crash
 * may have happened before, during or after the Arc send). Loading migrates
 * such entries to {@code RECOVERY}: the quota stays occupied, the same
 * eventId is refused with {@link ReservationResult#RECOVERY_EXISTING} (never
 * re-sent, never released as a clean failure). {@code COMMITTED} and
 * {@code RECOVERY} entries can never be released through the failed-send
 * path.
 *
 * <p>Fail-closed: storage-full, invalid dates, {@code false} returns and
 * exceptions all forbid the XP reward. The UTC date string is captured ONCE
 * per attempt by the caller (injectable date supplier for tests).
 *
 * <p>Safety rules: bounded (pairs / days / reservations), deterministic
 * oldest-date eviction, saturated integer counts, strict ISO date parsing on
 * load and reserve, strict schema loading — an invalid UUID, invalid date,
 * negative count, invalid reservation state or missing required field marks
 * the store damaged ({@code failClosed} persisted flag): every query/reserve/
 * commit/release then stays conservatively refused. Future data versions and
 * payloads that cannot be expressed within the capacity bounds fail closed
 * the same way.
 *
 * <p><b>Rolling retention (8E.2.4):</b> COMMITTED eventId entries are
 * retained for at most {@value #RETENTION_DAYS} UTC days. Older fully-settled
 * COMMITTED entries are cleaned during {@link #tryReserve}, freeing index
 * and pair capacity. RESERVED / RECOVERY entries are never auto-cleaned.
 * The eventId idempotency window is therefore bounded to 64 UTC days, not
 * permanent.
 *
 * <p><b>Crash-consistency limitation (documented):</b> a plain SavedData is
 * not an fsync WAL; the very last write may be lost on a crash. The
 * conservative direction is always "quota stays occupied".
 */
public final class ShadowExperienceLimitStore extends SavedData implements ShadowExperienceLimitWriter {

    public static final String NAME = "tcth_shadow_experience_limits";
    /** v2 (8E.1): RESERVED-on-load migrates to RECOVERY. v1 data still loads
     *  (the same migration applies). */
    public static final int DATA_VERSION = 2;
    /** Maximum tracked (thief, target) pairs. */
    public static final int MAX_PAIRS = 1024;
    /** Maximum tracked days per pair. */
    public static final int MAX_DAYS_PER_PAIR = 64;
    /** Maximum outstanding reservations (eventId entries). */
    public static final int MAX_RESERVATIONS = 4096;
    /**
     * Rolling retention window for COMMITTED eventId entries. The idempotency
     * guarantee is bounded to {@code RETENTION_DAYS} UTC days (64), not
     * permanent. COMMITTED entries on dates older than (current UTC day − 63)
     * are eligible for cleanup during {@link #tryReserve}. RESERVED and
     * RECOVERY entries are <strong>never</strong> auto-cleaned — unsettled
     * records persist until explicitly resolved or the store is replaced.
     */
    public static final int RETENTION_DAYS = MAX_DAYS_PER_PAIR;

    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_PAIRS = "pairs";
    private static final String KEY_RESERVATIONS = "reservations";
    private static final String KEY_FAIL_CLOSED = "failClosed";

    /**
     * Reservation lifecycle states.
     *
     * <p>{@code RESERVED} exists only inside one JVM session: it is created
     * by {@link #tryReserve} and either committed (send succeeded),
     * released (send clearly failed) or left on disk by a crash. A persisted
     * {@code RESERVED} is ALWAYS migrated to {@link #RECOVERY} on load — its
     * outcome is unknown and the quota must stay occupied.
     */
    private enum ReservationState {
        RESERVED, COMMITTED, RECOVERY
    }

    /** Stable pair key — UUIDs only, never player names. */
    record PairKey(UUID thiefId, UUID targetId) {
        PairKey {
            Objects.requireNonNull(thiefId, "thiefId");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    private record ReservationEntry(UUID thiefId, UUID targetId, String utcDay, ReservationState state) {
    }

    /**
     * Complete result of the read-only reservation planning pass. The maps
     * already include the new RESERVED entry; applying this plan is the only
     * mutating step in tryReserve.
     */
    private record ReservationPlan(Map<PairKey, Map<String, Integer>> occupied,
                                   LinkedHashMap<UUID, ReservationEntry> reservations) {
    }

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Occupied quota per pair per UTC day (RESERVED + COMMITTED). */
    private final Map<PairKey, Map<String, Integer>> occupied = new HashMap<>();
    /** eventId → reservation (insertion order for deterministic cleanup).
     *  Index entries are freed by 64-day retention cleanup and, when needed,
     *  safe date rotation. */
    private final Map<UUID, ReservationEntry> reservations = new LinkedHashMap<>(64, 0.75f);
    /** Persisted damage/saturation marker: once set, every XP query/reserve/
     *  commit/release stays conservatively refused. */
    private boolean failClosed;

    private static java.util.function.Supplier<LocalDate> dateSupplier =
            () -> LocalDate.now(ZoneOffset.UTC);

    public static final Factory<ShadowExperienceLimitStore> FACTORY = new Factory<>(
            ShadowExperienceLimitStore::new, ShadowExperienceLimitStore::load, null);

    /** Returns the store bound to the overworld data storage. */
    public static ShadowExperienceLimitStore current(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public ShadowExperienceLimitStore() {
    }

    @Override
    public synchronized ReservationResult tryReserve(UUID thiefId, UUID targetId, String utcDay,
                                                     UUID eventId, long limit) {
        if (failClosed) {
            return ReservationResult.REJECTED; // damaged: never reopen
        }
        if (thiefId == null || targetId == null || eventId == null || limit <= 0L
                || !isStrictIsoDate(utcDay)) {
            return ReservationResult.REJECTED; // fail closed
        }
        // eventId idempotency: the same attempt never reserves twice. An
        // eventId bound to a different pair or UTC day is a hijack attempt:
        // fail closed.
        ReservationEntry existing = reservations.get(eventId);
        if (existing != null) {
            if (!existing.thiefId.equals(thiefId) || !existing.targetId.equals(targetId)
                    || !existing.utcDay.equals(utcDay)) {
                return ReservationResult.REJECTED;
            }
            return switch (existing.state) {
                case RESERVED -> ReservationResult.RESERVED; // same-JVM retry
                case COMMITTED -> ReservationResult.COMMITTED_EXISTING;
                case RECOVERY -> ReservationResult.RECOVERY_EXISTING;
            };
        }
        // ---- Read-only pre-guards — no mutation until all checks pass ----
        PairKey pair = new PairKey(thiefId, targetId);
        Map<String, Integer> existingDays = occupied.get(pair);
        // LIMIT_REACHED is deliberately checked against the current state
        // before planning. A rejected request must not perform maintenance.
        int currentDayCount = existingDays == null ? 0 : existingDays.getOrDefault(utcDay, 0);
        if (currentDayCount >= limit || currentDayCount >= Integer.MAX_VALUE) {
            return ReservationResult.LIMIT_REACHED;
        }

        ReservationPlan plan;
        try {
            plan = planReservation(pair, utcDay, eventId, thiefId, targetId, limit);
        } catch (RuntimeException | LinkageError e) {
            return ReservationResult.REJECTED;
        }
        if (plan == null) {
            return ReservationResult.REJECTED;
        }

        // ---- One-time application of the accepted read-only plan ----
        occupied.clear();
        plan.occupied.forEach((plannedPair, plannedDays) ->
                occupied.put(plannedPair, new HashMap<>(plannedDays)));
        reservations.clear();
        reservations.putAll(plan.reservations);
        setDirty();
        return ReservationResult.RESERVED;
    }

    /**
     * Builds a complete virtual post-state. Retention cleanup and date
     * rotation happen only in these copies, so any later rejection leaves the
     * live SavedData byte-for-byte unchanged.
     */
    private ReservationPlan planReservation(PairKey pair, String utcDay, UUID eventId,
                                             UUID thiefId, UUID targetId, long limit) {
        Map<PairKey, Map<String, Integer>> plannedOccupied = copyOccupied();
        LinkedHashMap<UUID, ReservationEntry> plannedReservations = copyReservations();
        String retentionCutoff = computeRetentionCutoff(utcDay);
        if (retentionCutoff == null) {
            return null;
        }

        expireOldCommittedEntries(plannedOccupied, plannedReservations, retentionCutoff);

        Map<String, Integer> plannedDays = plannedOccupied.get(pair);
        if (plannedDays != null && !plannedDays.containsKey(utcDay)
                && plannedDays.size() >= MAX_DAYS_PER_PAIR) {
            String rotationOldest = oldestDay(plannedDays);
            if (rotationOldest == null
                    || hasUnsettledReservations(plannedReservations, pair, rotationOldest)) {
                return null;
            }
            removeReservationsFor(plannedReservations, pair, rotationOldest);
            plannedDays.remove(rotationOldest);
            if (plannedDays.isEmpty()) {
                plannedOccupied.remove(pair);
                plannedDays = null;
            }
        }

        if (plannedOccupied.get(pair) == null && plannedOccupied.size() >= MAX_PAIRS) {
            return null;
        }
        if (plannedReservations.size() >= MAX_RESERVATIONS) {
            return null;
        }
        plannedDays = plannedOccupied.get(pair);
        if (plannedDays != null && !plannedDays.containsKey(utcDay)
                && plannedDays.size() >= MAX_DAYS_PER_PAIR) {
            return null;
        }

        int dayCount = plannedDays == null ? 0 : plannedDays.getOrDefault(utcDay, 0);
        if (dayCount >= limit || dayCount >= Integer.MAX_VALUE) {
            return null;
        }

        if (plannedDays == null) {
            plannedDays = new HashMap<>();
            plannedOccupied.put(pair, plannedDays);
        }
        plannedDays.put(utcDay, dayCount + 1);
        plannedReservations.put(eventId, new ReservationEntry(thiefId, targetId, utcDay,
                ReservationState.RESERVED));
        return new ReservationPlan(plannedOccupied, plannedReservations);
    }

    @Override
    public synchronized boolean commitReservation(UUID eventId) {
        if (failClosed) {
            return false;
        }
        ReservationEntry entry = reservations.get(eventId);
        if (entry == null || entry.state != ReservationState.RESERVED) {
            return false;
        }
        reservations.put(eventId, new ReservationEntry(entry.thiefId, entry.targetId,
                entry.utcDay, ReservationState.COMMITTED));
        setDirty();
        return true;
    }

    @Override
    public synchronized boolean releaseReservation(UUID eventId) {
        if (failClosed) {
            return false; // never free quota on damaged data
        }
        ReservationEntry entry = reservations.get(eventId);
        // Only a same-JVM RESERVED entry (a clearly failed Arc send) is
        // releaseable. COMMITTED and RECOVERY describe an already-granted or
        // unknown outcome — they must NEVER be released through the
        // failed-send path (8E.1 §1.4).
        if (entry == null || entry.state != ReservationState.RESERVED) {
            return false;
        }
        reservations.remove(eventId);
        Map<String, Integer> days = occupied.get(new PairKey(entry.thiefId, entry.targetId));
        if (days != null) {
            int count = days.getOrDefault(entry.utcDay, 0);
            if (count <= 1) {
                days.remove(entry.utcDay);
                if (days.isEmpty()) {
                    occupied.remove(new PairKey(entry.thiefId, entry.targetId));
                }
            } else {
                days.put(entry.utcDay, count - 1);
            }
        }
        setDirty();
        return true;
    }

    @Override
    public synchronized boolean isAtPairLimit(UUID thiefId, UUID targetId, String utcDay, long limit) {
        if (failClosed) {
            return true; // damaged: every XP attempt refused
        }
        if (thiefId == null || targetId == null || limit <= 0L || !isStrictIsoDate(utcDay)) {
            return true; // fail closed
        }
        Map<String, Integer> days = occupied.get(new PairKey(thiefId, targetId));
        if (days == null) {
            return false;
        }
        int count = days.getOrDefault(utcDay, 0);
        return count >= limit || count >= Integer.MAX_VALUE;
    }

    // ---- internals ----

    /**
     * Computes the retention cutoff date: any COMMITTED reservation with a
     * UTC day strictly before this cutoff is eligible for cleanup. Returns
     * {@code null} when the input date is invalid (cleanup is skipped).
     */
    private static String computeRetentionCutoff(String utcDay) {
        if (!isStrictIsoDate(utcDay)) {
            return null;
        }
        try {
            LocalDate current = LocalDate.parse(utcDay, ISO_DATE);
            LocalDate cutoff = current.minusDays(RETENTION_DAYS - 1);
            return cutoff.format(ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Removes all COMMITTED reservation entries and their occupied aggregates
     * for dates strictly before {@code retentionCutoff}. Pairs that become
     * empty after cleanup are removed from {@link #occupied}.
     *
     * <p>RESERVED and RECOVERY entries are <strong>never</strong> touched —
     * unsettled records persist until explicitly resolved. Occupied aggregates
     * for dates that still carry RESERVED/RECOVERY entries are preserved.
     *
     * <p>Only operates on the virtual planning copies used by
     * {@link #tryReserve}; the live store is changed only when the complete
     * reservation plan is accepted.
     */
    private void expireOldCommittedEntries(Map<PairKey, Map<String, Integer>> targetOccupied,
                                           Map<UUID, ReservationEntry> targetReservations,
                                           String retentionCutoff) {
        Iterator<Map.Entry<UUID, ReservationEntry>> rit = targetReservations.entrySet().iterator();
        while (rit.hasNext()) {
            ReservationEntry entry = rit.next().getValue();
            if (entry.state == ReservationState.COMMITTED
                    && entry.utcDay.compareTo(retentionCutoff) < 0) {
                rit.remove();
            }
        }
        // For each pair, remove expired dates from the occupied aggregate
        // ONLY if no RESERVED/RECOVERY entries remain on that date.
        Iterator<Map.Entry<PairKey, Map<String, Integer>>> oit = targetOccupied.entrySet().iterator();
        while (oit.hasNext()) {
            Map.Entry<PairKey, Map<String, Integer>> pairEntry = oit.next();
            PairKey pk = pairEntry.getKey();
            Map<String, Integer> days = pairEntry.getValue();
            Iterator<String> dit = days.keySet().iterator();
            while (dit.hasNext()) {
                String d = dit.next();
                if (d.compareTo(retentionCutoff) >= 0) {
                    continue; // within retention window
                }
                if (hasAnyReservationOnDay(targetReservations, pk, d)) {
                    continue; // RESERVED/RECOVERY still present
                }
                dit.remove();
            }
            if (days.isEmpty()) {
                oit.remove();
            }
        }
    }

    /**
     * Whether any reservation entry (any state) exists for the given pair
     * and day. Used during retention cleanup to avoid removing occupied
     * aggregates that still have RESERVED/RECOVERY entries.
     */
    private static boolean hasAnyReservationOnDay(Map<UUID, ReservationEntry> sourceReservations,
                                                  PairKey pair, String utcDay) {
        for (ReservationEntry entry : sourceReservations.values()) {
            if (entry.utcDay.equals(utcDay)
                    && entry.thiefId.equals(pair.thiefId())
                    && entry.targetId.equals(pair.targetId())) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} for an empty list or a list whose elements are all
     *  {@code TAG_COMPOUND} — anything else is a schema error. */
    private static boolean isCompoundList(Tag tag) {
        if (!(tag instanceof ListTag list)) {
            return false;
        }
        int elementType = list.getElementType();
        return elementType == Tag.TAG_END || elementType == Tag.TAG_COMPOUND;
    }

    /** Strict ISO date validation (no lenient parsing). */
    static boolean isStrictIsoDate(String day) {
        if (day == null || day.isEmpty()) {
            return false;
        }
        try {
            LocalDate parsed = LocalDate.parse(day, ISO_DATE);
            return parsed.format(ISO_DATE).equals(day);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Deterministic oldest date of the pair's aggregate days (ISO dates sort
     *  lexicographically), or {@code null} when the map is empty. */
    private static String oldestDay(Map<String, Integer> days) {
        String oldest = null;
        for (String day : days.keySet()) {
            if (oldest == null || day.compareTo(oldest) < 0) {
                oldest = day;
            }
        }
        return oldest;
    }

    /** Whether the pair has any UNSETTLED (RESERVED / RECOVERY) reservation
     *  on the given day — such records must never be silently dropped
     *  (8E.1 §2.2). */
    private static boolean hasUnsettledReservations(Map<UUID, ReservationEntry> sourceReservations,
                                                    PairKey pair, String utcDay) {
        for (ReservationEntry entry : sourceReservations.values()) {
            if (!entry.utcDay.equals(utcDay)
                    || !entry.thiefId.equals(pair.thiefId())
                    || !entry.targetId.equals(pair.targetId())) {
                continue;
            }
            if (entry.state == ReservationState.RESERVED
                    || entry.state == ReservationState.RECOVERY) {
                return true;
            }
        }
        return false;
    }

    /** Removes the pair's reservation-index entries for the given day. Only
     *  called after {@link #hasUnsettledReservations} returned {@code false},
     *  so every removed entry is a settled COMMITTED one — the occupied
     *  aggregates are already removed by the caller, the quota never
     *  reopens. */
    private static void removeReservationsFor(Map<UUID, ReservationEntry> sourceReservations,
                                              PairKey pair, String utcDay) {
        Iterator<Map.Entry<UUID, ReservationEntry>> it = sourceReservations.entrySet().iterator();
        while (it.hasNext()) {
            ReservationEntry entry = it.next().getValue();
            if (entry.utcDay.equals(utcDay)
                    && entry.thiefId.equals(pair.thiefId())
                    && entry.targetId.equals(pair.targetId())) {
                it.remove();
            }
        }
    }

    private Map<PairKey, Map<String, Integer>> copyOccupied() {
        Map<PairKey, Map<String, Integer>> copy = new HashMap<>();
        occupied.forEach((pair, days) -> copy.put(pair, new HashMap<>(days)));
        return copy;
    }

    private LinkedHashMap<UUID, ReservationEntry> copyReservations() {
        return new LinkedHashMap<>(reservations);
    }

    /** Captures the current UTC date string once per caller attempt. */
    public static String today() {
        try {
            LocalDate date = dateSupplier.get();
            return date == null ? "" : date.format(ISO_DATE);
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_VERSION, DATA_VERSION);
        tag.putBoolean(KEY_FAIL_CLOSED, failClosed);
        ListTag pairsList = new ListTag();
        occupied.forEach((pair, days) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("thiefId", pair.thiefId());
            entry.putUUID("targetId", pair.targetId());
            ListTag daysTag = new ListTag();
            days.forEach((day, count) -> {
                CompoundTag dayEntry = new CompoundTag();
                dayEntry.putString("day", day);
                dayEntry.putInt("count", count);
                daysTag.add(dayEntry);
            });
            entry.put("days", daysTag);
            pairsList.add(entry);
        });
        tag.put(KEY_PAIRS, pairsList);
        ListTag reservationList = new ListTag();
        reservations.forEach((eventId, r) -> {
            CompoundTag rTag = new CompoundTag();
            rTag.putUUID("eventId", eventId);
            rTag.putUUID("thiefId", r.thiefId);
            rTag.putUUID("targetId", r.targetId);
            rTag.putString("day", r.utcDay);
            rTag.putString("state", r.state.name());
            reservationList.add(rTag);
        });
        tag.put(KEY_RESERVATIONS, reservationList);
        return tag;
    }

    public static ShadowExperienceLimitStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ShadowExperienceLimitStore store = new ShadowExperienceLimitStore();
        // dataVersion MUST exist as TAG_INT — never fall back to a getInt
        // default and load as a healthy empty store.
        if (!tag.contains(KEY_VERSION, Tag.TAG_INT)) {
            store.failClosed = true;
            return store;
        }
        int version = tag.getInt(KEY_VERSION);
        if (version < 1 || version > DATA_VERSION) {
            // version 0, negative versions and future versions ALL fail
            // closed (an empty store would silently grant XP against unknown
            // semantics).
            store.failClosed = true;
            return store;
        }
        if (!tag.contains(KEY_FAIL_CLOSED, Tag.TAG_BYTE)) {
            store.failClosed = true;
            return store;
        }
        store.failClosed = tag.getBoolean(KEY_FAIL_CLOSED);
        if (store.failClosed) {
            return store; // already marked damaged: never trust the payload
        }
        if (!tag.contains(KEY_PAIRS, Tag.TAG_LIST) || !isCompoundList(tag.get(KEY_PAIRS))) {
            store.failClosed = true;
            return store;
        }
        if (!tag.contains(KEY_RESERVATIONS, Tag.TAG_LIST)
                || !isCompoundList(tag.get(KEY_RESERVATIONS))) {
            store.failClosed = true;
            return store;
        }
        // Aggregates: conservative MERGE. Duplicate pair entries merge (never
        // last-write-wins); duplicate days take the max count; an invalid
        // UUID, an invalid day, a negative count or a missing days field is
        // storage damage → failClosed, never silently skipped. Payloads beyond
        // the capacity bounds fail closed too.
        ListTag pairsList = tag.getList(KEY_PAIRS, Tag.TAG_COMPOUND);
        for (int i = 0; i < pairsList.size() && !store.failClosed; i++) {
            CompoundTag entry = pairsList.getCompound(i);
            UUID thiefId;
            UUID targetId;
            if (entry.contains("thiefId", Tag.TAG_INT_ARRAY)
                    && entry.contains("targetId", Tag.TAG_INT_ARRAY)) {
                try {
                    thiefId = entry.getUUID("thiefId");
                    targetId = entry.getUUID("targetId");
                } catch (RuntimeException e) {
                    store.failClosed = true; // invalid UUIDs
                    break;
                }
            } else {
                store.failClosed = true; // missing UUID fields
                break;
            }
            if (!entry.contains("days", Tag.TAG_LIST)
                    || !isCompoundList(entry.get("days"))) {
                store.failClosed = true; // missing/mistyped days list
                break;
            }
            Map<String, Integer> days = new HashMap<>();
            ListTag daysTag = entry.getList("days", Tag.TAG_COMPOUND);
            for (int d = 0; d < daysTag.size(); d++) {
                CompoundTag dayEntry = daysTag.getCompound(d);
                // day MUST be TAG_STRING + strict ISO; count MUST be TAG_INT
                // (a missing count must never read as 0).
                if (!dayEntry.contains("day", Tag.TAG_STRING)
                        || !dayEntry.contains("count", Tag.TAG_INT)) {
                    store.failClosed = true;
                    break;
                }
                String day = dayEntry.getString("day");
                int count = dayEntry.getInt("count");
                if (!isStrictIsoDate(day) || count < 0) {
                    store.failClosed = true; // invalid day / negative count
                    break;
                }
                days.merge(day, Math.min(count, Integer.MAX_VALUE), Math::max);
            }
            if (store.failClosed) {
                break;
            }
            if (days.isEmpty()) {
                store.failClosed = true; // missing required days field
                break;
            }
            if (days.size() > MAX_DAYS_PER_PAIR) {
                store.failClosed = true; // cannot express within capacity
                break;
            }
            PairKey pair = new PairKey(thiefId, targetId);
            Map<String, Integer> existingDays = store.occupied.get(pair);
            if (existingDays == null) {
                if (store.occupied.size() >= MAX_PAIRS) {
                    store.failClosed = true; // over the pair cap
                    break;
                }
                store.occupied.put(pair, days);
            } else {
                for (Map.Entry<String, Integer> e : days.entrySet()) {
                    existingDays.merge(e.getKey(), e.getValue(), Math::max);
                }
                if (existingDays.size() > MAX_DAYS_PER_PAIR) {
                    store.failClosed = true; // merged days exceed capacity
                    break;
                }
            }
        }
        if (store.failClosed) {
            return store;
        }
        // Reservations: an eventId already present with a different identity
        // or state marks the storage damaged — never "pick the last one".
        ListTag reservationList = tag.getList(KEY_RESERVATIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < reservationList.size() && !store.failClosed; i++) {
            CompoundTag rTag = reservationList.getCompound(i);
            UUID eventId;
            UUID thiefId;
            UUID targetId;
            String day;
            ReservationState state;
            if (rTag.contains("eventId", Tag.TAG_INT_ARRAY)
                    && rTag.contains("thiefId", Tag.TAG_INT_ARRAY)
                    && rTag.contains("targetId", Tag.TAG_INT_ARRAY)
                    && rTag.contains("day", Tag.TAG_STRING)
                    && rTag.contains("state", Tag.TAG_STRING)) {
                try {
                    eventId = rTag.getUUID("eventId");
                    thiefId = rTag.getUUID("thiefId");
                    targetId = rTag.getUUID("targetId");
                    day = rTag.getString("day");
                    state = parseState(rTag.getString("state"));
                } catch (RuntimeException e) {
                    store.failClosed = true; // invalid reservation UUIDs
                    break;
                }
            } else {
                store.failClosed = true; // missing/mistyped reservation fields
                break;
            }
            if (!isStrictIsoDate(day) || state == null) {
                store.failClosed = true; // invalid day / unknown state
                break;
            }
            ReservationEntry existing = store.reservations.get(eventId);
            if (existing != null) {
                if (existing.thiefId.equals(thiefId) && existing.targetId.equals(targetId)
                        && existing.utcDay.equals(day) && existing.state == state) {
                    continue; // byte-identical duplicate: harmless
                }
                store.failClosed = true; // identity/state conflict: damaged
                break;
            }
            if (store.reservations.size() >= MAX_RESERVATIONS) {
                store.failClosed = true; // cannot express within capacity
                break;
            }
            store.reservations.put(eventId,
                    new ReservationEntry(thiefId, targetId, day, state));
        }
        if (store.failClosed) {
            return store;
        }
        // Conservative cross-validation — reservations imply occupied slots
        // per (pair, day); corrupted NBT (e.g. the aggregates bucket missing)
        // must never let the occupied count shrink below what the loaded
        // reservations say. The back-fill must never exceed MAX_PAIRS.
        Map<PairKey, Map<String, Integer>> implied = new HashMap<>();
        for (ReservationEntry r : store.reservations.values()) {
            implied.computeIfAbsent(new PairKey(r.thiefId, r.targetId), k -> new HashMap<>())
                    .merge(r.utcDay, 1, Integer::sum);
        }
        for (Map.Entry<PairKey, Map<String, Integer>> entry : implied.entrySet()) {
            if (!store.occupied.containsKey(entry.getKey())
                    && store.occupied.size() >= MAX_PAIRS) {
                store.failClosed = true; // back-fill would exceed the cap
                break;
            }
            Map<String, Integer> days = store.occupied.computeIfAbsent(entry.getKey(),
                    k -> new HashMap<>());
            for (Map.Entry<String, Integer> day : entry.getValue().entrySet()) {
                int current = days.getOrDefault(day.getKey(), 0);
                if (current < day.getValue()) {
                    days.put(day.getKey(), day.getValue());
                }
            }
            if (days.size() > MAX_DAYS_PER_PAIR) {
                store.failClosed = true;
                break;
            }
        }
        return store;
    }

    @Nullable
    /**
     * Parses a persisted reservation state. A persisted {@code "RESERVED"}
     * describes a pre-restart send whose outcome is UNKNOWN — it migrates to
     * {@link ReservationState#RECOVERY} (8E.1 §1.3): the quota stays
     * occupied and the eventId is never re-sent. This migration applies to
     * v1 data (which only ever wrote RESERVED/COMMITTED) and to v2 data
     * written during the same-JVM crash window.
     */
    private static ReservationState parseState(String value) {
        return switch (value) {
            case "RESERVED" -> ReservationState.RECOVERY; // crash migration
            case "COMMITTED" -> ReservationState.COMMITTED;
            case "RECOVERY" -> ReservationState.RECOVERY;
            default -> null; // unknown → storage damage
        };
    }

    // ---- test hooks (not part of the public API) ----

    public static void setDateSupplierForTesting(java.util.function.Supplier<LocalDate> supplier) {
        dateSupplier = supplier;
    }

    public static void resetForTesting() {
        dateSupplier = () -> LocalDate.now(ZoneOffset.UTC);
    }

    /** @return the number of tracked pairs (tests) */
    public int pairCount() {
        return occupied.size();
    }

    /** @return the number of outstanding reservations (tests) */
    public int reservationCount() {
        return reservations.size();
    }

    /** @return the occupied quota count for a pair on a day (tests) */
    public int occupiedCountForTest(UUID thiefId, UUID targetId, String utcDay) {
        Map<String, Integer> days = occupied.get(new PairKey(thiefId, targetId));
        return days == null ? 0 : days.getOrDefault(utcDay, 0);
    }

    /** @return whether the store is in the persisted fail-closed state (tests) */
    public boolean isFailClosed() {
        return failClosed;
    }
}
