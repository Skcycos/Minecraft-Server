package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.action.data.type.IActionDataType;
import com.daqem.arc.api.action.type.ActionType;
import com.daqem.arc.api.condition.type.ConditionType;
import com.daqem.arc.api.reward.type.IRewardType;
import com.daqem.arc.api.reward.type.RewardType;
import com.daqem.arc.registry.ArcRegistry;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.AutomatedCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.ChefAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.CookingDeviceCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishQualityCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishTierCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FireDamageCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FireResistanceEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.KnifeDurabilityEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.TastingCooldownCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.TastingEffectsEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.reward.TastingEffectsReward;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/**
 * Registration of TCTH's custom Arc action type, action data types and
 * condition types.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@code tcth:on_dish_cooked} — the custom {@link ActionType};</li>
 *   <li>action data types carrying stable string/number representations of the
 *       dish: {@code result_item_id}, {@code count}, {@code recipe_id},
 *       {@code device}, {@code quality}, {@code tier}, {@code automated};</li>
 *   <li>condition types: {@code tcth:dish_tier}, {@code tcth:dish_quality},
 *       {@code tcth:cooking_device}, {@code tcth:automated}.</li>
 * </ul>
 *
 * <p>Enum values are deliberately exposed as their {@code name()} strings (not
 * as TCTH enum objects) so the public side never hands third-party APIs
 * non-stable object references.
 *
 * <p>After registration, {@link #verifyRegistrations()} explicitly checks the
 * ids against Arc's registries (action types, condition types) and logs a
 * DEBUG line per id — "no exception" alone is not treated as proof of
 * registration.
 *
 * <p>This class only lives in the {@code jobsplus} compat module and is loaded
 * when Jobs+ (hence Arc) is installed.
 */
public final class TcthArcRegistrar {

    // ---- action type ----
    public static final ActionType<DishCookedAction> DISH_COOKED =
            ActionType.register(id("on_dish_cooked"), new DishCookedAction.Serializer());

    // ---- action data types (no registry — validated by id in verify) ----
    public static final IActionDataType<String> RESULT_ITEM_ID = ActionDataType.register(id("result_item_id"));
    public static final IActionDataType<Integer> COUNT = ActionDataType.register(id("count"));
    public static final IActionDataType<String> RECIPE_ID = ActionDataType.register(id("recipe_id"));
    public static final IActionDataType<String> DEVICE = ActionDataType.register(id("device"));
    public static final IActionDataType<String> QUALITY = ActionDataType.register(id("quality"));
    public static final IActionDataType<String> TIER = ActionDataType.register(id("tier"));
    public static final IActionDataType<Boolean> AUTOMATED = ActionDataType.register(id("automated"));

    // ---- condition types ----
    public static final ConditionType<DishTierCondition> DISH_TIER =
            ConditionType.register(id("dish_tier"), new DishTierCondition.Serializer());
    public static final ConditionType<DishQualityCondition> DISH_QUALITY =
            ConditionType.register(id("dish_quality"), new DishQualityCondition.Serializer());
    public static final ConditionType<CookingDeviceCondition> COOKING_DEVICE =
            ConditionType.register(id("cooking_device"), new CookingDeviceCondition.Serializer());
    public static final ConditionType<AutomatedCondition> AUTOMATED_CONDITION =
            ConditionType.register(id("automated"), new AutomatedCondition.Serializer());

    // ---- phase 3D: chef ability tree conditions ----
    public static final ConditionType<ChefAbilitiesEnabledCondition> CHEF_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("chef_abilities_enabled"), new ChefAbilitiesEnabledCondition.Serializer());
    public static final ConditionType<TastingEffectsEnabledCondition> TASTING_EFFECTS_ENABLED_CONDITION =
            ConditionType.register(id("tasting_effects_enabled"), new TastingEffectsEnabledCondition.Serializer());
    public static final ConditionType<FireResistanceEnabledCondition> FIRE_RESISTANCE_ENABLED_CONDITION =
            ConditionType.register(id("fire_resistance_enabled"), new FireResistanceEnabledCondition.Serializer());
    public static final ConditionType<KnifeDurabilityEnabledCondition> KNIFE_DURABILITY_ENABLED_CONDITION =
            ConditionType.register(id("knife_durability_enabled"), new KnifeDurabilityEnabledCondition.Serializer());
    public static final ConditionType<FireDamageCondition> FIRE_DAMAGE_CONDITION =
            ConditionType.register(id("fire_damage"), new FireDamageCondition.Serializer());
    public static final ConditionType<TastingCooldownCondition> TASTING_COOLDOWN_CONDITION =
            ConditionType.register(id("tasting_cooldown"), new TastingCooldownCondition.Serializer());

    // ---- phase 3D: chef ability tree rewards ----
    public static final IRewardType<TastingEffectsReward> TASTING_EFFECTS =
            RewardType.register(id("tasting_effects"), new TastingEffectsReward.Serializer());

    private TcthArcRegistrar() {
    }

    /**
     * Verifies every registered id against Arc's registries (or, for data
     * types without a registry, against their own location) and logs one DEBUG
     * line per entry. Must be called after the registrar class is loaded.
     */
    public static void verifyRegistrations() {
        checkInRegistry(ArcRegistry.ACTION, id("on_dish_cooked"), "action type");
        checkInRegistry(ArcRegistry.CONDITION, id("dish_tier"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("dish_quality"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("cooking_device"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("automated"), "condition type");

        checkDataType(RESULT_ITEM_ID, "result_item_id");
        checkDataType(COUNT, "count");
        checkDataType(RECIPE_ID, "recipe_id");
        checkDataType(DEVICE, "device");
        checkDataType(QUALITY, "quality");
        checkDataType(TIER, "tier");
        checkDataType(AUTOMATED, "automated");

        // Phase 3D: chef ability tree condition types.
        checkInRegistry(ArcRegistry.CONDITION, id("chef_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("tasting_effects_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("fire_resistance_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("knife_durability_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("fire_damage"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("tasting_cooldown"), "condition type");

        // Phase 3D: chef ability tree reward type.
        checkInRegistry(ArcRegistry.REWARD, id("tasting_effects"), "reward type");
    }

    private static void checkInRegistry(Registry<?> registry, ResourceLocation registryId, String kind) {
        boolean present = registry.get(registryId) != null;
        TCTHIntegration.LOGGER.debug("[TCTH] Arc {} tcth:{} {} in ArcRegistry",
                kind, registryId.getPath(), present ? "present" : "MISSING");
        if (!present) {
            TCTHIntegration.LOGGER.warn("[TCTH] Arc {} tcth:{} NOT FOUND in ArcRegistry", kind, registryId.getPath());
        }
    }

    private static void checkDataType(IActionDataType<?> dataType, String path) {
        boolean matches = id(path).equals(dataType.getLocation());
        TCTHIntegration.LOGGER.debug("[TCTH] Arc data type tcth:{} {} (location {})",
                path, matches ? "ok" : "MISMATCH", dataType.getLocation());
        if (!matches) {
            TCTHIntegration.LOGGER.warn("[TCTH] Arc data type tcth:{} location mismatch", path);
        }
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("tcth", path);
    }
}
