package com.tanrunn.tcth.impl.shadow;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-victim daily successful-ITEM quota with an eventId-idempotent
 * reservation protocol (8C.2.1 §2).
 *
 * <p>Replaces the 8C.2 best-effort counter. Protocol:
 * <ul>
 *   <li>{@link #tryReserve} — called BEFORE the asset commit; a successful
 *       reservation occupies the quota immediately ({@code RESERVED});</li>
 *   <li>{@link #commitReservation} — called on SUCCESS; the reservation moves
 *       to {@code COMMITTED} and keeps occupying the quota;</li>
 *   <li>{@link #releaseReservation} — called on clean failures and after a
 *       successful rollback; frees the quota;</li>
 *   <li>{@code RECOVERY_REQUIRED} keeps the reservation (the assets may have
 *       moved, so the quota stays occupied).</li>
 * </ul>
 *
 * <p>Both {@code RESERVED} and {@code COMMITTED} count against the per-day
 * limit. Fail-closed: storage-full, invalid dates, {@code false} returns and
 * exceptions all forbid the ITEM transfer. The UTC date string is captured
 * ONCE per attempt by the caller.
 *
 * <p>Capacity (8C.2.2 §4): the reservation index is bounded; when full, only
 * an already-<em>settled</em> COMMITTED index entry may be dropped to make
 * room — that NEVER reduces the {@code occupied} aggregates, so no victim's
 * quota reopens. When every outstanding index entry is still RESERVED the
 * store rejects (fail-closed). An eventId reused with a different
 * victim/day is rejected too.
 *
 * <p>Safety rules: bounded (victims / days / reservations), deterministic
 * oldest-date eviction (ISO dates sort lexicographically), saturated integer
 * counts (no overflow), strict ISO date parsing on load and reserve, and
 * defensive loading that never fails the world load; reservations and
 * aggregates are cross-validated conservatively on load so corrupted NBT
 * never shrinks an occupied count.
 *
 * <p>Strict loading (8C.2.4 §4): an invalid UUID, an invalid date, a
 * negative count, an invalid reservation state or a missing required field
 * is storage damage — it sets the persisted {@code failClosed} flag instead
 * of being silently skipped; only byte-identical duplicates are acceptable.
 * Once fail-closed, every ITEM query/reserve/commit/release stays
 * conservatively refused.
 *
 * <p><b>Crash-consistency limitation (documented):</b> a plain SavedData is
 * not an fsync WAL; the very last write may be lost on a crash.
 */
public final class ShadowDailyLimitStore extends SavedData implements ShadowDailyLimitWriter {

    public static final String NAME = "tcth_shadow_daily_limits";
    /** v3 (8C.2.3): persisted failClosed flag. v1/v2 data still loads. */
    public static final int DATA_VERSION = 3;
    /** Maximum tracked victims. */
    public static final int MAX_VICTIMS = 1024;
    /** Maximum tracked days per victim. */
    public static final int MAX_DAYS_PER_VICTIM = 64;
    /** Maximum outstanding reservations (eventId entries). */
    public static final int MAX_RESERVATIONS = 4096;

    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_VICTIMS = "victims";
    private static final String KEY_RESERVATIONS = "reservations";
    private static final String KEY_FAIL_CLOSED = "failClosed";

    private enum ReservationState {
        RESERVED, COMMITTED
    }

    private record ReservationEntry(UUID victimId, String utcDay, ReservationState state) {
    }

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Occupied quota per victim per UTC day (RESERVED + COMMITTED). */
    private final Map<UUID, Map<String, Integer>> occupied = new HashMap<>();
    /** eventId → reservation (insertion order for deterministic eviction). */
    private final Map<UUID, ReservationEntry> reservations = new LinkedHashMap<>(64, 0.75f);
    /** Persisted damage/saturation marker (8C.2.3 §3): once set, the store
     *  refuses every ITEM attempt (isAtItemLimit → true, tryReserve →
     *  REJECTED, commit/release → false) instead of silently dropping data
     *  and reopening quotas. Survives save/load. */
    private boolean failClosed;

    private static java.util.function.Supplier<LocalDate> dateSupplier =
            () -> LocalDate.now(ZoneOffset.UTC);

    public static final Factory<ShadowDailyLimitStore> FACTORY = new Factory<>(
            ShadowDailyLimitStore::new, ShadowDailyLimitStore::load, null);

    /** Returns the store bound to the overworld data storage. */
    public static ShadowDailyLimitStore current(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public ShadowDailyLimitStore() {
    }

    @Override
    public synchronized ReservationResult tryReserve(UUID victimId, String utcDay, UUID eventId, long limit) {
        if (failClosed) {
            return ReservationResult.REJECTED; // damaged/saturated: never reopen
        }
        if (victimId == null || eventId == null || limit <= 0L || !isStrictIsoDate(utcDay)) {
            return ReservationResult.REJECTED; // fail closed
        }
        // eventId idempotency: the same attempt never reserves twice. An
        // eventId bound to a different victim or UTC day is a hijack attempt
        // (8C.2.2 §4): fail closed.
        ReservationEntry existing = reservations.get(eventId);
        if (existing != null) {
            if (!existing.victimId.equals(victimId) || !existing.utcDay.equals(utcDay)) {
                return ReservationResult.REJECTED;
            }
            return existing.state == ReservationState.COMMITTED
                    ? ReservationResult.COMMITTED_EXISTING
                    : ReservationResult.RESERVED;
        }
        if (reservations.size() >= MAX_RESERVATIONS) {
            // Full index: only a settled COMMITTED entry may be dropped for
            // space — occupied aggregates stay untouched. All-RESERVED means
            // the store must reject (fail-closed, 8C.2.2 §4).
            if (!evictOneCommittedIndex()) {
                return ReservationResult.REJECTED;
            }
        }
        if (occupied.size() >= MAX_VICTIMS && !occupied.containsKey(victimId)) {
            return ReservationResult.REJECTED; // storage full: fail closed
        }
        Map<String, Integer> days = occupied.computeIfAbsent(victimId, k -> new HashMap<>());
        if (!days.containsKey(utcDay) && days.size() >= MAX_DAYS_PER_VICTIM) {
            evictOldestDay(days);
        }
        int count = days.getOrDefault(utcDay, 0);
        if (count >= limit || count >= Integer.MAX_VALUE) {
            return ReservationResult.LIMIT_REACHED;
        }
        days.put(utcDay, count + 1);
        reservations.put(eventId, new ReservationEntry(victimId, utcDay, ReservationState.RESERVED));
        setDirty();
        return ReservationResult.RESERVED;
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
        reservations.put(eventId, new ReservationEntry(entry.victimId, entry.utcDay,
                ReservationState.COMMITTED));
        setDirty();
        return true;
    }

    @Override
    public synchronized boolean releaseReservation(UUID eventId) {
        if (failClosed) {
            return false; // never free quota on damaged data
        }
        ReservationEntry entry = reservations.remove(eventId);
        if (entry == null) {
            return false;
        }
        Map<String, Integer> days = occupied.get(entry.victimId);
        if (days != null) {
            int count = days.getOrDefault(entry.utcDay, 0);
            if (count <= 1) {
                days.remove(entry.utcDay);
                if (days.isEmpty()) {
                    occupied.remove(entry.victimId);
                }
            } else {
                days.put(entry.utcDay, count - 1);
            }
        }
        setDirty();
        return true;
    }

    @Override
    public synchronized boolean isAtItemLimit(UUID victimId, String utcDay, long limit) {
        if (failClosed) {
            return true; // damaged/saturated: every ITEM attempt refused
        }
        if (victimId == null || limit <= 0L || !isStrictIsoDate(utcDay)) {
            return true; // fail closed
        }
        Map<String, Integer> days = occupied.get(victimId);
        if (days == null) {
            return false;
        }
        int count = days.getOrDefault(utcDay, 0);
        return count >= limit || count >= Integer.MAX_VALUE;
    }

    // ---- internals ----

    /** {@code true} for an empty list or a list whose elements are all
     *  {@code TAG_COMPOUND} — anything else is a schema error (8C.2.5 §3). */
    private static boolean isCompoundList(Tag tag) {
        if (!(tag instanceof ListTag list)) {
            return false;
        }
        int elementType = list.getElementType();
        return elementType == Tag.TAG_END || elementType == Tag.TAG_COMPOUND;
    }

    /** Strict ISO date validation (8C.2.1: no lenient parsing). */
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

    /** Deterministic oldest-date eviction: ISO dates sort lexicographically. */
    private static void evictOldestDay(Map<String, Integer> days) {
        String oldest = null;
        for (String day : days.keySet()) {
            if (oldest == null || day.compareTo(oldest) < 0) {
                oldest = day;
            }
        }
        if (oldest != null) {
            days.remove(oldest);
        }
    }

    /** Drops ONE already-settled COMMITTED index entry (insertion order) to
     *  free index space. Never touches the {@code occupied} aggregates, so
     *  the victim's quota stays closed (8C.2.2 §4).
     *
     *  @return {@code false} when every outstanding entry is still RESERVED */
    private boolean evictOneCommittedIndex() {
        Iterator<Map.Entry<UUID, ReservationEntry>> it = reservations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ReservationEntry> e = it.next();
            if (e.getValue().state == ReservationState.COMMITTED) {
                it.remove();
                setDirty();
                return true;
            }
        }
        return false;
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
        ListTag occupiedList = new ListTag();
        occupied.forEach((uuid, days) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("victim", uuid);
            ListTag daysTag = new ListTag();
            days.forEach((day, count) -> {
                CompoundTag dayEntry = new CompoundTag();
                dayEntry.putString("day", day);
                dayEntry.putInt("count", count);
                daysTag.add(dayEntry);
            });
            entry.put("days", daysTag);
            occupiedList.add(entry);
        });
        tag.put(KEY_VICTIMS, occupiedList);
        ListTag reservationList = new ListTag();
        reservations.forEach((eventId, r) -> {
            CompoundTag rTag = new CompoundTag();
            rTag.putUUID("eventId", eventId);
            rTag.putUUID("victim", r.victimId);
            rTag.putString("day", r.utcDay);
            rTag.putString("state", r.state.name());
            reservationList.add(rTag);
        });
        tag.put(KEY_RESERVATIONS, reservationList);
        return tag;
    }

    public static ShadowDailyLimitStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ShadowDailyLimitStore store = new ShadowDailyLimitStore();
        // 8C.2.5 §3: dataVersion MUST exist as TAG_INT — never fall back to
        // a getInt default and load as a healthy empty store.
        if (!tag.contains(KEY_VERSION, Tag.TAG_INT)) {
            store.failClosed = true;
            return store;
        }
        int version = tag.getInt(KEY_VERSION);
        if (version < 1 || version > DATA_VERSION) {
            // 8C.2.6 §1: only 1..DATA_VERSION is valid — version 0, negative
            // versions and future versions ALL fail closed (an empty store
            // would silently allow theft against unknown semantics).
            store.failClosed = true;
            return store;
        }
        // Versioned root schema (8C.2.5 §3):
        //   v1: victims required; reservations/failClosed may be absent
        //   v2: victims + reservations required
        //   v3: victims + reservations + failClosed (TAG_BYTE) required
        if (version >= 3) {
            if (!tag.contains(KEY_FAIL_CLOSED, Tag.TAG_BYTE)) {
                store.failClosed = true;
                return store;
            }
            store.failClosed = tag.getBoolean(KEY_FAIL_CLOSED);
            if (store.failClosed) {
                return store; // already marked damaged: never trust the payload
            }
        }
        if (!tag.contains(KEY_VICTIMS, Tag.TAG_LIST) || !isCompoundList(tag.get(KEY_VICTIMS))) {
            store.failClosed = true;
            return store;
        }
        if (version >= 2
                && (!tag.contains(KEY_RESERVATIONS, Tag.TAG_LIST)
                    || !isCompoundList(tag.get(KEY_RESERVATIONS)))) {
            store.failClosed = true;
            return store;
        }
        // Aggregates: conservative MERGE (8C.2.4 §4). Duplicate victim
        // entries merge (never last-write-wins); duplicate days take the
        // max count; an invalid UUID, an invalid day, a negative count or a
        // missing days field is storage damage → failClosed, never silently
        // skipped. Payloads beyond the capacity bounds fail closed too.
        ListTag occupiedList = tag.getList(KEY_VICTIMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < occupiedList.size() && !store.failClosed; i++) {
            CompoundTag entry = occupiedList.getCompound(i);
            UUID uuid;
            if (entry.contains("victim", Tag.TAG_INT_ARRAY)) {
                try {
                    uuid = entry.getUUID("victim");
                } catch (RuntimeException e) {
                    store.failClosed = true; // invalid victim UUID
                    break;
                }
            } else {
                store.failClosed = true; // missing victim UUID
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
                // (a missing count must never read as 0, 8C.2.5 §3).
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
            if (days.size() > MAX_DAYS_PER_VICTIM) {
                store.failClosed = true; // cannot express within capacity
                break;
            }
            Map<String, Integer> existingDays = store.occupied.get(uuid);
            if (existingDays == null) {
                if (store.occupied.size() >= MAX_VICTIMS) {
                    store.failClosed = true; // over the victim cap
                    break;
                }
                store.occupied.put(uuid, days);
            } else {
                for (Map.Entry<String, Integer> e : days.entrySet()) {
                    existingDays.merge(e.getKey(), e.getValue(), Math::max);
                }
                if (existingDays.size() > MAX_DAYS_PER_VICTIM) {
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
            UUID victimId;
            String day;
            ReservationState state;
            if (rTag.contains("eventId", Tag.TAG_INT_ARRAY)
                    && rTag.contains("victim", Tag.TAG_INT_ARRAY)
                    && rTag.contains("day", Tag.TAG_STRING)
                    && rTag.contains("state", Tag.TAG_STRING)) {
                try {
                    eventId = rTag.getUUID("eventId");
                    victimId = rTag.getUUID("victim");
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
                if (existing.victimId.equals(victimId) && existing.utcDay.equals(day)
                        && existing.state == state) {
                    continue; // byte-identical duplicate: harmless
                }
                store.failClosed = true; // identity/state conflict: damaged
                break;
            }
            if (store.reservations.size() >= MAX_RESERVATIONS) {
                store.failClosed = true; // cannot express within capacity
                break;
            }
            store.reservations.put(eventId, new ReservationEntry(victimId, day, state));
        }
        if (store.failClosed) {
            return store;
        }
        // 8C.2.2 §4: conservative cross-validation — reservations imply
        // occupied slots per (victim, day); a corrupted NBT (e.g. the
        // aggregates bucket missing) must never let the occupied count
        // shrink below what the loaded reservations say. The back-fill must
        // never exceed MAX_VICTIMS (8C.2.3 §3).
        Map<UUID, Map<String, Integer>> implied = new HashMap<>();
        for (ReservationEntry r : store.reservations.values()) {
            implied.computeIfAbsent(r.victimId, k -> new HashMap<>())
                    .merge(r.utcDay, 1, Integer::sum);
        }
        for (Map.Entry<UUID, Map<String, Integer>> entry : implied.entrySet()) {
            if (!store.occupied.containsKey(entry.getKey())
                    && store.occupied.size() >= MAX_VICTIMS) {
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
            if (days.size() > MAX_DAYS_PER_VICTIM) {
                store.failClosed = true;
                break;
            }
        }
        return store;
    }

    @Nullable
    private static ReservationState parseState(String value) {
        try {
            return ReservationState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- test hooks (not part of the public API) ----

    public static void setDateSupplierForTesting(java.util.function.Supplier<LocalDate> supplier) {
        dateSupplier = supplier;
    }

    public static void resetForTesting() {
        dateSupplier = () -> LocalDate.now(ZoneOffset.UTC);
    }

    /** @return the number of tracked victims (tests) */
    public int victimCount() {
        return occupied.size();
    }

    /** @return the number of outstanding reservations (tests) */
    public int reservationCount() {
        return reservations.size();
    }

    /** @return the occupied quota count for a victim on a day (tests) */
    public int occupiedCountForTest(UUID victimId, String utcDay) {
        Map<String, Integer> days = occupied.get(victimId);
        return days == null ? 0 : days.getOrDefault(utcDay, 0);
    }

    /** @return whether the store is in the persisted fail-closed state (tests) */
    public boolean isFailClosed() {
        return failClosed;
    }
}
