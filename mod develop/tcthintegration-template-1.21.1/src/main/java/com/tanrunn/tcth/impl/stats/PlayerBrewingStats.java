package com.tanrunn.tcth.impl.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Per-player brewing statistics (phase 7D).
 *
 * <p>Records what a player has actually prepared through a brewing device:
 * <ul>
 *   <li>{@code totalBrewingEvents} and {@code deviceCounts} increase by 1 per
 *       verified event;</li>
 *   <li>{@code totalBeveragesPrepared}, {@code tierCounts} and {@code itemCounts}
 *       accumulate the stack {@code count};</li>
 *   <li>{@code uniqueBeverages} and {@code itemCounts} are capped at
 *       {@link #MAX_TRACKED_ITEMS} — existing items keep accumulating past the
 *       cap, new ones are not added;</li>
 *   <li>all integer accumulation is saturating;</li>
 *   <li>unknown device/tier values are tolerated on load (skipped, never
 *       fatal).</li>
 * </ul>
 *
 * <p>Only real, non-automated, graded events are recorded by the caller
 * ({@link BrewingStatsTracker}); this class never filters.
 */
public final class PlayerBrewingStats {

    /** Hard cap on tracked per-player beverage entries (safety). */
    public static final int MAX_TRACKED_ITEMS = 4096;

    /** Item ranking: count descending, then full {@code namespace:path} asc. */
    private static final Comparator<Map.Entry<String, Integer>> ITEM_RANK =
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey);

    private int totalBrewingEvents;
    private int totalBeveragesPrepared;
    private final Set<String> uniqueBeverages = new HashSet<>();
    private final Map<BeverageTier, Integer> tierCounts = new EnumMap<>(BeverageTier.class);
    private final Map<BeverageDevice, Integer> deviceCounts = new EnumMap<>(BeverageDevice.class);
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private long firstPreparedAt;
    private long lastPreparedAt;
    private String lastBeverage = "";
    private String lastDevice = "";
    private String lastTier = "";

    PlayerBrewingStats() {
    }

    /** Saturating addition: clamps at Integer.MAX_VALUE, never overflows. */
    private static int satAdd(int a, int b) {
        if (b <= 0) {
            return a;
        }
        long sum = (long) a + b;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * Records one verified beverage event.
     *
     * @param tier         resolved beverage tier (caller filters UNKNOWN/T3)
     * @param device       producing device (never null)
     * @param beverageId   item id of the prepared beverage
     * @param count        stack count (must be &gt; 0; caller filters)
     * @param nowMillis    event timestamp
     */
    void record(BeverageTier tier, BeverageDevice device, String beverageId, int count, long nowMillis) {
        totalBrewingEvents = satAdd(totalBrewingEvents, 1);
        if (uniqueBeverages.size() < MAX_TRACKED_ITEMS) {
            uniqueBeverages.add(beverageId);
        }
        if (tier != null) {
            tierCounts.put(tier, satAdd(tierCounts.getOrDefault(tier, 0), count));
        }
        deviceCounts.put(device, satAdd(deviceCounts.getOrDefault(device, 0), 1));
        totalBeveragesPrepared = satAdd(totalBeveragesPrepared, count);
        Integer existing = itemCounts.get(beverageId);
        if (existing != null) {
            itemCounts.put(beverageId, satAdd(existing, count));
        } else if (itemCounts.size() < MAX_TRACKED_ITEMS) {
            itemCounts.put(beverageId, count);
        }
        if (firstPreparedAt == 0L) {
            firstPreparedAt = nowMillis;
        }
        lastPreparedAt = nowMillis;
        lastBeverage = beverageId;
        lastDevice = device.name();
        lastTier = tier != null ? tier.name() : "";
    }

    // ---- accessors (defensive copies) ----

    public int getTotalBrewingEvents() {
        return totalBrewingEvents;
    }

    public int getTotalBeveragesPrepared() {
        return totalBeveragesPrepared;
    }

    public int getUniqueBeverageCount() {
        return uniqueBeverages.size();
    }

    public Set<String> getUniqueBeverages() {
        return Collections.unmodifiableSet(new HashSet<>(uniqueBeverages));
    }

    public Map<BeverageTier, Integer> getTierCounts() {
        return Collections.unmodifiableMap(new EnumMap<>(tierCounts));
    }

    public Map<BeverageDevice, Integer> getDeviceCounts() {
        return Collections.unmodifiableMap(new EnumMap<>(deviceCounts));
    }

    public Map<String, Integer> getItemCounts() {
        return Collections.unmodifiableMap(new HashMap<>(itemCounts));
    }

    public long getFirstPreparedAt() {
        return firstPreparedAt;
    }

    public long getLastPreparedAt() {
        return lastPreparedAt;
    }

    public String getLastBeverage() {
        return lastBeverage;
    }

    public String getLastDevice() {
        return lastDevice;
    }

    public String getLastTier() {
        return lastTier;
    }

    /**
     * Most-prepared beverage id, or {@code ""} if none. Ties broken by full id
     * lexicographic ascending order.
     */
    public String getMostPreparedBeverage() {
        List<Map.Entry<String, Integer>> ranked = rankedItemSnapshots();
        return ranked.isEmpty() ? "" : ranked.getFirst().getKey();
    }

    /** Count of the most-prepared beverage, or {@code 0} if none. */
    public int getMostPreparedBeverageCount() {
        List<Map.Entry<String, Integer>> ranked = rankedItemSnapshots();
        return ranked.isEmpty() ? 0 : ranked.getFirst().getValue();
    }

    private List<Map.Entry<String, Integer>> rankedItemSnapshots() {
        if (itemCounts.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(itemCounts.size());
        for (Map.Entry<String, Integer> e : itemCounts.entrySet()) {
            list.add(Map.entry(e.getKey(), e.getValue()));
        }
        list.sort(ITEM_RANK);
        return list;
    }

    // ---- NBT ----

    private static final String KEY_EVENTS = "totalBrewingEvents";
    private static final String KEY_TOTAL = "totalBeveragesPrepared";
    private static final String KEY_UNIQUE = "uniqueBeverages";
    private static final String KEY_TIERS = "tierCounts";
    private static final String KEY_DEVICES = "deviceCounts";
    private static final String KEY_ITEMS = "itemCounts";
    private static final String KEY_FIRST = "firstPreparedAt";
    private static final String KEY_LAST = "lastPreparedAt";
    private static final String KEY_LAST_BEVERAGE = "lastBeverage";
    private static final String KEY_LAST_DEVICE = "lastDevice";
    private static final String KEY_LAST_TIER = "lastTier";

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_EVENTS, totalBrewingEvents);
        tag.putInt(KEY_TOTAL, totalBeveragesPrepared);
        tag.putLong(KEY_FIRST, firstPreparedAt);
        tag.putLong(KEY_LAST, lastPreparedAt);
        tag.putString(KEY_LAST_BEVERAGE, lastBeverage);
        tag.putString(KEY_LAST_DEVICE, lastDevice);
        tag.putString(KEY_LAST_TIER, lastTier);

        ListTag unique = new ListTag();
        uniqueBeverages.stream().sorted().forEach(i -> unique.add(StringTag.valueOf(i)));
        tag.put(KEY_UNIQUE, unique);

        tag.put(KEY_TIERS, enumCounts(tierCounts, BeverageTier.class));
        tag.put(KEY_DEVICES, enumCounts(deviceCounts, BeverageDevice.class));

        CompoundTag items = new CompoundTag();
        itemCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> items.putInt(e.getKey(), e.getValue()));
        tag.put(KEY_ITEMS, items);
        return tag;
    }

    static PlayerBrewingStats load(CompoundTag tag) {
        PlayerBrewingStats stats = new PlayerBrewingStats();
        // Reject negative counters: treat them as 0 (saturated, never negative).
        stats.totalBrewingEvents = sanitizeCount(tag.getInt(KEY_EVENTS));
        stats.totalBeveragesPrepared = sanitizeCount(tag.getInt(KEY_TOTAL));
        stats.firstPreparedAt = tag.getLong(KEY_FIRST);
        stats.lastPreparedAt = tag.getLong(KEY_LAST);
        stats.lastBeverage = sanitizeBeverageId(tag.getString(KEY_LAST_BEVERAGE));
        stats.lastDevice = tag.getString(KEY_LAST_DEVICE);
        stats.lastTier = tag.getString(KEY_LAST_TIER);

        for (Tag t : tag.getList(KEY_UNIQUE, Tag.TAG_STRING)) {
            if (stats.uniqueBeverages.size() >= MAX_TRACKED_ITEMS) {
                break;
            }
            String id = sanitizeBeverageId(t.getAsString());
            if (!id.isEmpty()) {
                stats.uniqueBeverages.add(id);
            }
        }
        loadEnumCounts(tag.getCompound(KEY_TIERS), stats.tierCounts, BeverageTier.class);
        loadEnumCounts(tag.getCompound(KEY_DEVICES), stats.deviceCounts, BeverageDevice.class);
        CompoundTag items = tag.getCompound(KEY_ITEMS);
        for (String key : items.getAllKeys()) {
            if (stats.itemCounts.size() >= MAX_TRACKED_ITEMS) {
                break;
            }
            String id = sanitizeBeverageId(key);
            if (id.isEmpty()) {
                continue; // reject malformed resource locations
            }
            int count = sanitizeCount(items.getInt(key));
            if (count > 0) {
                stats.itemCounts.put(id, count);
            }
        }
        return stats;
    }

    /** ResourceLocation-valid item id, or {@code ""} when malformed. */
    private static String sanitizeBeverageId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Must be an explicit "namespace:path" with a valid namespace and no
        // path traversal. Reject implicit-default-namespace inputs ("path"
        // without a colon), empty namespace/path, and any ".." segment.
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            return "";
        }
        if (raw.contains("..")) {
            return "";
        }
        try {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(raw);
            return id != null ? id.toString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Negative counters are rejected (clamped to 0); positive values pass. */
    private static int sanitizeCount(int value) {
        return Math.max(0, value);
    }

    private static <E extends Enum<E>> void loadEnumCounts(CompoundTag tag, Map<E, Integer> target, Class<E> enumType) {
        for (String key : tag.getAllKeys()) {
            try {
                int count = sanitizeCount(tag.getInt(key));
                if (count > 0) {
                    target.put(Enum.valueOf(enumType, key), count);
                }
            } catch (IllegalArgumentException e) {
                // Unknown device/tier: skip, never fail loading.
            }
        }
    }

    private static <E extends Enum<E>> CompoundTag enumCounts(Map<E, Integer> counts, Class<E> enumType) {
        CompoundTag tag = new CompoundTag();
        counts.forEach((e, c) -> tag.putInt(e.name(), c));
        return tag;
    }
}
