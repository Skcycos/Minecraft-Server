package com.tanrunn.tcth.impl.shadow;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Loads {@code data/&lt;pack&gt;/shadow_loot/&lt;entity_namespace&gt;/&lt;entity_path&gt;.json}
 * definitions (8D.1 §3 + 8D.1.1 §3).
 *
 * <p>Vanilla highest-priority override semantics (8D.1.1 §3, 8D.1.2):
 * <ul>
 *   <li>ONLY the highest-priority resource for an entity is used (the LAST
 *       entry of {@code listResourceStacks}, which Minecraft orders
 *       LOW → HIGH);</li>
 *   <li>a corrupt JSON or an invalid schema at the highest priority → that
 *       entity has NO definition (never falls back to a lower-priority
 *       file);</li>
 *   <li>before the atomic map is published, the registries are consulted:
 *       the entity type must be registered, every entry item must be
 *       registered, not AIR, and produce a non-empty ItemStack — any unknown
 *       entry rejects the WHOLE entity definition;</li>
 *   <li>the coordinator never discovers unknown items after the random draw.</li>
 * </ul>
 *
 * <p>Hard exclusions (code-level): Wither, Ender Dragon, Elder Guardian and
 * Warden are never lootable, even when a data pack misconfigures them.
 */
public final class ShadowLootLoader {

    public static final String FOLDER = "shadow_loot";

    /** Code-level hard exclusions (8D.1 §3): never lootable, even if misconfigured. */
    public static final List<ResourceLocation> HARD_EXCLUDED = List.of(
            ResourceLocation.fromNamespaceAndPath("minecraft", "wither"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "ender_dragon"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "elder_guardian"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "warden"));

    private static final ShadowLootLoader INSTANCE = new ShadowLootLoader();

    private volatile Map<ResourceLocation, ShadowLootDefinition> definitions = Map.of();

    private ShadowLootLoader() {
    }

    public static ShadowLootLoader instance() {
        return INSTANCE;
    }

    /** Shared resource parsing (used by the per-reload bound listener). */
    static Map<ResourceLocation, JsonObject> prepare(ResourceManager manager) {
        Map<ResourceLocation, JsonObject> raw = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> entry
                : manager.listResourceStacks(FOLDER, location -> location.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation key = entry.getKey();
            // 8D.1.3 §4: ONLY the tcth outer namespace is authoritative —
            // any other namespace is ignored (never races with the canonical
            // files in traversal order).
            if (!TCTHIntegration.MODID.equals(key.getNamespace())) {
                continue;
            }
            // key = "shadow_loot/<entityNs>/<entityPath>.json"
            String path = key.getPath();
            int slash = path.indexOf('/');
            if (slash <= 0 || slash >= path.length() - 1) {
                continue; // malformed location: skip
            }
            String entityNs = path.substring(slash + 1);
            int secondSlash = entityNs.indexOf('/');
            if (secondSlash <= 0) {
                continue;
            }
            ResourceLocation entityType = ResourceLocation.fromNamespaceAndPath(
                    entityNs.substring(0, secondSlash),
                    entityNs.substring(secondSlash + 1).replaceFirst("\\.json$", ""));
            if (entry.getValue().isEmpty()) {
                continue;
            }
            // Highest-priority resource only: Minecraft's resource stack is
            // ordered LOW → HIGH, so the LAST element wins.
            Resource highest = entry.getValue().get(entry.getValue().size() - 1);
            try (var input = highest.open()) {
                JsonElement element = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                if (element.isJsonObject()) {
                    raw.put(entityType, element.getAsJsonObject());
                }
            } catch (Exception e) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow loot file unreadable: {}", key);
                // corrupt JSON at the highest priority → no definition, no fallback
            }
        }
        return raw;
    }

    /**
     * Publishes a reload's parsed definitions (8D.3.1 §1). The RegistryAccess
     * comes from the bound reload listener ({@link AddReloadListenerEvent});
     * a null/unavailable registry fails closed to an EMPTY map. The low-rate
     * INFO line proves the initial reload result (at most one per reload).
     */
    static void publish(Map<ResourceLocation, JsonObject> raw, @org.jetbrains.annotations.Nullable
            RegistryAccess registryAccess) {
        if (registryAccess == null) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow loot reload skipped: registry access unavailable");
            INSTANCE.definitions = Map.of();
            return;
        }
        Map<ResourceLocation, ShadowLootDefinition> parsed = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonObject> entry : raw.entrySet()) {
            ResourceLocation entityType = entry.getKey();
            if (isHardExcluded(entityType)) {
                continue; // never load a definition for a hard-excluded entity
            }
            ShadowLootDefinition definition = ShadowLootDefinition.parse(entityType, entry.getValue());
            if (definition == null) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow loot definition rejected (invalid schema): {}", entityType);
                continue;
            }
            if (!validate(registryAccess, entityType, definition)) {
                ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                        "[TCTH] Shadow loot definition rejected (unknown entity/item): {}", entityType);
                continue; // whole definition rejected
            }
            parsed.put(entityType, definition);
        }
        INSTANCE.definitions = Map.copyOf(parsed); // atomic swap; stale entries dropped
        TCTHIntegration.LOGGER.info(
                "[TCTH] Shadow loot definitions loaded: {} entities", parsed.size());
    }

    /** Registry validation: entity registered; every item registered, not
     *  AIR and stack non-empty. Any unknown entry rejects the WHOLE file. */
    private static boolean validate(RegistryAccess registryAccess, ResourceLocation entityType,
                                    ShadowLootDefinition definition) {
        try {
            Registry<EntityType<?>> entities = registryAccess.registryOrThrow(Registries.ENTITY_TYPE);
            if (!entities.containsKey(entityType)) {
                return false; // unknown entity type (containsKey: get() falls back to a default)
            }
            Registry<Item> items = registryAccess.registryOrThrow(Registries.ITEM);
            for (ShadowLootDefinition.ShadowLootPool pool : definition.pools()) {
                for (ShadowLootDefinition.ShadowLootEntry entry : pool.entries()) {
                    if (!items.containsKey(entry.itemId())) {
                        return false; // unknown item id
                    }
                    Item item = items.get(entry.itemId());
                    if (item == null || item == net.minecraft.world.item.Items.AIR) {
                        return false; // unknown or AIR item
                    }
                    if (new ItemStack(item, entry.minCount()).isEmpty()) {
                        return false; // stack must be non-empty
                    }
                }
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Whether the entity type is code-level hard excluded (8D.1 §3). */
    public static boolean isHardExcluded(ResourceLocation entityType) {
        return HARD_EXCLUDED.contains(entityType);
    }

    /**
     * @return the loaded definition for the entity type, or {@code null} when
     *         undefined (the coordinator reports NO_CANDIDATE)
     */
    public ShadowLootDefinition get(ResourceLocation entityType) {
        return definitions.get(entityType);
    }

    public Map<ResourceLocation, ShadowLootDefinition> snapshot() {
        return definitions;
    }

    // ---- weighted selection: exactly one random call per layer (8D.1 §3) ----

    /** Layer 1: one pool by weight. */
    public static ShadowLootDefinition.ShadowLootPool selectPool(ShadowLootDefinition definition,
                                                                 RandomSource random) {
        long sum = 0L;
        for (ShadowLootDefinition.ShadowLootPool pool : definition.pools()) {
            sum += pool.weight();
        }
        long roll = random.nextInt((int) Math.min(sum, Integer.MAX_VALUE));
        for (ShadowLootDefinition.ShadowLootPool pool : definition.pools()) {
            roll -= pool.weight();
            if (roll < 0L) {
                return pool;
            }
        }
        return definition.pools().get(definition.pools().size() - 1);
    }

    /** Layer 2: one entry by weight within the pool. */
    public static ShadowLootDefinition.ShadowLootEntry selectEntry(ShadowLootDefinition.ShadowLootPool pool,
                                                                   RandomSource random) {
        long sum = 0L;
        for (ShadowLootDefinition.ShadowLootEntry entry : pool.entries()) {
            sum += entry.weight();
        }
        long roll = random.nextInt((int) Math.min(sum, Integer.MAX_VALUE));
        for (ShadowLootDefinition.ShadowLootEntry entry : pool.entries()) {
            roll -= entry.weight();
            if (roll < 0L) {
                return entry;
            }
        }
        return pool.entries().get(pool.entries().size() - 1);
    }

    /** Layer 3: one count in [min_count, max_count] — ALWAYS one random call,
     *  even when min == max (8D.1.1 §7). */
    public static int rollCount(ShadowLootDefinition.ShadowLootEntry entry, RandomSource random) {
        int span = entry.maxCount() - entry.minCount();
        return entry.minCount() + random.nextInt(span + 1);
    }
}
