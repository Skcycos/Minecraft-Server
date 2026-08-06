package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunnerRewardsEnabledCondition;

/**
 * Phase 5A.1 preset tests: reads the actual {@code docs/presets/tcth-gunner/}
 * files (the same ones deployed to the server) and verifies the gunner reward
 * contract: correct holder/action/rewards, strict tier exclusivity, the
 * {@code tcth:gunner_rewards_enabled} + {@code tcth:automated=false}
 * conditions on every reward, no GD656/currency/foreign-job dependencies, and
 * no preset leakage into the main JAR.
 */
class GunnerPresetTest {

    private static final Path PRESET = Path.of("docs/presets/tcth-gunner");

    private static final Map<String, int[]> TIER_XP = new HashMap<>();
    static {
        TIER_XP.put("COMMON", new int[]{1, 2});
        TIER_XP.put("ELITE", new int[]{3, 5});
        TIER_XP.put("HEAVY", new int[]{6, 10});
        TIER_XP.put("BOSS", new int[]{12, 20});
    }

    @AfterEach
    void tearDown() {
        GunnerRewardsEnabledCondition.resetSuppliersForTesting();
        GunnerRewardsEnabledCondition.resetThrottleForTesting();
    }

    private static JsonObject readJson(Path p) {
        try {
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + p, e);
        }
    }

    private static String tierOf(String fileName) {
        return fileName.replace("gun_kill_", "").replace(".json", "").toUpperCase();
    }

    @Test
    void packMcmetaIsValid() {
        JsonObject pack = readJson(PRESET.resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertNotNull(pack);
        assertEquals(48, pack.get("pack_format").getAsInt(), "pack_format 48 = MC 1.21.1");
        assertTrue(pack.has("description"));
    }

    @Test
    void gunnerJobJsonIsValid() {
        JsonObject job = readJson(PRESET.resolve("data/tcth/jobsplus/jobs/gunner.json"));
        assertFalse(job.get("is_default").getAsBoolean(), "is_default must be false");
        assertEquals(100, job.get("max_level").getAsInt());
        assertEquals("scguns:defender_pistol", job.getAsJsonObject("icon").get("id").getAsString());
    }

    @Test
    void fourRewardFilesExistAndMatchContract() throws IOException {
        Set<String> tiersSeen = new HashSet<>();
        try (Stream<Path> walk = Files.walk(PRESET.resolve("data/tcth/arc/gunner"))) {
            List<Path> files = walk.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            assertEquals(4, files.size(), "exactly four base rewards (COMMON/ELITE/HEAVY/BOSS)");
            for (Path f : files) {
                String tier = tierOf(f.getFileName().toString());
                assertTrue(TIER_XP.containsKey(tier), "unknown tier file " + f);
                assertTrue(tiersSeen.add(tier), "duplicate tier file " + f);

                JsonObject action = readJson(f);
                assertEquals("tcth:gunner", action.getAsJsonObject("holder").get("id").getAsString());
                assertEquals("jobsplus:job", action.getAsJsonObject("holder").get("type").getAsString());
                assertEquals("tcth:on_gun_kill", action.get("type").getAsString());

                // Rewards: one jobsplus:job_exp with the fixed XP range.
                JsonArray rewards = action.getAsJsonArray("rewards");
                assertEquals(1, rewards.size());
                JsonObject reward = rewards.get(0).getAsJsonObject();
                assertEquals("jobsplus:job_exp", reward.get("type").getAsString());
                int[] xp = TIER_XP.get(tier);
                assertEquals(xp[0], reward.get("min").getAsInt(), tier + " min XP");
                assertEquals(xp[1], reward.get("max").getAsInt(), tier + " max XP");

                // Conditions: gunner_rewards_enabled + tier + automated=false.
                JsonArray conditions = action.getAsJsonArray("conditions");
                assertTrue(hasCondition(conditions, "tcth:gunner_rewards_enabled"),
                        tier + " must gate on tcth:gunner_rewards_enabled");
                JsonObject tierCond = conditionWith(conditions, "tcth:gun_target_tier");
                assertNotNull(tierCond, tier + " must gate on tcth:gun_target_tier");
                assertEquals(tier, tierCond.get("tier").getAsString(), tier + " tier condition");
                JsonObject autoCond = conditionWith(conditions, "tcth:automated");
                assertNotNull(autoCond, tier + " must gate on tcth:automated");
                assertFalse(autoCond.get("value").getAsBoolean(), "automated must be false");
            }
        }
        assertEquals(4, tiersSeen.size());
    }

    @Test
    void tierTagsAreStrictlyMutuallyExclusive() throws IOException {
        // Each entity type may appear in at most ONE tier tag (elite/heavy/
        // common/boss). The excluded tag may overlap (it has priority).
        List<String> tierTags = List.of("elite", "heavy", "common", "boss");
        Map<String, Set<String>> tierEntities = new HashMap<>();
        for (String tier : tierTags) {
            tierEntities.put(tier, readEntities(PRESET.resolve(
                    "data/tcth/tags/entity_type/gunner_targets/" + tier + ".json")));
        }
        for (int i = 0; i < tierTags.size(); i++) {
            for (int j = i + 1; j < tierTags.size(); j++) {
                String a = tierTags.get(i);
                String b = tierTags.get(j);
                Set<String> overlap = new HashSet<>(tierEntities.get(a));
                overlap.retainAll(tierEntities.get(b));
                assertTrue(overlap.isEmpty(),
                        "tiers " + a + " and " + b + " overlap: " + overlap);
            }
        }
        // Every tier must be non-empty.
        tierEntities.forEach((tier, entities) -> assertFalse(entities.isEmpty(), tier + " tag is empty"));
    }

    @Test
    void excludedTagExistsAndHasPriorityEntities() throws IOException {
        Set<String> excluded = readEntities(PRESET.resolve(
                "data/tcth/tags/entity_type/gunner_targets/excluded.json"));
        assertTrue(excluded.contains("minecraft:villager"));
        assertTrue(excluded.contains("minecraft:player"));
        assertTrue(excluded.contains("minecraft:iron_golem"));
        assertFalse(excluded.contains("minecraft:ravager"), "ravager must not be excluded (it is ELITE)");
    }

    @Test
    void presetHasNoForeignDependencies() throws IOException {
        try (Stream<Path> walk = Files.walk(PRESET)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                assertFalse(text.toLowerCase().contains("gd656"), "no GD656 references: " + p);
                assertFalse(text.toLowerCase().contains("bounty"), "no bounty references: " + p);
                assertFalse(text.toLowerCase().contains("\"coin"), "no currency reward: " + p);
                assertFalse(text.contains("jobsplus:job_exp\"")
                                && !p.toString().endsWith("gunner.json")
                                && !p.toString().contains("arc/gunner"),
                        "reward files must only belong to tcth:gunner");
            }
        }
    }

    @Test
    void presetDoesNotLeakIntoMainJarResources() throws IOException {
        Path mainData = Path.of("src/main/resources/data/tcth");
        assertTrue(!Files.exists(mainData.resolve("jobsplus")) || !Files.exists(mainData.resolve("jobsplus/jobs/gunner.json")),
                "the preset must not be bundled into the main JAR");
        assertTrue(!Files.exists(mainData.resolve("arc/gunner")),
                "the preset must not be bundled into the main JAR");
        assertTrue(!Files.exists(mainData.resolve("tags/entity_type/gunner_targets")),
                "the preset must not be bundled into the main JAR");
    }

    // ---- helpers ----

    private static boolean hasCondition(JsonArray conditions, String type) {
        return conditionWith(conditions, type) != null;
    }

    private static JsonObject conditionWith(JsonArray conditions, String type) {
        for (JsonElement e : conditions) {
            JsonObject c = e.getAsJsonObject();
            if (type.equals(c.get("type").getAsString())) {
                return c;
            }
        }
        return null;
    }

    private static Set<String> readEntities(Path tagFile) throws IOException {
        JsonObject tag = readJson(tagFile);
        Set<String> entities = new HashSet<>();
        for (JsonElement e : tag.getAsJsonArray("values")) {
            if (e.isJsonObject()) {
                entities.add(e.getAsJsonObject().get("id").getAsString());
            } else {
                entities.add(e.getAsString());
            }
        }
        return entities;
    }

    // ===== GunnerRewardsEnabledCondition switch combination =====

    @Test
    void rewardsConditionRequiresAllThreeSwitches() {
        GunnerRewardsEnabledCondition.resetSuppliersForTesting();
        GunnerRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.rewardsEnabledSupplier = () -> true;
        assertTrue(new GunnerRewardsEnabledCondition(false).isMet(null));

        GunnerRewardsEnabledCondition.frameworkEnabledSupplier = () -> false;
        assertFalse(new GunnerRewardsEnabledCondition(false).isMet(null));

        GunnerRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.integrationEnabledSupplier = () -> false;
        assertFalse(new GunnerRewardsEnabledCondition(false).isMet(null));

        GunnerRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.rewardsEnabledSupplier = () -> false;
        assertFalse(new GunnerRewardsEnabledCondition(false).isMet(null));
    }

    @Test
    void rewardsConditionFailsClosedOnConfigError() {
        GunnerRewardsEnabledCondition.resetSuppliersForTesting();
        GunnerRewardsEnabledCondition.resetThrottleForTesting();
        GunnerRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        GunnerRewardsEnabledCondition.rewardsEnabledSupplier = () -> {
            throw new RuntimeException("broken config");
        };
        // Repeated calls must not throw and must stay closed (throttled WARN).
        assertFalse(new GunnerRewardsEnabledCondition(false).isMet(null));
        assertFalse(new GunnerRewardsEnabledCondition(false).isMet(null));
        // inverted must NOT flip a config failure into a pass: fail-closed is
        // unconditional, otherwise a broken config would grant rewards.
        assertFalse(new GunnerRewardsEnabledCondition(true).isMet(null),
                "a config read failure must never match, even when inverted");
    }

    @Test
    void rewardsConditionInvertedFlipsResult() {
        GunnerRewardsEnabledCondition.resetSuppliersForTesting();
        GunnerRewardsEnabledCondition.frameworkEnabledSupplier = () -> false;
        GunnerRewardsEnabledCondition.integrationEnabledSupplier = () -> false;
        GunnerRewardsEnabledCondition.rewardsEnabledSupplier = () -> false;
        assertTrue(new GunnerRewardsEnabledCondition(true).isMet(null),
                "inverted condition matches when the switches are off");
    }

    // ===== GunKillDistanceCondition finite hardening =====

    @Test
    void distanceConditionRejectsNonFiniteBounds() throws Exception {
        Class<?> c = com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunKillDistanceCondition.class;
        for (float[] bad : new float[][]{{Float.NaN, 100f}, {0f, Float.POSITIVE_INFINITY},
                {Float.NEGATIVE_INFINITY, 100f}, {-1f, 100f}, {100f, 10f}}) {
            assertThrows(IllegalArgumentException.class, () -> {
                try {
                    c.getConstructor(boolean.class, float.class, float.class)
                            .newInstance(false, bad[0], bad[1]);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }, "bounds [" + bad[0] + "," + bad[1] + "] must be rejected");
        }
    }

    @Test
    void distanceConditionNonFiniteEventDistanceNeverMatches() throws Exception {
        com.daqem.arc.api.action.data.ActionData data = org.mockito.Mockito.mock(
                com.daqem.arc.api.action.data.ActionData.class);
        var dataType = TcthArcRegistrar.GUN_KILL_DISTANCE;
        Class<?> c = com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunKillDistanceCondition.class;
        Object cond = c.getConstructor(boolean.class, float.class, float.class).newInstance(false, 0f, 100f);
        org.mockito.Mockito.when(data.getData(dataType)).thenReturn(Float.NaN);
        assertFalse((boolean) c.getMethod("isMet", com.daqem.arc.api.action.data.ActionData.class)
                .invoke(cond, data));
        org.mockito.Mockito.when(data.getData(dataType)).thenReturn(Float.POSITIVE_INFINITY);
        assertFalse((boolean) c.getMethod("isMet", com.daqem.arc.api.action.data.ActionData.class)
                .invoke(cond, data));
        org.mockito.Mockito.when(data.getData(dataType)).thenReturn(null);
        assertFalse((boolean) c.getMethod("isMet", com.daqem.arc.api.action.data.ActionData.class)
                .invoke(cond, data));
    }

    @Test
    void gunKillEventRejectsNonFiniteDistance() {
        Class<?> c = com.tanrunn.tcth.api.guncombat.GunKillEvent.class;
        Object[] base = new Object[]{
                java.util.UUID.randomUUID(),
                org.mockito.Mockito.mock(net.minecraft.server.level.ServerPlayer.class),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol"),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "zombie"),
                java.util.UUID.randomUUID(),
                com.tanrunn.tcth.api.guncombat.GunTargetTier.COMMON,
                10.0f, false,
                org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class),
                net.minecraft.core.BlockPos.ZERO
        };
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0f}) {
            Object[] args = base.clone();
            args[7] = bad;
            assertThrows(IllegalArgumentException.class, () -> {
                try {
                    c.getConstructor(
                            java.util.UUID.class,
                            net.minecraft.server.level.ServerPlayer.class,
                            net.minecraft.resources.ResourceLocation.class,
                            net.minecraft.world.item.ItemStack.class,
                            net.minecraft.resources.ResourceLocation.class,
                            java.util.UUID.class,
                            com.tanrunn.tcth.api.guncombat.GunTargetTier.class,
                            float.class, boolean.class,
                            net.minecraft.server.level.ServerLevel.class,
                            net.minecraft.core.BlockPos.class).newInstance(args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }, "distance " + bad + " must be rejected");
        }
    }
}
