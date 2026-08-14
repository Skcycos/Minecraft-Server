package com.tanrunn.tcth.impl.shadow;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * A parsed {@code data/&lt;pack&gt;/shadow_loot/&lt;namespace&gt;/&lt;path&gt;.json} definition
 * (8D.1 §3, schema from 8D.0.2 §6).
 *
 * <p>Strict constraints (any violation → the WHOLE definition is rejected,
 * never a fallback pool):
 * <ul>
 *   <li>1..8 pools; each pool 1..32 entries;</li>
 *   <li>entry weight 1..1,000,000; pool weight 1..1,000,000; weight sums use
 *       {@code long} with overflow protection;</li>
 *   <li>min_count/max_count 1..4, min &lt;= max;</li>
 *   <li>entity id and item ids must resolve in their registries (validated by
 *       the caller with registry access).</li>
 * </ul>
 */
public record ShadowLootDefinition(ResourceLocation entityType, List<ShadowLootPool> pools) {

    public record ShadowLootPool(long weight, List<ShadowLootEntry> entries) {
    }

    public record ShadowLootEntry(ResourceLocation itemId, long weight, int minCount, int maxCount) {
    }

    public static final int MIN_POOLS = 1;
    public static final int MAX_POOLS = 8;
    public static final int MIN_ENTRIES = 1;
    public static final int MAX_ENTRIES = 32;
    public static final long MIN_WEIGHT = 1L;
    public static final long MAX_WEIGHT = 1_000_000L;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 4;

    /**
     * Parses a definition from JSON without registry access (entity/item id
     * existence is validated separately against the registries).
     *
     * @return the parsed definition, or {@code null} when ANY field is
     *         invalid (fail-closed)
     */
    public static ShadowLootDefinition parse(ResourceLocation entityType, JsonObject json) {
        if (entityType == null || json == null) {
            return null;
        }
        try {
        JsonElement poolsElement = json.get("pools");
        if (poolsElement == null || !poolsElement.isJsonArray()) {
            return null;
        }
        JsonArray poolsArray = poolsElement.getAsJsonArray();
        if (poolsArray.size() < MIN_POOLS || poolsArray.size() > MAX_POOLS) {
            return null;
        }
        List<ShadowLootPool> pools = new ArrayList<>(poolsArray.size());
        for (int i = 0; i < poolsArray.size(); i++) {
            JsonElement poolElement = poolsArray.get(i);
            if (!poolElement.isJsonObject()) {
                return null;
            }
            JsonObject pool = poolElement.getAsJsonObject();
            Long poolWeight = parseWeight(pool, "weight");
            if (poolWeight == null) {
                return null;
            }
            JsonElement entriesElement = pool.get("entries");
            if (entriesElement == null || !entriesElement.isJsonArray()) {
                return null;
            }
            JsonArray entriesArray = entriesElement.getAsJsonArray();
            if (entriesArray.size() < MIN_ENTRIES || entriesArray.size() > MAX_ENTRIES) {
                return null;
            }
            List<ShadowLootEntry> entries = new ArrayList<>(entriesArray.size());
            for (int e = 0; e < entriesArray.size(); e++) {
                JsonElement entryElement = entriesArray.get(e);
                if (!entryElement.isJsonObject()) {
                    return null;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                ShadowLootEntry parsed = parseEntry(entry);
                if (parsed == null) {
                    return null;
                }
                entries.add(parsed);
            }
            // Weight sum overflow protection (long).
            long poolSum = 0L;
            for (ShadowLootEntry entry : entries) {
                if (poolSum > Long.MAX_VALUE - entry.weight()) {
                    return null; // overflow → invalid
                }
                poolSum += entry.weight();
            }
            pools.add(new ShadowLootPool(poolWeight, List.copyOf(entries)));
        }
        return new ShadowLootDefinition(entityType, List.copyOf(pools));
        } catch (RuntimeException e) {
            return null; // unparsable JSON (NaN etc.) → rejected
        }
    }

    private static ShadowLootEntry parseEntry(JsonObject entry) {
        ResourceLocation itemId = parseResourceLocation(entry, "id");
        if (itemId == null) {
            return null;
        }
        Long weight = parseWeight(entry, "weight");
        if (weight == null) {
            return null;
        }
        Integer minCount = parseCount(entry, "min_count");
        if (minCount == null) {
            return null;
        }
        Integer maxCount = parseCount(entry, "max_count");
        if (maxCount == null) {
            return null;
        }
        if (minCount > maxCount) {
            return null;
        }
        return new ShadowLootEntry(itemId, weight, minCount, maxCount);
    }

    private static Long parseWeight(JsonObject object, String key) {
        Long value = parseInteger(object, key);
        if (value == null || value < MIN_WEIGHT || value > MAX_WEIGHT) {
            return null;
        }
        return value;
    }

    private static Integer parseCount(JsonObject object, String key) {
        Long value = parseInteger(object, key);
        if (value == null || value < MIN_COUNT || value > MAX_COUNT) {
            return null;
        }
        return value.intValue();
    }

    /** The value must be a mathematical integer (8D.1.2 §4): decimals like
     *  1.5, NaN, Infinity and exponent overflow are all rejected. */
    private static Long parseInteger(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            java.math.BigDecimal value = element.getAsBigDecimal();
            if (value.stripTrailingZeros().scale() > 0) {
                return null; // not a mathematical integer (e.g. 1.5)
            }
            return value.longValueExact(); // throws ArithmeticException on overflow
        } catch (RuntimeException e) {
            return null; // NaN / Infinity / overflow → rejected
        }
    }

    private static ResourceLocation parseResourceLocation(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(element.getAsString());
        if (rl == null || rl.getPath().contains("..")) {
            return null;
        }
        return rl;
    }
}
