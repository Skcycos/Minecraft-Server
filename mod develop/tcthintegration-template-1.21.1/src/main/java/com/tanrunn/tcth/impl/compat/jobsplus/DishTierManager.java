package com.tanrunn.tcth.impl.compat.jobsplus;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;

/**
 * Data-driven dish tier mapping.
 *
 * <p>Loads two maps from
 * {@code data/<ns>/dish_tiers/recipes/<recipe-namespace>/<recipe-path>.json}
 * and {@code data/<ns>/dish_tiers/items/<item-namespace>/<item-path>.json}.
 *
 * <p>Resolution priority (see {@link #resolve}):
 * <ol>
 *   <li>recipe id mapping (when the event carries a recipe id);</li>
 *   <li>item mapping keyed by the result item id — this covers vanilla and
 *       Kaleidoscope Cookery dishes whose recipe id is unavailable
 *       ({@code null});</li>
 *   <li>no tier when neither mapping has an entry.</li>
 * </ol>
 *
 * <p>The maps are replaced atomically via {@code Map.copyOf} after each
 * reload (no in-place mutation), so concurrent readers always see a complete
 * snapshot. A malformed entry is isolated (logged and skipped) and unknown
 * JSON fields produce a warning.
 */
public final class DishTierManager extends SimpleJsonResourceReloadListener {

    public static final String FOLDER = "dish_tiers";
    private static final String RECIPES_PREFIX = "recipes/";
    private static final String ITEMS_PREFIX = "items/";

    /** recipe id -> tier. */
    private static volatile Map<ResourceLocation, DishTierDefinition> recipeTiers = Map.of();
    /** item id -> tier. */
    private static volatile Map<ResourceLocation, DishTierDefinition> itemTiers = Map.of();

    public DishTierManager() {
        super(new Gson(), FOLDER);
    }

    /**
     * Resolves the tier for a dish event.
     *
     * @param recipeId     the event recipe id (may be {@code null})
     * @param result       the dish result stack (used for item mapping)
     * @return the resolved tier definition, or empty when neither mapping
     *         applies
     */
    public static Optional<DishTierDefinition> resolve(@Nullable ResourceLocation recipeId, ItemStack result) {
        if (recipeId != null) {
            DishTierDefinition byRecipe = recipeTiers.get(recipeId);
            if (byRecipe != null) {
                return Optional.of(byRecipe);
            }
        }
        if (result != null && !result.isEmpty()) {
            ResourceLocation itemId = result.getItem().builtInRegistryHolder().key().location();
            DishTierDefinition byItem = itemTiers.get(itemId);
            if (byItem != null) {
                return Optional.of(byItem);
            }
        }
        return Optional.empty();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager,
                         ProfilerFiller profilerFiller) {
        Map<ResourceLocation, DishTierDefinition> recipes = new HashMap<>();
        Map<ResourceLocation, DishTierDefinition> items = new HashMap<>();
        objects.forEach((id, element) -> {
            try {
                String path = id.getPath();
                if (path.startsWith(RECIPES_PREFIX)) {
                    recipes.put(recipeIdOf(id, RECIPES_PREFIX), parseDefinition(element, id));
                } else if (path.startsWith(ITEMS_PREFIX)) {
                    items.put(recipeIdOf(id, ITEMS_PREFIX), parseDefinition(element, id));
                } else {
                    TCTHIntegration.LOGGER.warn("[TCTH] Skipping dish tier entry '{}': path must start with '{}' or '{}'",
                            id, RECIPES_PREFIX, ITEMS_PREFIX);
                }
            } catch (RuntimeException e) {
                TCTHIntegration.LOGGER.warn("[TCTH] Skipping invalid dish tier entry '{}': {}", id, e.toString());
            }
        });
        // Atomic swap: readers never observe a partially updated map.
        recipeTiers = Map.copyOf(recipes);
        itemTiers = Map.copyOf(items);
    }

    private static DishTierDefinition parseDefinition(JsonElement element, ResourceLocation id) {
        JsonObject json = GsonHelper.convertToJsonObject(element, "dish tier");
        Set<String> known = Set.of("tier");
        json.keySet().stream()
                .filter(k -> !known.contains(k))
                .forEach(k -> TCTHIntegration.LOGGER.warn("[TCTH] Unknown field '{}' in dish tier entry '{}'", k, id));
        String tierName = GsonHelper.getAsString(json, "tier").toUpperCase(Locale.ROOT);
        return new DishTierDefinition(DishTier.valueOf(tierName));
    }

    /**
     * Reload-listener ids look like {@code tcth:recipes/minecraft/cooked_beef};
     * the underlying id is reconstructed as {@code minecraft:cooked_beef}.
     */
    private static ResourceLocation recipeIdOf(ResourceLocation listenerId, String prefix) {
        String rest = listenerId.getPath().substring(prefix.length());
        String[] parts = rest.split("/", 2);
        if (parts.length == 2) {
            return ResourceLocation.fromNamespaceAndPath(parts[0], parts[1]);
        }
        return ResourceLocation.fromNamespaceAndPath(listenerId.getNamespace(), parts[0]);
    }
}
