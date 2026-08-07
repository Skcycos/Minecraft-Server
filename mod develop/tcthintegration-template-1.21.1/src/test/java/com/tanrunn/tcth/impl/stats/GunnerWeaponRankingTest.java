package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

/**
 * Phase 5C: main-weapon and Top-N ranking (count desc, id asc).
 */
class GunnerWeaponRankingTest {

    private static final String T = "minecraft:zombie";

    private static void kill(PlayerGunnerStats s, String weapon, int times) {
        for (int i = 0; i < times; i++) {
            s.record(weapon, T, GunTargetTier.COMMON, 1.0f, 1000L + i);
        }
    }

    @Test
    void highestCountIsMainWeapon() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:a", 3);
        kill(s, "scguns:b", 5);
        kill(s, "scguns:c", 2);
        assertEquals("scguns:b", s.getMostUsedWeapon());
        assertEquals(5, s.getMostUsedWeaponKills());
    }

    @Test
    void tieBreaksByIdLexicographicAscending() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:zebra", 4);
        kill(s, "scguns:alpha", 4);
        kill(s, "scguns:middle", 4);
        assertEquals("scguns:alpha", s.getMostUsedWeapon());
    }

    @Test
    void top3CountDescThenIdAsc() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:d", 1);
        kill(s, "scguns:c", 5);
        kill(s, "scguns:a", 5);
        kill(s, "scguns:b", 3);
        kill(s, "scguns:e", 2);
        List<Map.Entry<String, Integer>> top = s.getTopWeapons(3);
        assertEquals(3, top.size());
        assertEquals("scguns:a", top.get(0).getKey());
        assertEquals(5, top.get(0).getValue());
        assertEquals("scguns:c", top.get(1).getKey());
        assertEquals(5, top.get(1).getValue());
        assertEquals("scguns:b", top.get(2).getKey());
        assertEquals(3, top.get(2).getValue());
    }

    @Test
    void topNCapsAtAvailable() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:only", 2);
        assertEquals(1, s.getTopWeapons(3).size());
    }

    @Test
    void emptyWeaponsReturnEmptyMainAndTop() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        assertEquals("", s.getMostUsedWeapon());
        assertEquals(0, s.getMostUsedWeaponKills());
        assertTrue(s.getTopWeapons(3).isEmpty());
    }

    @Test
    void topWeaponsListIsUnmodifiable() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:a", 1);
        List<Map.Entry<String, Integer>> top = s.getTopWeapons(3);
        assertThrows(UnsupportedOperationException.class, () -> top.add(Map.entry("x", 1)));
    }

    @Test
    void topWeaponEntriesAreImmutableSnapshots() {
        PlayerGunnerStats s = new PlayerGunnerStats();
        kill(s, "scguns:a", 3);
        int before = s.getWeaponKills().get("scguns:a");
        Map.Entry<String, Integer> entry = s.getTopWeapons(3).getFirst();
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(999));
        assertEquals(before, s.getWeaponKills().get("scguns:a"),
                "setValue on a top-weapon entry must not mutate internal kill counts");
        assertEquals(before, s.getMostUsedWeaponKills());
    }
}
