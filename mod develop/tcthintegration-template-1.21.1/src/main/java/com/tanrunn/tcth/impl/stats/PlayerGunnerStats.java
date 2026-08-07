package com.tanrunn.tcth.impl.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

import net.minecraft.nbt.CompoundTag;

/**
 * Per-player gunner statistics (server-authoritative).
 *
 * <p>Stored in {@link GunnerStatsData} and serialized to NBT. Only
 * {@link net.minecraft.resources.ResourceLocation} strings, numbers and
 * necessary plain text are persisted — never full ItemStack / NBT. All integer
 * counters use saturated addition.
 *
 * <p>Phase 5C reuses the 5A counters (totals, tiers, weapon map,
 * {@code maxDistance}) and adds permanent medal unlock state. No second stats
 * map is introduced for kills, weapons, tiers or distance.
 */
public final class PlayerGunnerStats {

    private static final String KEY_TOTAL_KILLS = "totalGunKills";
    private static final String KEY_COMMON = "commonKills";
    private static final String KEY_ELITE = "eliteKills";
    private static final String KEY_HEAVY = "heavyKills";
    private static final String KEY_BOSS = "bossKills";
    private static final String KEY_WEAPON_KILLS = "weaponKills";
    private static final String KEY_UNIQUE_WEAPONS = "uniqueWeapons";
    private static final String KEY_MAX_DISTANCE = "maxDistance";
    private static final String KEY_LAST_WEAPON = "lastWeapon";
    private static final String KEY_LAST_TARGET = "lastTarget";
    private static final String KEY_LAST_TIER = "lastTier";
    private static final String KEY_FIRST_KILL = "firstGunKillAt";
    private static final String KEY_LAST_KILL = "lastGunKillAt";
    private static final String KEY_UNLOCKED_MEDALS = "unlockedMedals";

    /** Hard cap on persisted medal entries (safety). */
    public static final int MAX_MEDALS = 128;

    /**
     * Weapon ranking: kill count descending, then full {@code namespace:path}
     * ascending. Independent of HashMap / NBT iteration order.
     */
    private static final Comparator<Map.Entry<String, Integer>> WEAPON_RANK =
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey);

    private int totalGunKills;
    private int commonKills;
    private int eliteKills;
    private int heavyKills;
    private int bossKills;
    private final Map<String, Integer> weaponKills = new HashMap<>();
    private int uniqueWeapons;
    private float maxDistance;
    private String lastWeapon = "";
    private String lastTarget = "";
    private String lastTier = "";
    private long firstGunKillAt;
    private long lastGunKillAt;
    /** medalId → unlockedAtEpochMillis (0 = historical / unknown). */
    private final Map<String, Long> unlockedMedals = new LinkedHashMap<>();

    PlayerGunnerStats() {
    }

    /**
     * Records a gun kill.
     *
     * @param weaponId item id of the firearm
     * @param targetId entity type id of the killed target
     * @param tier     difficulty tier of the target
     * @param distance distance of the kill (non-finite / negative ignored for max)
     * @param timestamp epoch millis
     */
    public void record(String weaponId, String targetId, GunTargetTier tier, float distance, long timestamp) {
        totalGunKills = saturateAdd(totalGunKills, 1);
        switch (tier) {
            case COMMON -> commonKills = saturateAdd(commonKills, 1);
            case ELITE -> eliteKills = saturateAdd(eliteKills, 1);
            case HEAVY -> heavyKills = saturateAdd(heavyKills, 1);
            case BOSS -> bossKills = saturateAdd(bossKills, 1);
        }
        // Weapon cap: existing weapons keep accumulating past the cap; NEW
        // weapon ids beyond the cap are not tracked (but the kill still counts
        // in totalGunKills and the tier counters above).
        if (!weaponKills.containsKey(weaponId) && weaponKills.size() >= GunnerStatsData.MAX_WEAPONS) {
            // cap reached: do not add a new weapon entry
        } else {
            int newCount = weaponKills.merge(weaponId, 1, (old, one) -> saturateAdd(old, one));
            if (newCount == 1) {
                uniqueWeapons = saturateAdd(uniqueWeapons, 1);
            }
        }
        if (Float.isFinite(distance) && distance >= 0.0f && distance > maxDistance) {
            maxDistance = distance;
        }
        lastWeapon = weaponId != null ? weaponId : "";
        lastTarget = targetId != null ? targetId : "";
        lastTier = tier != null ? tier.name() : "";
        if (firstGunKillAt == 0L) {
            firstGunKillAt = timestamp;
        }
        lastGunKillAt = timestamp;
    }

    public int getTotalGunKills() {
        return totalGunKills;
    }

    public int getCommonKills() {
        return commonKills;
    }

    public int getEliteKills() {
        return eliteKills;
    }

    public int getHeavyKills() {
        return heavyKills;
    }

    public int getBossKills() {
        return bossKills;
    }

    public Map<String, Integer> getWeaponKills() {
        return Map.copyOf(weaponKills);
    }

    public int getUniqueWeapons() {
        return uniqueWeapons;
    }

    public float getMaxDistance() {
        return maxDistance;
    }

    public String getLastWeapon() {
        return lastWeapon;
    }

    public String getLastTarget() {
        return lastTarget;
    }

    public String getLastTier() {
        return lastTier;
    }

    public long getFirstGunKillAt() {
        return firstGunKillAt;
    }

    public long getLastGunKillAt() {
        return lastGunKillAt;
    }

    /**
     * Most-used weapon id, or {@code ""} if none. Ties broken by full id
     * lexicographic ascending order.
     */
    public String getMostUsedWeapon() {
        List<Map.Entry<String, Integer>> ranked = rankedWeaponSnapshots();
        return ranked.isEmpty() ? "" : ranked.getFirst().getKey();
    }

    /**
     * Kill count of the most-used weapon, or {@code 0} if none.
     */
    public int getMostUsedWeaponKills() {
        List<Map.Entry<String, Integer>> ranked = rankedWeaponSnapshots();
        return ranked.isEmpty() ? 0 : ranked.getFirst().getValue();
    }

    /**
     * Top-{@code n} weapons: count desc, id asc. Returns an unmodifiable list of
     * <em>immutable</em> {@link Map#entry} snapshots — callers cannot
     * {@code setValue} into the internal weapon map.
     */
    public List<Map.Entry<String, Integer>> getTopWeapons(int n) {
        if (n <= 0 || weaponKills.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> ranked = rankedWeaponSnapshots();
        if (ranked.size() <= n) {
            return List.copyOf(ranked);
        }
        return List.copyOf(ranked.subList(0, n));
    }

    /**
     * Sorted weapon snapshots (immutable entries). Safe to expose externally.
     */
    private List<Map.Entry<String, Integer>> rankedWeaponSnapshots() {
        if (weaponKills.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(weaponKills.size());
        for (Map.Entry<String, Integer> e : weaponKills.entrySet()) {
            // Snapshot: Map.entry is immutable; setValue throws UnsupportedOperationException.
            list.add(Map.entry(e.getKey(), e.getValue()));
        }
        list.sort(WEAPON_RANK);
        return list;
    }

    /**
     * Unmodifiable view of unlocked medals ({@code medalId → unlockedAt}).
     */
    public Map<String, Long> getUnlockedMedals() {
        return Collections.unmodifiableMap(unlockedMedals);
    }

    public boolean hasMedal(GunnerMedal medal) {
        return medal != null && unlockedMedals.containsKey(medal.id());
    }

    /**
     * Permanently unlocks {@code medal} if not already held and under the
     * safety cap. Once unlocked, never revoked.
     *
     * @return {@code true} if this call newly unlocked the medal
     */
    public boolean tryUnlock(GunnerMedal medal, long unlockedAtEpochMillis) {
        if (medal == null) {
            return false;
        }
        if (unlockedMedals.containsKey(medal.id())) {
            return false;
        }
        if (unlockedMedals.size() >= MAX_MEDALS) {
            return false;
        }
        unlockedMedals.put(medal.id(), unlockedAtEpochMillis);
        return true;
    }

    /**
     * Unlocked medals in fixed definition order (only those present).
     */
    public List<GunnerMedal> getUnlockedMedalsInOrder() {
        List<GunnerMedal> ordered = new ArrayList<>();
        for (GunnerMedal medal : GunnerMedal.values()) {
            if (unlockedMedals.containsKey(medal.id())) {
                ordered.add(medal);
            }
        }
        return List.copyOf(ordered);
    }

    // ---- NBT ----

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_TOTAL_KILLS, totalGunKills);
        tag.putInt(KEY_COMMON, commonKills);
        tag.putInt(KEY_ELITE, eliteKills);
        tag.putInt(KEY_HEAVY, heavyKills);
        tag.putInt(KEY_BOSS, bossKills);
        CompoundTag weaponsTag = new CompoundTag();
        weaponKills.forEach(weaponsTag::putInt);
        tag.put(KEY_WEAPON_KILLS, weaponsTag);
        tag.putInt(KEY_UNIQUE_WEAPONS, uniqueWeapons);
        tag.putFloat(KEY_MAX_DISTANCE, maxDistance);
        tag.putString(KEY_LAST_WEAPON, lastWeapon);
        tag.putString(KEY_LAST_TARGET, lastTarget);
        tag.putString(KEY_LAST_TIER, lastTier);
        tag.putLong(KEY_FIRST_KILL, firstGunKillAt);
        tag.putLong(KEY_LAST_KILL, lastGunKillAt);
        CompoundTag medalsTag = new CompoundTag();
        unlockedMedals.forEach(medalsTag::putLong);
        tag.put(KEY_UNLOCKED_MEDALS, medalsTag);
        return tag;
    }

    static PlayerGunnerStats load(CompoundTag tag) {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.totalGunKills = tag.getInt(KEY_TOTAL_KILLS);
        stats.commonKills = tag.getInt(KEY_COMMON);
        stats.eliteKills = tag.getInt(KEY_ELITE);
        stats.heavyKills = tag.getInt(KEY_HEAVY);
        stats.bossKills = tag.getInt(KEY_BOSS);
        CompoundTag weaponsTag = tag.getCompound(KEY_WEAPON_KILLS);
        for (String key : weaponsTag.getAllKeys()) {
            if (stats.weaponKills.size() >= GunnerStatsData.MAX_WEAPONS) {
                break;
            }
            stats.weaponKills.put(key, weaponsTag.getInt(key));
        }
        stats.uniqueWeapons = tag.getInt(KEY_UNIQUE_WEAPONS);
        stats.maxDistance = sanitizeDistance(tag.getFloat(KEY_MAX_DISTANCE));
        stats.lastWeapon = tag.getString(KEY_LAST_WEAPON);
        stats.lastTarget = tag.getString(KEY_LAST_TARGET);
        stats.lastTier = tag.getString(KEY_LAST_TIER);
        stats.firstGunKillAt = tag.getLong(KEY_FIRST_KILL);
        stats.lastGunKillAt = tag.getLong(KEY_LAST_KILL);
        if (tag.contains(KEY_UNLOCKED_MEDALS)) {
            CompoundTag medalsTag = tag.getCompound(KEY_UNLOCKED_MEDALS);
            for (String key : medalsTag.getAllKeys()) {
                if (stats.unlockedMedals.size() >= MAX_MEDALS) {
                    break;
                }
                GunnerMedal.byId(key).ifPresent(medal -> {
                    // Unknown ids already filtered by byId; merge duplicates by first win.
                    if (!stats.unlockedMedals.containsKey(medal.id())) {
                        stats.unlockedMedals.put(medal.id(), medalsTag.getLong(key));
                    }
                });
            }
        }
        // Silent consistency: stats may already satisfy medals missing from NBT
        // (v1 archives or partial writes). Never announces.
        GunnerMedalEvaluator.reconcileSilent(stats);
        return stats;
    }

    static float sanitizeDistance(float distance) {
        if (!Float.isFinite(distance) || distance < 0.0f) {
            return 0.0f;
        }
        return distance;
    }

    private static int saturateAdd(int a, int b) {
        long sum = (long) a + (long) b;
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (sum < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) sum;
    }
}
