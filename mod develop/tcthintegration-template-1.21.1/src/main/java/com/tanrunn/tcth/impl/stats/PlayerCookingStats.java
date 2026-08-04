package com.tanrunn.tcth.impl.stats;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Per-player cooking statistics.
 *
 * <p>Records what a player has cooked: cooking-event count, total dish count
 * (sum of stack counts), unique result items, per-device event counts,
 * per-tier/per-quality counts and per-item cumulative counts.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code totalCookingEvents} and {@code deviceCounts} increase by 1 per
 *       event;</li>
 *   <li>{@code totalDishesCooked}, {@code tierCounts}, {@code qualityCounts}
 *       and {@code itemCounts} accumulate the stack {@code count};</li>
 *   <li>all integer accumulation is saturating (never overflows);</li>
 *   <li>{@code itemCounts} and {@code uniqueResultItems} are capped — once the
 *       cap is reached, existing items keep accumulating but new ones are not
 *       added;</li>
 *   <li>the loader tolerates unknown device/tier/quality values (skipped, never
 *       fatal).</li>
 * </ul>
 */
public final class PlayerCookingStats {

    private static final int MAX_TRACKED_ITEMS = 4096;

    private int totalCookingEvents = 0;
    private int totalDishesCooked = 0;
    private final Set<String> uniqueResultItems = new HashSet<>();
    private final Map<CookingDevice, Integer> deviceCounts = new EnumMap<>(CookingDevice.class);
    private final Map<DishTier, Integer> tierCounts = new EnumMap<>(DishTier.class);
    private final Map<DishQuality, Integer> qualityCounts = new EnumMap<>(DishQuality.class);
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private long firstCookedAt = -1;
    private long lastCookedAt = -1;
    private String lastDish = "";
    private String lastDevice = "";
    private String lastTier = "";
    private String lastQuality = "";

    /** Saturating addition: clamps at Integer.MAX_VALUE, never overflows. */
    private static int satAdd(int a, int b) {
        if (b <= 0) {
            return a;
        }
        long sum = (long) a + b;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * Records one verified dish event.
     *
     * @param count the stack count (must be &gt; 0; caller filters)
     */
    void record(CookingDevice device, DishTier tier, DishQuality quality, String resultItemId,
                int count, long nowMillis) {
        totalCookingEvents = satAdd(totalCookingEvents, 1);
        if (uniqueResultItems.size() < MAX_TRACKED_ITEMS) {
            uniqueResultItems.add(resultItemId);
        }
        deviceCounts.put(device, satAdd(deviceCounts.getOrDefault(device, 0), 1));
        if (tier != null) {
            tierCounts.put(tier, satAdd(tierCounts.getOrDefault(tier, 0), count));
        }
        qualityCounts.put(quality, satAdd(qualityCounts.getOrDefault(quality, 0), count));
        totalDishesCooked = satAdd(totalDishesCooked, count);
        // Item counts: existing items keep accumulating even past the cap;
        // new items are only added while under the cap.
        Integer existing = itemCounts.get(resultItemId);
        if (existing != null) {
            itemCounts.put(resultItemId, satAdd(existing, count));
        } else if (itemCounts.size() < MAX_TRACKED_ITEMS) {
            itemCounts.put(resultItemId, count);
        }
        if (firstCookedAt < 0) {
            firstCookedAt = nowMillis;
        }
        lastCookedAt = nowMillis;
        lastDish = resultItemId;
        lastDevice = device.name();
        lastTier = tier != null ? tier.name() : "";
        lastQuality = quality.name();
    }

    // ---- accessors (defensive copies) ----

    public int getTotalCookingEvents() {
        return totalCookingEvents;
    }

    public int getTotalDishesCooked() {
        return totalDishesCooked;
    }

    public int getUniqueDishCount() {
        return uniqueResultItems.size();
    }

    public Set<String> getUniqueResultItems() {
        return Collections.unmodifiableSet(new HashSet<>(uniqueResultItems));
    }

    public Map<CookingDevice, Integer> getDeviceCounts() {
        return Collections.unmodifiableMap(new EnumMap<>(deviceCounts));
    }

    public Map<DishTier, Integer> getTierCounts() {
        return Collections.unmodifiableMap(new EnumMap<>(tierCounts));
    }

    public Map<DishQuality, Integer> getQualityCounts() {
        return Collections.unmodifiableMap(new EnumMap<>(qualityCounts));
    }

    public Map<String, Integer> getItemCounts() {
        return Collections.unmodifiableMap(new HashMap<>(itemCounts));
    }

    public long getFirstCookedAt() {
        return firstCookedAt;
    }

    public long getLastCookedAt() {
        return lastCookedAt;
    }

    public String getLastDish() {
        return lastDish;
    }

    public String getLastDevice() {
        return lastDevice;
    }

    public String getLastTier() {
        return lastTier;
    }

    public String getLastQuality() {
        return lastQuality;
    }

    // ---- NBT ----

    private static final String KEY_EVENTS = "totalCookingEvents";
    private static final String KEY_TOTAL = "totalDishesCooked";
    private static final String KEY_UNIQUE = "uniqueResultItems";
    private static final String KEY_DEVICES = "deviceCounts";
    private static final String KEY_TIERS = "tierCounts";
    private static final String KEY_QUALITIES = "qualityCounts";
    private static final String KEY_ITEMS = "itemCounts";
    private static final String KEY_FIRST = "firstCookedAt";
    private static final String KEY_LAST = "lastCookedAt";
    private static final String KEY_LAST_DISH = "lastDish";
    private static final String KEY_LAST_DEVICE = "lastDevice";
    private static final String KEY_LAST_TIER = "lastTier";
    private static final String KEY_LAST_QUALITY = "lastQuality";

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_EVENTS, totalCookingEvents);
        tag.putInt(KEY_TOTAL, totalDishesCooked);
        tag.putLong(KEY_FIRST, firstCookedAt);
        tag.putLong(KEY_LAST, lastCookedAt);
        tag.putString(KEY_LAST_DISH, lastDish);
        tag.putString(KEY_LAST_DEVICE, lastDevice);
        tag.putString(KEY_LAST_TIER, lastTier);
        tag.putString(KEY_LAST_QUALITY, lastQuality);

        ListTag unique = new ListTag();
        uniqueResultItems.stream().sorted().forEach(i -> unique.add(StringTag.valueOf(i)));
        tag.put(KEY_UNIQUE, unique);

        tag.put(KEY_DEVICES, enumCounts(deviceCounts, CookingDevice.class));
        tag.put(KEY_TIERS, enumCounts(tierCounts, DishTier.class));
        tag.put(KEY_QUALITIES, enumCounts(qualityCounts, DishQuality.class));

        CompoundTag items = new CompoundTag();
        itemCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> items.putInt(e.getKey(), e.getValue()));
        tag.put(KEY_ITEMS, items);
        return tag;
    }

    static PlayerCookingStats load(CompoundTag tag) {
        PlayerCookingStats stats = new PlayerCookingStats();
        stats.totalCookingEvents = tag.getInt(KEY_EVENTS);
        stats.totalDishesCooked = tag.getInt(KEY_TOTAL);
        stats.firstCookedAt = tag.getLong(KEY_FIRST);
        stats.lastCookedAt = tag.getLong(KEY_LAST);
        stats.lastDish = tag.getString(KEY_LAST_DISH);
        stats.lastDevice = tag.getString(KEY_LAST_DEVICE);
        stats.lastTier = tag.getString(KEY_LAST_TIER);
        stats.lastQuality = tag.getString(KEY_LAST_QUALITY);

        for (Tag t : tag.getList(KEY_UNIQUE, Tag.TAG_STRING)) {
            if (stats.uniqueResultItems.size() >= MAX_TRACKED_ITEMS) {
                break;
            }
            stats.uniqueResultItems.add(t.getAsString());
        }
        loadEnumCounts(tag.getCompound(KEY_DEVICES), stats.deviceCounts, CookingDevice.class);
        loadEnumCounts(tag.getCompound(KEY_TIERS), stats.tierCounts, DishTier.class);
        loadEnumCounts(tag.getCompound(KEY_QUALITIES), stats.qualityCounts, DishQuality.class);
        CompoundTag items = tag.getCompound(KEY_ITEMS);
        for (String key : items.getAllKeys()) {
            if (stats.itemCounts.size() >= MAX_TRACKED_ITEMS) {
                break;
            }
            stats.itemCounts.put(key, items.getInt(key));
        }
        return stats;
    }

    private static <E extends Enum<E>> void loadEnumCounts(CompoundTag tag, Map<E, Integer> target, Class<E> enumType) {
        for (String key : tag.getAllKeys()) {
            try {
                target.put(Enum.valueOf(enumType, key), tag.getInt(key));
            } catch (IllegalArgumentException e) {
                // Unknown device/tier/quality: skip, never fail loading.
            }
        }
    }

    private static <E extends Enum<E>> CompoundTag enumCounts(Map<E, Integer> counts, Class<E> enumType) {
        CompoundTag tag = new CompoundTag();
        counts.forEach((e, c) -> tag.putInt(e.name(), c));
        return tag;
    }
}
