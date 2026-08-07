package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 5C.1: en_us / zh_cn both define every gunner profile + medal lang key.
 */
class GunnerLangKeysTest {

    private static final Gson GSON = new Gson();
    private static final Path EN = Path.of("src/main/resources/assets/tcth/lang/en_us.json");
    private static final Path ZH = Path.of("src/main/resources/assets/tcth/lang/zh_cn.json");

    private static final Set<String> REQUIRED = requiredKeys();

    private static Set<String> requiredKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (GunnerMedal medal : GunnerMedal.values()) {
            keys.add(medal.translationKey());
        }
        keys.add("tcth.gunner.medal.list_separator");
        keys.add("tcth.gunner.medal.unlocked");
        keys.add("tcth.gunner.profile.title");
        keys.add("tcth.gunner.profile.player");
        keys.add("tcth.gunner.profile.unknown_player");
        keys.add("tcth.gunner.profile.confirmed_kills");
        keys.add("tcth.gunner.profile.unique_weapons");
        keys.add("tcth.gunner.profile.main_weapon");
        keys.add("tcth.gunner.profile.main_weapon_none");
        keys.add("tcth.gunner.profile.longest_kill");
        keys.add("tcth.gunner.profile.tier_distribution");
        keys.add("tcth.gunner.profile.top_weapons_header");
        keys.add("tcth.gunner.profile.top_weapon_line");
        keys.add("tcth.gunner.profile.medals");
        keys.add("tcth.gunner.profile.medals_none");
        return keys;
    }

    @Test
    void englishAndChineseContainAllProfileAndMedalKeys() throws IOException {
        JsonObject en = GSON.fromJson(Files.readString(EN, StandardCharsets.UTF_8), JsonObject.class);
        JsonObject zh = GSON.fromJson(Files.readString(ZH, StandardCharsets.UTF_8), JsonObject.class);
        for (String key : REQUIRED) {
            assertTrue(en.has(key), "en_us missing " + key);
            assertTrue(zh.has(key), "zh_cn missing " + key);
            assertTrue(!en.get(key).getAsString().isBlank(), "en_us blank " + key);
            assertTrue(!zh.get(key).getAsString().isBlank(), "zh_cn blank " + key);
        }
    }

    @Test
    void medalTranslationKeysMatchIdConvention() {
        for (GunnerMedal medal : GunnerMedal.values()) {
            assertEquals("tcth.gunner.medal." + medal.id(), medal.translationKey());
        }
    }

    @Test
    void thresholdsHaveSingleAuthoritativeConstants() {
        // Smoke: isSatisfied uses the public constants (not a second hard-coded 100).
        PlayerGunnerStats empty = new PlayerGunnerStats();
        assertTrue(!GunnerMedal.FIRST_BLOOD.isSatisfied(empty));
        assertEquals(1, GunnerMedal.FIRST_BLOOD_KILLS);
        assertEquals(100, GunnerMedal.CENTURION_KILLS);
        assertEquals(50.0f, GunnerMedal.LONG_SHOT_DISTANCE);
        assertEquals(25, GunnerMedal.ELITE_HUNTER_KILLS);
        assertEquals(1, GunnerMedal.BOSS_FINISHER_KILLS);
    }
}
