package com.tanrunn.tcth.impl.shadow;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Strict serializer for {@link ShadowLootState} (8D.1 §2 + 8D.1.1 §4).
 *
 * <p>Per-state field whitelists (8D.1.1 §4): AVAILABLE and CORRUPT carry only
 * {@code version+state}; PENDING only {@code version/state/eventId/thief/
 * startedAt}; LOOTED only {@code version/state/eventId/itemId/count/
 * completedAt}. A missing, mistyped or UNKNOWN field, a negative/zero
 * timestamp, an invalid ResourceLocation, a count outside 1..4 or a future/
 * zero/negative version all read as CORRUPT — and the serializer NEVER throws
 * (NeoForge would silently skip the attachment on an exception).
 *
 * <p>Write/read symmetry: every required field is always written, so any
 * legal in-memory state round-trips to the identical state.
 */
public final class ShadowLootStateSerializer implements IAttachmentSerializer<CompoundTag, ShadowLootState> {

    private static final int DATA_VERSION = 1;

    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_STATE = "state";
    private static final String KEY_EVENT_ID = "eventId";
    private static final String KEY_THIEF = "thief";
    private static final String KEY_STARTED_AT = "startedAt";
    private static final String KEY_ITEM_ID = "itemId";
    private static final String KEY_COUNT = "count";
    private static final String KEY_COMPLETED_AT = "completedAt";

    private static final Set<String> KEYS = Set.of(KEY_VERSION, KEY_STATE, KEY_EVENT_ID, KEY_THIEF,
            KEY_STARTED_AT, KEY_ITEM_ID, KEY_COUNT, KEY_COMPLETED_AT);

    @Override
    public CompoundTag write(ShadowLootState value, HolderLookup.Provider lookup) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_VERSION, DATA_VERSION);
        tag.putString(KEY_STATE, value.state().name());
        switch (value.state()) {
            case AVAILABLE, CORRUPT -> {
                // only version+state
            }
            case PENDING -> {
                tag.putUUID(KEY_EVENT_ID, value.eventId());
                tag.putUUID(KEY_THIEF, value.thiefUuid());
                tag.putLong(KEY_STARTED_AT, value.startedAt());
            }
            case LOOTED -> {
                tag.putUUID(KEY_EVENT_ID, value.eventId());
                tag.putString(KEY_ITEM_ID, value.itemId().toString());
                tag.putInt(KEY_COUNT, value.count());
                tag.putLong(KEY_COMPLETED_AT, value.completedAt());
            }
        }
        return tag;
    }

    @Override
    public ShadowLootState read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider lookup) {
        try {
            if (!tag.contains(KEY_VERSION, Tag.TAG_INT)
                    || tag.getInt(KEY_VERSION) != DATA_VERSION) {
                return ShadowLootState.corrupt(); // missing/future/zero/negative version
            }
            if (!tag.contains(KEY_STATE, Tag.TAG_STRING)) {
                return ShadowLootState.corrupt();
            }
            ShadowLootState.State state = parseState(tag.getString(KEY_STATE));
            if (state == null) {
                return ShadowLootState.corrupt(); // unknown state value
            }
            switch (state) {
                case AVAILABLE, CORRUPT -> {
                    if (!allowedKeysOnly(tag, KEY_VERSION, KEY_STATE)) {
                        return ShadowLootState.corrupt(); // foreign fields
                    }
                    return state == ShadowLootState.State.AVAILABLE
                            ? ShadowLootState.available()
                            : ShadowLootState.corrupt();
                }
                case PENDING -> {
                    if (!allowedKeysOnly(tag, KEY_VERSION, KEY_STATE, KEY_EVENT_ID, KEY_THIEF,
                            KEY_STARTED_AT)) {
                        return ShadowLootState.corrupt(); // foreign fields
                    }
                    if (!tag.contains(KEY_EVENT_ID, Tag.TAG_INT_ARRAY)
                            || !tag.contains(KEY_THIEF, Tag.TAG_INT_ARRAY)
                            || !tag.contains(KEY_STARTED_AT, Tag.TAG_LONG)) {
                        return ShadowLootState.corrupt();
                    }
                    UUID eventId = tag.getUUID(KEY_EVENT_ID);
                    UUID thiefUuid = tag.getUUID(KEY_THIEF);
                    long startedAt = tag.getLong(KEY_STARTED_AT);
                    if (eventId == null || thiefUuid == null || startedAt <= 0L) {
                        return ShadowLootState.corrupt();
                    }
                    return ShadowLootState.pending(eventId, thiefUuid, startedAt);
                }
                case LOOTED -> {
                    if (!allowedKeysOnly(tag, KEY_VERSION, KEY_STATE, KEY_EVENT_ID, KEY_ITEM_ID,
                            KEY_COUNT, KEY_COMPLETED_AT)) {
                        return ShadowLootState.corrupt(); // foreign fields
                    }
                    if (!tag.contains(KEY_EVENT_ID, Tag.TAG_INT_ARRAY)
                            || !tag.contains(KEY_ITEM_ID, Tag.TAG_STRING)
                            || !tag.contains(KEY_COUNT, Tag.TAG_INT)
                            || !tag.contains(KEY_COMPLETED_AT, Tag.TAG_LONG)) {
                        return ShadowLootState.corrupt();
                    }
                    UUID eventId = tag.getUUID(KEY_EVENT_ID);
                    ResourceLocation itemId = parseStrictResourceLocation(tag.getString(KEY_ITEM_ID));
                    int count = tag.getInt(KEY_COUNT);
                    long completedAt = tag.getLong(KEY_COMPLETED_AT);
                    if (eventId == null || itemId == null || count <= 0 || count > 4
                            || completedAt <= 0L) {
                        return ShadowLootState.corrupt();
                    }
                    return ShadowLootState.looted(eventId, itemId, count, completedAt);
                }
                default -> {
                    return ShadowLootState.corrupt();
                }
            }
        } catch (RuntimeException e) {
            // NEVER propagate: NeoForge would skip the attachment and the
            // corrupt payload would be indistinguishable from "missing".
            return ShadowLootState.corrupt();
        }
    }

    /** Whether every key in the tag belongs to the allowed set. */
    private static boolean allowedKeysOnly(CompoundTag tag, String... allowed) {
        Set<String> allowedSet = Set.of(allowed);
        for (String key : tag.getAllKeys()) {
            if (!allowedSet.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private static ShadowLootState.State parseState(String value) {
        try {
            return ShadowLootState.State.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Strict ResourceLocation parsing: invalid ids and path traversal are damage. */
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
            return null;
        }
        return rl;
    }
}
