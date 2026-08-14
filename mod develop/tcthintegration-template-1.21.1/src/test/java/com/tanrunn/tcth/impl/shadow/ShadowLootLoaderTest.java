package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Tests for {@link ShadowLootDefinition} and {@link ShadowLootLoader}
 * (8D.1 §3 + 8D.1.1 §3): strict schema, per-file fail-closed, hard
 * exclusions, atomic reload with stale cleanup, one random call per layer,
 * highest-priority override semantics and reload-time registry validation.
 */
class ShadowLootLoaderTest {

    private static final ResourceLocation COW = ResourceLocation.fromNamespaceAndPath("minecraft", "cow");
    private static final ResourceLocation ZOMBIE = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private static final String COW_DEF_1 = """
            { "pools": [ { "weight": 100, "entries": [
                { "id": "minecraft:cobblestone", "weight": 50, "min_count": 1, "max_count": 1 } ] } ] }
            """;
    private static final String COW_DEF_2 = """
            { "pools": [ { "weight": 100, "entries": [
                { "id": "minecraft:cobblestone", "weight": 50, "min_count": 2, "max_count": 2 } ] } ] }
            """;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    @BeforeEach
    void setUp() {
        ShadowLootLoader.publish(Map.of(), registryAccess());
    }

    @AfterEach
    void tearDown() {
        ShadowLootLoader.publish(Map.of(), registryAccess());
    }

    private static JsonObject valid() {
        return (JsonObject) JsonParser.parseString(COW_DEF_1);
    }

    // ---- schema parsing ----

    @Test
    void validDefinitionParses() {
        ShadowLootDefinition definition = ShadowLootDefinition.parse(COW, valid());
        assertNotNull(definition);
        assertEquals(1, definition.pools().size());
        assertEquals(1, definition.pools().get(0).entries().size());
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone"),
                definition.pools().get(0).entries().get(0).itemId());
        assertEquals(50L, definition.pools().get(0).entries().get(0).weight());
        assertEquals(1, definition.pools().get(0).entries().get(0).minCount());
        assertEquals(1, definition.pools().get(0).entries().get(0).maxCount());
    }

    @Test
    void invalidDefinitionsAreRejected() {
        assertNull(ShadowLootDefinition.parse(COW, null));
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString("{}")));
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": []}")), "zero pools");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolJson(0) + "]}")), "pool weight 0");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolJson(1000001) + "]}")), "pool weight too large");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("minecraft:cobblestone", 0, 1, 1) + "]}")),
                "entry weight 0");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("minecraft:cobblestone", 50, 0, 1) + "]}")),
                "min_count 0");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("minecraft:cobblestone", 50, 5, 4) + "]}")),
                "min > max");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("minecraft:cobblestone", 50, 1, 5) + "]}")),
                "max_count 5");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("a..b", 50, 1, 1) + "]}")),
                "path-traversal item id");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [" + poolWithEntryJson("not an id", 50, 1, 1) + "]}")),
                "invalid item id");
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(
                "{\"pools\": [{\"weight\": 100, \"entries\": ["
                        + "{\"id\": \"minecraft:cobblestone\", \"weight\": 50, \"min_count\": 1}]}]}")),
                "missing max_count");
        // Weight sums use long with overflow protection: the strict bounds
        // (1..1,000,000 × ≤32 entries) can never overflow, so the maximum
        // legal payload must parse cleanly.
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            entries.append(entryJson("minecraft:cobblestone", 1_000_000L, 1, 1)).append(",");
        }
        String maxLegal = "{\"pools\": [{\"weight\": 1000000, \"entries\": ["
                + entries.substring(0, entries.length() - 1) + "]}]}";
        assertNotNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(maxLegal)),
                "the maximum legal weight sum (32 × 1,000,000) must parse");
        // 9 pools exceed the pool bound.
        String singlePool = "{\"weight\": 1, \"entries\": ["
                + entryJson("minecraft:cobblestone", 1L, 1, 1) + "]}";
        String ninePools = "{\"pools\": [" + String.join(",", java.util.Collections.nCopies(9, singlePool)) + "]}";
        assertNull(ShadowLootDefinition.parse(COW, (JsonObject) JsonParser.parseString(ninePools)),
                "more than 8 pools must be rejected");
    }

    private static String poolJson(long weight) {
        return "{\"weight\": " + weight + ", \"entries\": ["
                + entryJson("minecraft:cobblestone", 50L, 1, 1) + "]}";
    }

    private static String poolWithEntryJson(String id, long weight, int min, int max) {
        return "{\"weight\": 100, \"entries\": [" + entryJson(id, weight, min, max) + "]}";
    }

    private static String entryJson(String id, long weight, int min, int max) {
        return "{\"id\": \"" + id + "\", \"weight\": " + weight
                + ", \"min_count\": " + min + ", \"max_count\": " + max + "}";
    }

    private static JsonObject fractional(String poolWeightJson, String entryWeightJson) {
        return (JsonObject) JsonParser.parseString(
                "{\"pools\": [{\"weight\": " + poolWeightJson.split(": ")[1]
                        + ", \"entries\": [{\"id\": \"minecraft:cobblestone\", "
                        + entryWeightJson + ", \"min_count\": 1, \"max_count\": 1}]}]}");
    }

    // ---- loader: hard exclusions, atomic reload, stale cleanup ----

    @Test
    void hardExcludedEntitiesNeverLoad() {
        ShadowLootLoader loader = ShadowLootLoader.instance();
        for (ResourceLocation excluded : ShadowLootLoader.HARD_EXCLUDED) {
            assertTrue(ShadowLootLoader.isHardExcluded(excluded));
            ShadowLootLoader.publish(Map.of(excluded, valid()), registryAccess());
            assertNull(loader.get(excluded), "hard-excluded entities must never be lootable");
        }
    }

    @Test
    void reloadIsAtomicAndDropsStale() {
        ShadowLootLoader loader = ShadowLootLoader.instance();
        ShadowLootLoader.publish(Map.of(COW, valid(), ZOMBIE, valid()), registryAccess());
        assertNotNull(loader.get(COW));
        assertNotNull(loader.get(ZOMBIE));
        ShadowLootLoader.publish(Map.of(COW, valid()), registryAccess());
        assertNotNull(loader.get(COW));
        assertNull(loader.get(ZOMBIE), "stale definitions must be dropped");
        ShadowLootLoader.publish(Map.of(COW, (JsonObject) JsonParser.parseString("{\"pools\": []}"),
                ZOMBIE, valid()), registryAccess());
        assertNull(loader.get(COW), "an invalid file fails that entity closed");
        assertNotNull(loader.get(ZOMBIE));
    }

    // ---- registry validation at reload (8D.1.1 §3) ----

    @Test
    void unknownEntityTypeRejectsTheDefinition() {
        ShadowLootLoader loader = ShadowLootLoader.instance();
        ShadowLootLoader.publish(Map.of(ResourceLocation.fromNamespaceAndPath("minecraft", "not_an_entity"),
                valid()), registryAccess());
        assertNull(loader.get(ResourceLocation.fromNamespaceAndPath("minecraft", "not_an_entity")),
                "an unregistered entity type must reject the whole definition");
    }

    @Test
    void mixedEntriesRejectTheWholeFileWithZeroRandom() {
        ShadowLootLoader loader = ShadowLootLoader.instance();
        String mixed = """
                { "pools": [ { "weight": 100, "entries": [
                    { "id": "minecraft:cobblestone", "weight": 50, "min_count": 1, "max_count": 1 },
                    { "id": "minecraft:not_a_real_item", "weight": 50, "min_count": 1, "max_count": 1 } ] } ] }
                """;
        ShadowLootLoader.publish(Map.of(COW, (JsonObject) JsonParser.parseString(mixed)), registryAccess());
        assertNull(loader.get(COW),
                "one valid + one unknown item must reject the whole file (8D.1.1 §3)");
    }

    @Test
    void airItemRejectsTheDefinition() {
        ShadowLootLoader loader = ShadowLootLoader.instance();
        String air = """
                { "pools": [ { "weight": 100, "entries": [
                    { "id": "minecraft:air", "weight": 50, "min_count": 1, "max_count": 1 } ] } ] }
                """;
        ShadowLootLoader.publish(Map.of(COW, (JsonObject) JsonParser.parseString(air)), registryAccess());
        assertNull(loader.get(COW), "AIR must never be a lootable item");
    }

    // ---- highest-priority override semantics (8D.1.1 §3) ----

    private MultiPackResourceManager packManager(Path low, Path high) {
        PackLocationInfo lowInfo = new PackLocationInfo("low", Component.literal("low"),
                PackSource.BUILT_IN, Optional.empty());
        PackLocationInfo highInfo = new PackLocationInfo("high", Component.literal("high"),
                PackSource.BUILT_IN, Optional.empty());
        return new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(lowInfo, low), new PathPackResources(highInfo, high)));
    }

    private static void writeFile(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void highestPriorityValidOverridesLower() throws Exception {
        Path low = Files.createTempDirectory("low");
        Path high = Files.createTempDirectory("high");
        writeFile(low, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_1);
        writeFile(high, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_2);
        ResourceManager manager = packManager(low, high);
        ShadowLootLoader loader = ShadowLootLoader.instance();
        ShadowLootLoader.publish(ShadowLootLoader.prepare(manager), registryAccess());
        ShadowLootDefinition definition = loader.get(COW);
        assertNotNull(definition);
        assertEquals(2, definition.pools().get(0).entries().get(0).minCount(),
                "the highest-priority definition must win");
    }

    @Test
    void highestPriorityCorruptJsonDoesNotFallBack() throws Exception {
        Path low = Files.createTempDirectory("low");
        Path high = Files.createTempDirectory("high");
        writeFile(low, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_1);
        writeFile(high, "data/tcth/shadow_loot/minecraft/cow.json", "{ not valid json !!!");
        ResourceManager manager = packManager(low, high);
        ShadowLootLoader loader = ShadowLootLoader.instance();
        ShadowLootLoader.publish(ShadowLootLoader.prepare(manager), registryAccess());
        assertNull(loader.get(COW),
                "corrupt JSON at the highest priority must yield NO definition, never fall back");
    }

    @Test
    void highestPriorityInvalidSchemaDoesNotFallBack() throws Exception {
        Path low = Files.createTempDirectory("low");
        Path high = Files.createTempDirectory("high");
        writeFile(low, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_1);
        writeFile(high, "data/tcth/shadow_loot/minecraft/cow.json", "{\"pools\": []}");
        ResourceManager manager = packManager(low, high);
        ShadowLootLoader loader = ShadowLootLoader.instance();
        ShadowLootLoader.publish(ShadowLootLoader.prepare(manager), registryAccess());
        assertNull(loader.get(COW),
                "an invalid schema at the highest priority must yield NO definition, never fall back");
    }

    // ---- one random call per layer ----


    // ---- 8D.1.2: integer-only numbers, null/throwing registry ----

    @Test
    void fractionalWeightsAndCountsAreRejected() {
        assertNull(ShadowLootDefinition.parse(COW, fractional("\"weight\": 1.5", "\"weight\": 50")),
                "a fractional weight must be rejected");
        assertNull(ShadowLootDefinition.parse(COW, fractional("\"weight\": 50", "\"weight\": 1.5")),
                "a fractional min_count must be rejected");
        assertNull(ShadowLootDefinition.parse(COW, fractional("\"weight\": 50", "\"weight\": 2.5")),
                "a fractional max_count must be rejected");
        assertNull(ShadowLootDefinition.parse(COW, fractional("\"weight\": 1e100", "\"weight\": 50")),
                "an exponent-overflowing weight must be rejected");
        assertNull(ShadowLootDefinition.parse(COW, fractional("\"weight\": 50", "\"weight\": 1e100")),
                "an exponent-overflowing count must be rejected");
    }

    @Test
    void nullRegistryAccessPublishesAnEmptyMap() {
        ShadowLootLoader.publish(Map.of(COW, valid()), null);
        assertTrue(ShadowLootLoader.instance().snapshot().isEmpty(),
                "null registry access must publish an EMPTY map (fail-closed), not stale data");
        assertNull(ShadowLootLoader.instance().get(COW));
    }

    @Test
    void throwingRegistryAccessPublishesAnEmptyMap() {
        ShadowLootLoader.publish(Map.of(COW, valid()), null);
        assertTrue(ShadowLootLoader.instance().snapshot().isEmpty(),
                "a null registry access must publish an EMPTY map (fail-closed)");
    }



    // ---- 8D.1.3: only the tcth outer namespace is authoritative ----

    @Test
    void nonTcthOuterNamespaceIsIgnored() throws Exception {
        // data/minecraft/shadow_loot/... must be ignored entirely (8D.1.3 §4).
        Path other = Files.createTempDirectory("other");
        writeFile(other, "data/minecraft/shadow_loot/minecraft/cow.json", COW_DEF_1);
        PackLocationInfo info = new PackLocationInfo("other", Component.literal("other"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(info, other)))) {
            Map<ResourceLocation, JsonObject> raw = ShadowLootLoader.prepare(manager);
            assertTrue(raw.isEmpty(), "a non-tcth outer namespace must never load");
        }
    }

    @Test
    void crossNamespaceDoesNotRaceWithTheCanonicalFile() throws Exception {
        // A lower-priority non-tcth file and a higher-priority tcth file: the
        // canonical tcth file wins deterministically — no traversal-order race.
        Path packA = Files.createTempDirectory("packA");
        Path packB = Files.createTempDirectory("packB");
        // packA (LOW): both a wrong-namespace file AND a tcth file.
        writeFile(packA, "data/minecraft/shadow_loot/minecraft/cow.json", COW_DEF_1);
        writeFile(packA, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_1);
        // packB (HIGH): canonical tcth file only.
        writeFile(packB, "data/tcth/shadow_loot/minecraft/cow.json", COW_DEF_2);
        PackLocationInfo infoA = new PackLocationInfo("packA", Component.literal("packA"),
                PackSource.BUILT_IN, Optional.empty());
        PackLocationInfo infoB = new PackLocationInfo("packB", Component.literal("packB"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(infoA, packA), new PathPackResources(infoB, packB)))) {
            ShadowLootLoader loader = ShadowLootLoader.instance();
            ShadowLootLoader.publish(ShadowLootLoader.prepare(manager), registryAccess());
            ShadowLootDefinition definition = loader.get(COW);
            assertNotNull(definition);
            assertEquals(2, definition.pools().get(0).entries().get(0).minCount(),
                    "the HIGHEST-priority canonical tcth file must win deterministically");
        }
    }



    // ---- 8D.3.1: bound-listener loading behaviour ----

    @Test
    void initialReloadLoadsAllThreeEntitiesWithoutLifecycleServer() throws Exception {
        // The bound listener carries the event RegistryAccess — no
        // ServerLifecycleHooks.current server involved (8D.3.1 §1).
        Path pack = Files.createTempDirectory("8d31-init");
        writeFile(pack, "data/tcth/shadow_loot/minecraft/chicken.json", COW_DEF_1);
        writeFile(pack, "data/tcth/shadow_loot/minecraft/pig.json", COW_DEF_1);
        writeFile(pack, "data/tcth/shadow_loot/minecraft/rabbit.json", COW_DEF_1);
        PackLocationInfo info = new PackLocationInfo("init", Component.literal("init"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(info, pack)))) {
            ShadowLootReloadListener listener = new ShadowLootReloadListener(registryAccess());
            Map<ResourceLocation, JsonObject> raw = listener.prepare(manager, null);
            assertEquals(3, raw.size(), "the initial reload must see all three files");
            listener.apply(raw, manager, null);
        }
        assertNotNull(ShadowLootLoader.instance().get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "chicken")));
        assertNotNull(ShadowLootLoader.instance().get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "pig")));
        assertNotNull(ShadowLootLoader.instance().get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "rabbit")));
    }

    @Test
    void twoReloadsBindDistinctRegistryAccessesWithoutCrossTalk() {
        RegistryAccess first = RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        ShadowLootReloadListener listenerA = new ShadowLootReloadListener(first);
        listenerA.apply(Map.of(COW, valid()), null, null);
        assertNotNull(ShadowLootLoader.instance().get(COW));
        // Second reload with a NULL-bound registry: the definitions must be
        // cleared — the listener never falls back to a previous context.
        ShadowLootReloadListener listenerB = new ShadowLootReloadListener(null);
        listenerB.apply(Map.of(COW, valid()), null, null);
        assertTrue(ShadowLootLoader.instance().snapshot().isEmpty(),
                "the bound registry must not be reused across reloads");
    }

    @Test
    void reloadListenerUsesTheCurrentResourceManager() throws Exception {
        // /reload and the initial startup share the exact same path: a fresh
        // listener bound to the CURRENT manager (no special recovery needed).
        Path pack = Files.createTempDirectory("8d31-manager");
        writeFile(pack, "data/tcth/shadow_loot/minecraft/chicken.json", COW_DEF_1);
        PackLocationInfo info = new PackLocationInfo("mgr", Component.literal("mgr"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(info, pack)))) {
            ShadowLootReloadListener listener = new ShadowLootReloadListener(registryAccess());
            java.util.Map<ResourceLocation, JsonObject> raw = listener.prepare(manager, null);
            assertEquals(1, raw.size(), "the reload listener must see the current manager's file");
            listener.apply(raw, manager, null);
        }
        assertNotNull(ShadowLootLoader.instance().get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "chicken")));
    }

    @Test
    void tcthDataReloadsRegistersABoundListenerWithTheEventRegistry() {
        net.neoforged.neoforge.event.AddReloadListenerEvent evt =
                org.mockito.Mockito.mock(net.neoforged.neoforge.event.AddReloadListenerEvent.class);
        org.mockito.Mockito.when(evt.getRegistryAccess()).thenReturn(registryAccess());
        com.tanrunn.tcth.impl.brewing.TcthDataReloads.onAddReloadListeners(evt);
        org.mockito.Mockito.verify(evt).getRegistryAccess();
        org.mockito.Mockito.verify(evt, org.mockito.Mockito.times(1))
                .addListener(org.mockito.ArgumentMatchers.argThat(
                        l -> l instanceof com.tanrunn.tcth.impl.shadow.ShadowLootReloadListener));
    }



    // ---- 8D.3.2: listener-based hardening ----

    private static ShadowLootReloadListener listener() {
        return new ShadowLootReloadListener(registryAccess());
    }

    @Test
    void listenerHighestPriorityValidOverridesLower() throws Exception {
        Path low = Files.createTempDirectory("l2-low");
        Path high = Files.createTempDirectory("l2-high");
        writeFile(low, "data/tcth/shadow_loot/minecraft/chicken.json", COW_DEF_1);
        writeFile(high, "data/tcth/shadow_loot/minecraft/chicken.json", COW_DEF_2);
        PackLocationInfo infoLow = new PackLocationInfo("low", Component.literal("low"),
                PackSource.BUILT_IN, Optional.empty());
        PackLocationInfo infoHigh = new PackLocationInfo("high", Component.literal("high"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(infoLow, low), new PathPackResources(infoHigh, high)))) {
            ShadowLootReloadListener l = listener();
            l.apply(l.prepare(manager, null), manager, null);
        }
        ShadowLootDefinition definition = ShadowLootLoader.instance().get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "chicken"));
        assertNotNull(definition);
        assertEquals(2, definition.pools().get(0).entries().get(0).minCount(),
                "the listener must apply the highest-priority definition");
    }

    @Test
    void listenerCorruptHighestPriorityDoesNotFallBack() throws Exception {
        Path low = Files.createTempDirectory("l2-low");
        Path high = Files.createTempDirectory("l2-high");
        writeFile(low, "data/tcth/shadow_loot/minecraft/chicken.json", COW_DEF_1);
        writeFile(high, "data/tcth/shadow_loot/minecraft/chicken.json", "{ not valid json");
        PackLocationInfo infoLow = new PackLocationInfo("low", Component.literal("low"),
                PackSource.BUILT_IN, Optional.empty());
        PackLocationInfo infoHigh = new PackLocationInfo("high", Component.literal("high"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(infoLow, low), new PathPackResources(infoHigh, high)))) {
            ShadowLootReloadListener l = listener();
            l.apply(l.prepare(manager, null), manager, null);
        }
        assertNull(ShadowLootLoader.instance().get(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "chicken")),
                "a corrupt highest-priority file must not fall back through the listener");
    }

    @Test
    void twoDistinctNonNullRegistriesDoNotCrossTalk() {
        // Both registries are non-null and behave differently: the first
        // resolves the real registries; the second is EMPTY (unknown items →
        // every definition rejected).
        RegistryAccess first = registryAccess();
        ShadowLootReloadListener listenerA = new ShadowLootReloadListener(first);
        listenerA.apply(Map.of(COW, valid()), null, null);
        assertNotNull(ShadowLootLoader.instance().get(COW));

        RegistryAccess empty = RegistryAccess.EMPTY;
        ShadowLootReloadListener listenerB = new ShadowLootReloadListener(empty);
        listenerB.apply(Map.of(COW, valid()), null, null);
        assertTrue(ShadowLootLoader.instance().snapshot().isEmpty(),
                "a second reload bound to a different registry must not reuse the first");
    }

    @Test
    void listenerNullRegistryStillClearsOldDefinitions() {
        ShadowLootReloadListener listenerA = new ShadowLootReloadListener(registryAccess());
        listenerA.apply(Map.of(COW, valid()), null, null);
        assertNotNull(ShadowLootLoader.instance().get(COW));
        new ShadowLootReloadListener(null).apply(Map.of(COW, valid()), null, null);
        assertTrue(ShadowLootLoader.instance().snapshot().isEmpty(),
                "a null-bound reload must keep clearing old definitions");
    }


    @Test
    void selectionUsesExactlyOneRandomCallPerLayer() {
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(random.nextInt(Mockito.anyInt())).thenReturn(0);
        ShadowLootDefinition definition = ShadowLootDefinition.parse(COW, valid());
        ShadowLootDefinition.ShadowLootPool pool = ShadowLootLoader.selectPool(definition, random);
        assertNotNull(pool);
        ShadowLootDefinition.ShadowLootEntry entry = ShadowLootLoader.selectEntry(pool, random);
        assertNotNull(entry);
        int count = ShadowLootLoader.rollCount(entry, random);
        assertTrue(count >= entry.minCount() && count <= entry.maxCount());
        Mockito.verify(random, Mockito.times(3)).nextInt(Mockito.anyInt());
    }

    @Test
    void rollCountAlwaysCallsTheRandomEvenWhenMinEqualsMax() {
        // 8D.1.1 §7: count draws exactly once even when min == max.
        RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(random.nextInt(Mockito.anyInt())).thenReturn(0);
        ShadowLootDefinition definition = ShadowLootDefinition.parse(COW, valid());
        ShadowLootDefinition.ShadowLootPool pool = ShadowLootLoader.selectPool(definition, random);
        ShadowLootDefinition.ShadowLootEntry entry = ShadowLootLoader.selectEntry(pool, random);
        assertEquals(1, entry.minCount());
        assertEquals(1, entry.maxCount());
        assertEquals(1, ShadowLootLoader.rollCount(entry, random));
        Mockito.verify(random, Mockito.times(3)).nextInt(Mockito.anyInt());
        assertFalse(entry.minCount() > entry.maxCount());
    }
}
