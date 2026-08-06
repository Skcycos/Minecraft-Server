package com.tanrunn.tcth.impl.stats;

import java.util.HashMap;
import java.util.Map;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-player gunner statistics (server-authoritative).
 *
 * <p>Stored in {@link GunnerStatsData} and serialized to NBT. Only
 * {@link ResourceLocation} strings, numbers and necessary plain text are
 * persisted — never full ItemStack / NBT. All integer counters use saturated
 * addition.
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

    PlayerGunnerStats() {
    }

    /**
     * Records a gun kill.
     *
     * @param weaponId item id of the firearm
     * @param targetId entity type id of the killed target
     * @param tier     difficulty tier of the target
     * @param distance distance of the kill
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
        if (distance > maxDistance) {
            maxDistance = distance;
        }
        lastWeapon = weaponId;
        lastTarget = targetId;
        lastTier = tier.name();
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
     * Returns the most-used weapon id, or {@code ""} if no kills recorded.
     */
    public String getMostUsedWeapon() {
        String topWeapon = "";
        int topCount = 0;
        for (Map.Entry<String, Integer> e : weaponKills.entrySet()) {
            if (e.getValue() > topCount) {
                topCount = e.getValue();
                topWeapon = e.getKey();
            }
        }
        return topWeapon;
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
        stats.maxDistance = tag.getFloat(KEY_MAX_DISTANCE);
        stats.lastWeapon = tag.getString(KEY_LAST_WEAPON);
        stats.lastTarget = tag.getString(KEY_LAST_TARGET);
        stats.lastTier = tag.getString(KEY_LAST_TIER);
        stats.firstGunKillAt = tag.getLong(KEY_FIRST_KILL);
        stats.lastGunKillAt = tag.getLong(KEY_LAST_KILL);
        return stats;
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
