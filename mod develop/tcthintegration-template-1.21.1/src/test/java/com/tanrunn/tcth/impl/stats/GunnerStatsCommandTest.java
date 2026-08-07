package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Unit tests for {@link GunnerStatsCommand} (phase 5A / 5C.1).
 *
 * <p>Profile output is asserted by translation keys, not hardcoded zh/en text.
 */
class GunnerStatsCommandTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void formatEmptyStatsReturnsComponent() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("枪客档案"));
        assertTrue(text.contains("总枪械击杀: 0"));
    }

    @Test
    void formatPopulatedStatsReturnsComponent() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:umax_pistol", "minecraft:skeleton", GunTargetTier.ELITE, 25.5f, 2000L);
        stats.record("scguns:defender_pistol", "minecraft:creeper", GunTargetTier.HEAVY, 50.0f, 3000L);

        Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("总枪械击杀: 3"));
        assertTrue(text.contains("COMMON=1"));
        assertTrue(text.contains("ELITE=1"));
        assertTrue(text.contains("HEAVY=1"));
        assertTrue(text.contains("最大击杀距离: 50.0"));
        assertTrue(text.contains("minecraft:creeper"));
    }

    @Test
    void formatShowsMostUsedWeapon() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 15.0f, 2000L);
        stats.record("scguns:umax_pistol", "minecraft:skeleton", GunTargetTier.ELITE, 20.0f, 3000L);

        Component component = GunnerStatsCommand.format(stats);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("scguns:defender_pistol"));
    }

    @Test
    void formatProfileUsesTranslationKeysNotHardcodedChinese() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 10.0f, 1000L);
        stats.record("scguns:defender_pistol", "minecraft:zombie", GunTargetTier.COMMON, 64.3f, 2000L);
        stats.record("scguns:combat_shotgun", "minecraft:skeleton", GunTargetTier.ELITE, 20.0f, 3000L);
        GunnerMedalEvaluator.unlockNewlyMet(stats, 1L);

        Component profile = GunnerStatsCommand.formatProfile("Tanrunn", stats);
        Set<String> keys = collectTranslationKeys(profile);
        assertTrue(keys.contains("tcth.gunner.profile.title"));
        assertTrue(keys.contains("tcth.gunner.profile.player"));
        assertTrue(keys.contains("tcth.gunner.profile.confirmed_kills"));
        assertTrue(keys.contains("tcth.gunner.profile.unique_weapons"));
        assertTrue(keys.contains("tcth.gunner.profile.main_weapon"));
        assertTrue(keys.contains("tcth.gunner.profile.longest_kill"));
        assertTrue(keys.contains("tcth.gunner.profile.tier_distribution"));
        assertTrue(keys.contains("tcth.gunner.profile.top_weapons_header"));
        assertTrue(keys.contains("tcth.gunner.profile.top_weapon_line"));
        assertTrue(keys.contains("tcth.gunner.profile.medals"));
        assertTrue(keys.contains("tcth.gunner.medal.first_blood"));
        assertTrue(keys.contains("tcth.gunner.medal.long_shot"));
        // Must not bake Chinese labels into the component tree as literals only.
        String flat = profile.getString();
        assertFalse(flat.contains("枪客战地档案"),
                "profile must not embed zh literals; client resolves translation keys");
    }

    @Test
    void formatProfileEmptyUsesNoneKeys() {
        PlayerGunnerStats stats = new PlayerGunnerStats();
        Component profile = GunnerStatsCommand.formatProfile("Nobody", stats);
        Set<String> keys = collectTranslationKeys(profile);
        assertTrue(keys.contains("tcth.gunner.profile.main_weapon_none"));
        assertTrue(keys.contains("tcth.gunner.profile.medals_none"));
        assertFalse(keys.contains("tcth.gunner.profile.top_weapons_header"));
    }

    static Set<String> collectTranslationKeys(Component component) {
        Set<String> keys = new HashSet<>();
        walk(component, keys);
        return keys;
    }

    private static void walk(Component component, Set<String> keys) {
        if (component == null) {
            return;
        }
        if (component.getContents() instanceof TranslatableContents tc) {
            keys.add(tc.getKey());
            for (Object arg : tc.getArgs()) {
                if (arg instanceof Component c) {
                    walk(c, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            walk(sibling, keys);
        }
    }
}
