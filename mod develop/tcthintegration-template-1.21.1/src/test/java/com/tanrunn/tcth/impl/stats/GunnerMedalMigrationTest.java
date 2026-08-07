package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Phase 5C: version-1 archives silently gain medals; no announcements.
 */
class GunnerMedalMigrationTest {

    private static HolderLookup.Provider provider() {
        MinecraftTestBootstrap.bootStrap();
        return HolderLookup.Provider.create(java.util.stream.Stream.empty());
    }

    private static CompoundTag v1Player(int total, int elite, int boss, float maxDistance) {
        CompoundTag p = new CompoundTag();
        p.putInt("totalGunKills", total);
        p.putInt("commonKills", Math.max(0, total - elite - boss));
        p.putInt("eliteKills", elite);
        p.putInt("heavyKills", 0);
        p.putInt("bossKills", boss);
        p.putInt("uniqueWeapons", 0);
        p.putFloat("maxDistance", maxDistance);
        p.putString("lastWeapon", "");
        p.putString("lastTarget", "");
        p.putString("lastTier", "");
        p.putLong("firstGunKillAt", 0L);
        p.putLong("lastGunKillAt", 0L);
        // intentionally no unlockedMedals
        return p;
    }

    private static GunnerStatsData loadV1(UUID id, CompoundTag player) {
        CompoundTag players = new CompoundTag();
        players.put(id.toString(), player);
        CompoundTag root = new CompoundTag();
        root.putInt("dataVersion", 1);
        root.put("players", players);
        return GunnerStatsData.load(root, provider());
    }

    @Test
    void version1Total100SilentlyUnlocksFirstBloodAndCenturion() {
        UUID id = UUID.randomUUID();
        GunnerStatsData data = loadV1(id, v1Player(100, 0, 0, 0.0f));
        PlayerGunnerStats stats = data.get(id);
        assertNotNull(stats);
        assertEquals(100, stats.getTotalGunKills());
        assertTrue(stats.hasMedal(GunnerMedal.FIRST_BLOOD));
        assertTrue(stats.hasMedal(GunnerMedal.CENTURION));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.FIRST_BLOOD.id()));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.CENTURION.id()));
        assertFalse(stats.hasMedal(GunnerMedal.LONG_SHOT));
    }

    @Test
    void version1MaxDistance50SilentlyUnlocksLongShot() {
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats = loadV1(id, v1Player(1, 0, 0, 50.0f)).get(id);
        assertNotNull(stats);
        assertEquals(50.0f, stats.getMaxDistance());
        assertTrue(stats.hasMedal(GunnerMedal.LONG_SHOT));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.LONG_SHOT.id()));
    }

    @Test
    void version1Elite25SilentlyUnlocksEliteHunter() {
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats = loadV1(id, v1Player(25, 25, 0, 0.0f)).get(id);
        assertNotNull(stats);
        assertEquals(25, stats.getEliteKills());
        assertTrue(stats.hasMedal(GunnerMedal.ELITE_HUNTER));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.ELITE_HUNTER.id()));
    }

    @Test
    void version1Boss1SilentlyUnlocksBossFinisher() {
        UUID id = UUID.randomUUID();
        PlayerGunnerStats stats = loadV1(id, v1Player(1, 0, 1, 0.0f)).get(id);
        assertNotNull(stats);
        assertEquals(1, stats.getBossKills());
        assertTrue(stats.hasMedal(GunnerMedal.BOSS_FINISHER));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.BOSS_FINISHER.id()));
    }

    @Test
    void migrationDoesNotChangeCounters() {
        UUID id = UUID.randomUUID();
        CompoundTag p = v1Player(42, 3, 1, 12.5f);
        PlayerGunnerStats stats = loadV1(id, p).get(id);
        assertNotNull(stats);
        assertEquals(42, stats.getTotalGunKills());
        assertEquals(3, stats.getEliteKills());
        assertEquals(1, stats.getBossKills());
        assertEquals(12.5f, stats.getMaxDistance());
    }

    @Test
    void saveWritesDataVersion2() {
        UUID id = UUID.randomUUID();
        GunnerStatsData data = loadV1(id, v1Player(5, 0, 0, 0.0f));
        CompoundTag saved = data.save(new CompoundTag(), provider());
        assertEquals(GunnerStatsData.DATA_VERSION, saved.getInt("dataVersion"));
        assertEquals(2, saved.getInt("dataVersion"));
    }

    @Test
    void version2MissingMedalIsSilentlyFilled() {
        UUID id = UUID.randomUUID();
        CompoundTag p = v1Player(100, 0, 0, 0.0f);
        // Pretend v2 without medals block
        CompoundTag players = new CompoundTag();
        players.put(id.toString(), p);
        CompoundTag root = new CompoundTag();
        root.putInt("dataVersion", 2);
        root.put("players", players);
        PlayerGunnerStats stats = GunnerStatsData.load(root, provider()).get(id);
        assertNotNull(stats);
        assertTrue(stats.hasMedal(GunnerMedal.CENTURION));
        assertEquals(0L, stats.getUnlockedMedals().get(GunnerMedal.CENTURION.id()));
    }

    @Test
    void unknownMedalIdIsSkipped() {
        CompoundTag medals = new CompoundTag();
        medals.putLong("not_a_real_medal", 123L);
        medals.putLong("first_blood", 456L);
        CompoundTag p = v1Player(1, 0, 0, 0.0f);
        p.put("unlockedMedals", medals);
        UUID id = UUID.randomUUID();
        CompoundTag players = new CompoundTag();
        players.put(id.toString(), p);
        CompoundTag root = new CompoundTag();
        root.putInt("dataVersion", 2);
        root.put("players", players);
        PlayerGunnerStats stats = GunnerStatsData.load(root, provider()).get(id);
        assertNotNull(stats);
        assertFalse(stats.getUnlockedMedals().containsKey("not_a_real_medal"));
        assertTrue(stats.hasMedal(GunnerMedal.FIRST_BLOOD));
        assertEquals(456L, stats.getUnlockedMedals().get(GunnerMedal.FIRST_BLOOD.id()));
    }

    @Test
    void migrationDoesNotUseAnnounceSink() {
        // Load path never touches the tracker announce seam.
        AtomicInteger announces = new AtomicInteger();
        GunnerStatsTracker.resetForTesting();
        GunnerStatsTracker.setAnnounceSinkForTesting((p, msg) -> announces.incrementAndGet());
        try {
            UUID id = UUID.randomUUID();
            loadV1(id, v1Player(100, 25, 1, 50.0f));
            assertEquals(0, announces.get(), "migration must never announce");
        } finally {
            GunnerStatsTracker.resetForTesting();
        }
    }
}
