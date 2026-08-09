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
 * World-scoped storage for all players' brewing statistics (phase 7D).
 *
 * <p>Stored as a separate {@link SavedData} file
 * ({@code world/data/tcth_brewing_stats.dat}) — never written into the vanilla
 * playerdata, independent of Jobs+/Arc. Keyed by player UUID so a name change
 * does not affect data.
 *
 * <p>Format carries a {@code dataVersion} for future migrations. Loading is
 * defensive: unparseable UUIDs / unknown fields are skipped and never fail the
 * world load. All counters use saturated addition.
 */
public final class BrewingStatsData extends SavedData {

    public static final String NAME = "tcth_brewing_stats";
    /** Schema version (phase 7D). */
    public static final int DATA_VERSION = 1;
    private static final String KEY_VERSION = "dataVersion";
    private static final String KEY_PLAYERS = "players";

    private static final int MAX_TRACKED_PLAYERS = 1024;

    private final Map<UUID, PlayerBrewingStats> players = new HashMap<>();

    public static final Factory<BrewingStatsData> FACTORY = new Factory<>(
            BrewingStatsData::new, BrewingStatsData::load, null);

    /**
     * Returns the stats data for the given level. Always bound to the server
     * overworld's data storage so statistics merge across dimensions.
     */
    public static BrewingStatsData current(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public BrewingStatsData() {
    }

    public PlayerBrewingStats getOrCreate(UUID playerId) {
        PlayerBrewingStats existing = players.get(playerId);
        if (existing != null) {
            return existing; // existing players keep updating even past the cap
        }
        if (players.size() >= MAX_TRACKED_PLAYERS) {
            return null; // safety cap: refuse NEW entries rather than grow unbounded
        }
        PlayerBrewingStats stats = new PlayerBrewingStats();
        players.put(playerId, stats);
        setDirty();
        return stats;
    }

    @Nullable
    public PlayerBrewingStats get(UUID playerId) {
        return players.get(playerId);
    }

    public Map<UUID, PlayerBrewingStats> getPlayers() {
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

    public static BrewingStatsData load(CompoundTag tag, HolderLookup.Provider registries) {
        BrewingStatsData data = new BrewingStatsData();
        int version = tag.contains(KEY_VERSION) ? tag.getInt(KEY_VERSION) : 0;
        if (version < 0) {
            return data;
        }
        CompoundTag playersTag = tag.getCompound(KEY_PLAYERS);
        for (String key : playersTag.getAllKeys()) {
            if (data.players.size() >= MAX_TRACKED_PLAYERS) {
                break; // enforce player cap on load too
            }
            try {
                UUID uuid = UUID.fromString(key);
                PlayerBrewingStats stats = PlayerBrewingStats.load(playersTag.getCompound(key));
                data.players.put(uuid, stats);
            } catch (IllegalArgumentException e) {
                // Unparseable uuid or entry: skip, never fail world load.
            }
        }
        return data;
    }
}
