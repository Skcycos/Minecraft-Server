package com.tanrunn.tcth.impl.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Independent, bounded audit log for shadow theft attempts (8B.1 schema v1).
 *
 * <p>Stored as a separate {@link SavedData} file
 * ({@code world/data/tcth_shadow_audit.dat}) bound to the <em>overworld</em>
 * data storage so records merge across dimensions — never written into the
 * vanilla playerdata.
 *
 * <p>Safety rules (8B.1 §3-4):
 * <ul>
 *   <li>records carry no {@code ItemStack}s, NBT, components or account
 *       objects (see {@link ShadowAuditRecord});</li>
 *   <li>{@code ResourceLocation} fields are strictly validated on load
 *       (invalid or path-traversal ids drop the record);</li>
 *   <li>unknown <em>non-null</em> enums drop the record; missing nullable
 *       fields are allowed as {@code null}; NaN / Infinity / negative scalars
 *       drop the record instead of failing the world load;</li>
 *   <li>the record list is bounded ({@link #MAX_RECORDS}): an append may only
 *       evict the oldest <em>settled</em> FINAL record — PENDING pre-writes
 *       and RECOVERY_REQUIRED records are NEVER evicted (8C.2.4 §3); when
 *       every record must be kept the append returns {@code false} and no
 *       asset commit may happen;</li>
 *   <li>persisted health (8C.2.4 §1): a future/negative data version, an
 *       invalid record, a conflicting duplicate eventId or a payload that
 *       cannot be expressed within the cap sets a persisted {@code failClosed}
 *       flag ({@link #isHealthy()} == false) — the store then refuses every
 *       append, disabling audit and real asset transfers;</li>
 *   <li>{@link #setDirty()} is only called when the store actually changed
 *       (insert, replace or eviction);</li>
 *   <li>query results are immutable snapshots.</li>
 * </ul>
 *
 * <p><b>Crash-consistency limitation (documented):</b> this is a plain
 * {@code SavedData} file, not an fsync'd write-ahead log. A crash between
 * the pre-write (PENDING) and the final write can leave a PENDING record
 * whose transfer outcome is unknown; a crash mid-save can lose the latest
 * records. There is no database-level atomicity — recovery is the operator's
 * responsibility, signalled by the {@code RECOVERY_REQUIRED} outcome.
 */
public final class ShadowAuditStore extends SavedData implements ShadowAuditWriter {

    public static final String NAME = "tcth_shadow_audit";
    /** Schema version. 1 = 8B.1 record set; 2 = persisted failClosed health
     *  flag (8C.2.4). v1 data (no flag) still migrates. */
    public static final int DATA_VERSION = 2;
    /** Hard cap on the number of stored records. */
    public static final int MAX_RECORDS = 10_000;

    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_FAIL_CLOSED = "failClosed";
    private static final String KEY_EVENT_ID = "eventId";
    private static final String KEY_THIEF = "thief";
    private static final String KEY_TARGET = "target";
    private static final String KEY_TARGET_KIND = "targetKind";
    private static final String KEY_TARGET_TYPE = "targetType";
    private static final String KEY_THEFT_TYPE = "theftType";
    private static final String KEY_OUTCOME = "outcome";
    private static final String KEY_AUDIT_STATE = "auditState";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_ITEM_COUNT = "itemCount";
    private static final String KEY_NUMERIC_AMOUNT = "numericAmount";
    private static final String KEY_EFFECT_ID = "effectId";
    private static final String KEY_EFFECT_DURATION = "effectDurationTicks";
    private static final String KEY_TIMESTAMP_EPOCH = "timestampEpochMillis";
    private static final String KEY_SERVER_TICK = "serverTick";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_POS_X = "posX";
    private static final String KEY_POS_Y = "posY";
    private static final String KEY_POS_Z = "posZ";
    private static final String KEY_FAILURE_REASON = "failureReason";

    /** Oldest first; inserts go to the tail; replaces stay in place. */
    private final List<ShadowAuditRecord> records = new ArrayList<>();
    /** Persisted damage/saturation marker (8C.2.4): once set, append refuses
     *  everything and {@link #isHealthy()} is {@code false}. Survives
     *  save/load. */
    private boolean failClosed;

    public static final Factory<ShadowAuditStore> FACTORY = new Factory<>(
            ShadowAuditStore::new, ShadowAuditStore::load, null);

    /**
     * Returns the audit store for the given level, always bound to the server
     * overworld's data storage so records merge across dimensions.
     */
    public static ShadowAuditStore current(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public ShadowAuditStore() {
    }

    @Override
    public synchronized boolean append(ShadowAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (failClosed) {
            return false; // damaged store: never write, never audit
        }
        int index = indexOf(record.eventId());
        if (index < 0) {
            // New eventId: inserting a PENDING pre-write or a FINAL record
            // are both allowed (8B.1.1 §5). At the cap only the oldest
            // SETTLED FINAL may be evicted — PENDING pre-writes and
            // RECOVERY_REQUIRED records are never dropped (8C.2.4 §3); when
            // everything must be kept the append refuses and the asset
            // commit must not happen.
            if (records.size() >= MAX_RECORDS && !evictOldestSettledFinal()) {
                return false;
            }
            records.add(record);
            setDirty();
            return true;
        }
        ShadowAuditRecord existing = records.get(index);
        // PENDING → FINAL (with an outcome) is the only allowed transition,
        // and only when every identity field stays identical — a FINAL record
        // may not swap the audit subject through the same eventId (8C.0 §5).
        if (existing.auditState() == ShadowAuditState.PENDING
                && record.auditState() == ShadowAuditState.FINAL
                && record.outcome() != null
                && identityFieldsMatch(existing, record)) {
            records.set(index, record);
            setDirty();
            return true;
        }
        // FINAL → byte-identical record: idempotent re-write, no change.
        if (existing.auditState() == ShadowAuditState.FINAL && existing.equals(record)) {
            return true;
        }
        // Everything else (FINAL → PENDING, FINAL → different FINAL,
        // PENDING → PENDING, PENDING → FINAL with swapped identity) is an
        // illegal transition: refuse and keep the original record untouched.
        return false;
    }

    /** Identity fields that a PENDING → FINAL transition must preserve. */
    private static boolean identityFieldsMatch(ShadowAuditRecord a, ShadowAuditRecord b) {
        return a.thiefId().equals(b.thiefId())
                && a.targetId().equals(b.targetId())
                && a.targetKind() == b.targetKind()
                && Objects.equals(a.targetType(), b.targetType())
                && a.theftType() == b.theftType()
                && Objects.equals(a.dimension(), b.dimension())
                && Objects.equals(a.position(), b.position())
                && a.serverTick() == b.serverTick();
    }

    private int indexOf(UUID eventId) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).eventId().equals(eventId)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public synchronized @Nullable ShadowAuditRecord byEventId(UUID eventId) {
        for (ShadowAuditRecord record : records) {
            if (record.eventId().equals(eventId)) {
                return record;
            }
        }
        return null;
    }

    @Override
    public synchronized boolean has(UUID eventId) {
        return byEventId(eventId) != null;
    }

    @Override
    public synchronized boolean isHealthy() {
        return !failClosed;
    }

    @Override
    public synchronized List<ShadowAuditRecord> byThief(UUID thiefId) {
        List<ShadowAuditRecord> result = new ArrayList<>();
        for (ShadowAuditRecord record : records) {
            if (record.thiefId().equals(thiefId)) {
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<ShadowAuditRecord> byTarget(UUID targetId) {
        List<ShadowAuditRecord> result = new ArrayList<>();
        for (ShadowAuditRecord record : records) {
            if (record.targetId().equals(targetId)) {
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<ShadowAuditRecord> all() {
        return List.copyOf(records);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_VERSION, DATA_VERSION);
        tag.putBoolean(KEY_FAIL_CLOSED, failClosed);
        ListTag list = new ListTag();
        for (ShadowAuditRecord record : records) {
            list.add(recordToTag(record));
        }
        tag.put(KEY_RECORDS, list);
        return tag;
    }

    public static ShadowAuditStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ShadowAuditStore store = new ShadowAuditStore();
        // 8C.2.5 §1: strict root schema — dataVersion MUST exist as TAG_INT;
        // a missing/mistyped key must NEVER fall back to getInt/getList
        // defaults and load as a healthy empty store.
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
        if (version >= 2) {
            // v2+ MUST carry the failClosed flag as TAG_BYTE.
            if (!tag.contains(KEY_FAIL_CLOSED, Tag.TAG_BYTE)) {
                store.failClosed = true;
                return store;
            }
            store.failClosed = tag.getBoolean(KEY_FAIL_CLOSED);
            if (store.failClosed) {
                return store; // already marked damaged: never trust the payload
            }
        }
        // v1+ MUST have a records TAG_LIST whose elements are TAG_COMPOUND
        // (an empty list is fine). A mistyped list must NOT degrade to an
        // empty list via getList defaults.
        if (!tag.contains(KEY_RECORDS, Tag.TAG_LIST) || !isCompoundList(tag.get(KEY_RECORDS))) {
            store.failClosed = true;
            return store;
        }
        ListTag list = tag.getList(KEY_RECORDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && !store.failClosed; i++) {
            ShadowAuditRecord record = recordFromTag(list.getCompound(i));
            if (record == null) {
                // 8C.2.4: an invalid record is storage damage — never
                // silently dropped (a dropped PENDING could reopen a commit).
                store.failClosed = true;
                break;
            }
            int index = store.indexOf(record.eventId());
            if (index >= 0) {
                if (store.records.get(index).equals(record)) {
                    continue; // byte-identical duplicate: acceptable
                }
                store.failClosed = true; // conflicting duplicate eventId
                break;
            }
            if (store.records.size() >= MAX_RECORDS) {
                // Preserve PENDING / RECOVERY_REQUIRED; only a settled FINAL
                // may be dropped for space. 8C.2.5 §4: when the store is full
                // of critical records and the NEW record is only a settled
                // FINAL, it may be safely dropped — only a critical record
                // that cannot be expressed fails the store closed.
                if (!store.evictOldestSettledFinal()) {
                    if (isCriticalRecord(record)) {
                        store.failClosed = true;
                        break;
                    }
                    continue; // droppable settled FINAL beyond the critical cap
                }
            }
            store.records.add(record);
        }
        return store;
    }

    /** Whether a record must never be evicted/dropped for space (8C.2.4 §3). */
    private static boolean isCriticalRecord(ShadowAuditRecord record) {
        return record.auditState() == ShadowAuditState.PENDING
                || record.outcome() == ShadowTheftOutcome.RECOVERY_REQUIRED;
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

    /** Drops the OLDEST record that is not a PENDING pre-write and not a
     *  RECOVERY_REQUIRED outcome (8C.2.4 §3). Returns {@code false} when no
     *  such record exists (everything must be kept). */
    private boolean evictOldestSettledFinal() {
        for (int i = 0; i < records.size(); i++) {
            ShadowAuditRecord r = records.get(i);
            if (r.auditState() != ShadowAuditState.PENDING
                    && r.outcome() != ShadowTheftOutcome.RECOVERY_REQUIRED) {
                records.remove(i);
                setDirty();
                return true;
            }
        }
        return false;
    }

    private static CompoundTag recordToTag(ShadowAuditRecord record) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(KEY_EVENT_ID, record.eventId());
        tag.putUUID(KEY_THIEF, record.thiefId());
        tag.putUUID(KEY_TARGET, record.targetId());
        tag.putString(KEY_TARGET_KIND, record.targetKind().name());
        if (record.targetType() != null) {
            tag.putString(KEY_TARGET_TYPE, record.targetType().toString());
        }
        if (record.theftType() != null) {
            tag.putString(KEY_THEFT_TYPE, record.theftType().name());
        }
        if (record.outcome() != null) {
            tag.putString(KEY_OUTCOME, record.outcome().name());
        }
        tag.putString(KEY_AUDIT_STATE, record.auditState().name());
        if (record.itemId() != null) {
            tag.putString(KEY_ITEM_ID, record.itemId().toString());
        }
        tag.putInt(KEY_ITEM_COUNT, record.itemCount());
        tag.putDouble(KEY_NUMERIC_AMOUNT, record.numericAmount());
        if (record.effectId() != null) {
            tag.putString(KEY_EFFECT_ID, record.effectId().toString());
        }
        tag.putInt(KEY_EFFECT_DURATION, record.effectDurationTicks());
        tag.putLong(KEY_TIMESTAMP_EPOCH, record.timestampEpochMillis());
        tag.putLong(KEY_SERVER_TICK, record.serverTick());
        tag.putString(KEY_DIMENSION, record.dimension().toString());
        if (record.position() != null) {
            tag.putInt(KEY_POS_X, record.position().getX());
            tag.putInt(KEY_POS_Y, record.position().getY());
            tag.putInt(KEY_POS_Z, record.position().getZ());
        }
        if (record.failureReason() != null) {
            tag.putString(KEY_FAILURE_REASON, record.failureReason());
        }
        return tag;
    }

    /**
     * Parses a record with STRICT schema validation (8C.2.5 §2): every
     * required field must EXIST with the exact NBT type — never substitutes
     * {@code 0} / empty strings via getInt/getString defaults. Optional
     * fields follow their semantics (missing → {@code null}; present with
     * the wrong type → damaged). Any violation returns {@code null}, which
     * fails the whole store closed.
     */
    @Nullable
    private static ShadowAuditRecord recordFromTag(CompoundTag tag) {
        // Required UUIDs (putUUID stores TAG_INT_ARRAY).
        if (!tag.contains(KEY_EVENT_ID, Tag.TAG_INT_ARRAY)
                || !tag.contains(KEY_THIEF, Tag.TAG_INT_ARRAY)
                || !tag.contains(KEY_TARGET, Tag.TAG_INT_ARRAY)) {
            return null;
        }
        // Required enums as TAG_STRING with a known value.
        if (!tag.contains(KEY_TARGET_KIND, Tag.TAG_STRING)) {
            return null;
        }
        ShadowTargetKind targetKind = parseEnum(ShadowTargetKind.class, tag.getString(KEY_TARGET_KIND));
        if (targetKind == null) {
            return null;
        }
        if (!tag.contains(KEY_AUDIT_STATE, Tag.TAG_STRING)) {
            return null;
        }
        ShadowAuditState auditState = parseEnum(ShadowAuditState.class, tag.getString(KEY_AUDIT_STATE));
        if (auditState == null) {
            return null;
        }
        // Required scalars with exact types.
        if (!tag.contains(KEY_ITEM_COUNT, Tag.TAG_INT)
                || !tag.contains(KEY_NUMERIC_AMOUNT, Tag.TAG_DOUBLE)
                || !tag.contains(KEY_EFFECT_DURATION, Tag.TAG_INT)
                || !tag.contains(KEY_TIMESTAMP_EPOCH, Tag.TAG_LONG)
                || !tag.contains(KEY_SERVER_TICK, Tag.TAG_LONG)
                || !tag.contains(KEY_DIMENSION, Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation dimension = parseStrictResourceLocation(tag.getString(KEY_DIMENSION));
        int itemCount = tag.getInt(KEY_ITEM_COUNT);
        double numericAmount = tag.getDouble(KEY_NUMERIC_AMOUNT);
        int effectDurationTicks = tag.getInt(KEY_EFFECT_DURATION);
        long timestampEpochMillis = tag.getLong(KEY_TIMESTAMP_EPOCH);
        long serverTick = tag.getLong(KEY_SERVER_TICK);
        if (dimension == null || itemCount < 0 || effectDurationTicks < 0
                || !Double.isFinite(numericAmount) || numericAmount < 0.0d
                || timestampEpochMillis < 0L || serverTick < 0L) {
            return null;
        }
        // Optional ResourceLocations: missing → null; present → TAG_STRING
        // (a present field with the wrong type is damage).
        ResourceLocation targetType = null;
        if (tag.contains(KEY_TARGET_TYPE)) {
            if (!tag.contains(KEY_TARGET_TYPE, Tag.TAG_STRING)) {
                return null;
            }
            targetType = parseStrictResourceLocation(tag.getString(KEY_TARGET_TYPE));
            if (targetType == null) {
                return null; // present but invalid → damage, never null
            }
        }
        ResourceLocation itemId = null;
        if (tag.contains(KEY_ITEM_ID)) {
            if (!tag.contains(KEY_ITEM_ID, Tag.TAG_STRING)) {
                return null;
            }
            itemId = parseStrictResourceLocation(tag.getString(KEY_ITEM_ID));
            if (itemId == null) {
                return null;
            }
        }
        ResourceLocation effectId = null;
        if (tag.contains(KEY_EFFECT_ID)) {
            if (!tag.contains(KEY_EFFECT_ID, Tag.TAG_STRING)) {
                return null;
            }
            effectId = parseStrictResourceLocation(tag.getString(KEY_EFFECT_ID));
            if (effectId == null) {
                return null;
            }
        }
        // Optional enums: missing → null; present → TAG_STRING with a known
        // value (8B.1 §3.9).
        ShadowTheftType theftType = null;
        if (tag.contains(KEY_THEFT_TYPE)) {
            if (!tag.contains(KEY_THEFT_TYPE, Tag.TAG_STRING)) {
                return null;
            }
            theftType = parseEnum(ShadowTheftType.class, tag.getString(KEY_THEFT_TYPE));
            if (theftType == null) {
                return null;
            }
        }
        ShadowTheftOutcome outcome = null;
        if (tag.contains(KEY_OUTCOME)) {
            if (!tag.contains(KEY_OUTCOME, Tag.TAG_STRING)) {
                return null;
            }
            outcome = parseEnum(ShadowTheftOutcome.class, tag.getString(KEY_OUTCOME));
            if (outcome == null) {
                return null;
            }
        }
        // position (8C.2.6 §2): all three keys absent → null (legal);
        // ANY key present → all three MUST exist AND be TAG_INT — partial
        // presence, mixed types and all-wrong types are all storage damage.
        boolean presentX = tag.contains(KEY_POS_X);
        boolean presentY = tag.contains(KEY_POS_Y);
        boolean presentZ = tag.contains(KEY_POS_Z);
        if (presentX != presentY || presentY != presentZ) {
            return null; // partial presence → damage
        }
        BlockPos position = null;
        if (presentX) {
            if (!tag.contains(KEY_POS_X, Tag.TAG_INT)
                    || !tag.contains(KEY_POS_Y, Tag.TAG_INT)
                    || !tag.contains(KEY_POS_Z, Tag.TAG_INT)) {
                return null; // present but mistyped → damage
            }
            position = new BlockPos(tag.getInt(KEY_POS_X), tag.getInt(KEY_POS_Y), tag.getInt(KEY_POS_Z));
        }
        // failureReason: missing → null; present → TAG_STRING.
        String failureReason = null;
        if (tag.contains(KEY_FAILURE_REASON)) {
            if (!tag.contains(KEY_FAILURE_REASON, Tag.TAG_STRING)) {
                return null;
            }
            failureReason = tag.getString(KEY_FAILURE_REASON);
        }
        // Let the record constructor enforce every cross-field invariant;
        // any violation drops the record.
        try {
            return new ShadowAuditRecord(tag.getUUID(KEY_EVENT_ID), tag.getUUID(KEY_THIEF),
                    tag.getUUID(KEY_TARGET), targetKind, targetType, theftType, outcome, auditState,
                    itemId, itemCount, numericAmount, effectId, effectDurationTicks,
                    timestampEpochMillis, serverTick, dimension, position, failureReason);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Strict ResourceLocation parsing: rejects invalid ids and path
     *  traversal (".." segments). */
    @Nullable
    private static ResourceLocation parseStrictResourceLocation(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(value);
        if (rl == null) {
            return null;
        }
        String path = rl.getPath();
        if (path.contains("..")) {
            return null; // reject path traversal
        }
        return rl;
    }

    @Nullable
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
