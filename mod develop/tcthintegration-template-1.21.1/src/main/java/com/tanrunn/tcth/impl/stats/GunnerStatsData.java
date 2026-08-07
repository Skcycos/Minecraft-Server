package com.tanrunn.tcth.impl.stats;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-scoped storage for all players' gunner statistics.
 *
 * <p>Stored as a separate {@link SavedData} file
 * ({@code world/data/tcth_gunner_stats.dat}) — never written into the vanilla
 * playerdata. Keyed by player UUID so a name change does not affect data.
 *
 * <p>Format carries a {@code dataVersion} for migrations. Phase 5C bumps the
 * version to {@code 2} for permanent medal unlock state. Loading is defensive:
 * missing/unknown fields fall back to defaults and never fail the world load.
 * All integer counters use saturated addition.
 */
public final class GunnerStatsData extends SavedData {

    public static final String NAME = "tcth_gunner_stats";
    /** Schema version: 1 = 5A counters; 2 = + medals (5C). */
    public static final int DATA_VERSION = 2;
    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_PLAYERS = "players";

    private static final int MAX_TRACKED_PLAYERS = 1024;
    public static final int MAX_WEAPONS = 4096;

    private final Map<UUID, PlayerGunnerStats> players = new HashMap<>();

    public static final Factory<GunnerStatsData> FACTORY = new Factory<>(
            GunnerStatsData::new, GunnerStatsData::load, null);

    /**
     * Returns the stats data for the given level. Always bound to the server
     * overworld's data storage so statistics merge across dimensions.
     */
    public static GunnerStatsData current(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public GunnerStatsData() {
    }

    public PlayerGunnerStats getOrCreate(UUID playerId) {
        PlayerGunnerStats existing = players.get(playerId);
        if (existing != null) {
            return existing; // existing players keep updating even past the cap
        }
        if (players.size() >= MAX_TRACKED_PLAYERS) {
            return null; // safety cap: refuse NEW entries rather than grow unbounded
        }
        PlayerGunnerStats stats = new PlayerGunnerStats();
        players.put(playerId, stats);
        setDirty();
        return stats;
    }

    @Nullable
    public PlayerGunnerStats get(UUID playerId) {
        return players.get(playerId);
    }

    public Map<UUID, PlayerGunnerStats> getPlayers() {
        return Map.copyOf(players);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_VERSION, DATA_VERSION);
        CompoundTag playersTag = new CompoundTag();
        players.forEach((uuid, stats) -> playersTag.put(uuid.toString(), stats.save()));
        tag.put(KEY_PLAYERS, playersTag);
        return tag;
    }

    public static GunnerStatsData load(CompoundTag tag, HolderLookup.Provider registries) {
        GunnerStatsData data = new GunnerStatsData();
        int version = tag.contains(KEY_VERSION) ? tag.getInt(KEY_VERSION) : 0;
        if (version < 0) {
            return data;
        }
        CompoundTag playersTag = tag.getCompound(KEY_PLAYERS);
        boolean needsRewrite = version < DATA_VERSION;
        for (String key : playersTag.getAllKeys()) {
            if (data.players.size() >= MAX_TRACKED_PLAYERS) {
                break; // enforce player cap on load too
            }
            try {
                UUID uuid = UUID.fromString(key);
                CompoundTag playerTag = playersTag.getCompound(key);
                // Detect silent medal reconcile by comparing medal map size after load.
                int medalsBefore = playerTag.contains("unlockedMedals")
                        ? playerTag.getCompound("unlockedMedals").size()
                        : 0;
                PlayerGunnerStats stats = PlayerGunnerStats.load(playerTag);
                if (stats.getUnlockedMedals().size() > medalsBefore) {
                    needsRewrite = true;
                }
                data.players.put(uuid, stats);
            } catch (IllegalArgumentException e) {
                // Unparseable uuid or entry: skip, never fail world load.
            }
        }
        // v1 → v2 (or silent medal fill) must persist on next save.
        if (needsRewrite) {
            data.setDirty();
        }
        return data;
    }
}
