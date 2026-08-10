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
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.BrewerDrinkCooldownCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.BrewerStudyAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.BrewerTastingAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.ChefAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.CookingDeviceCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishQualityCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishTierCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunnerExperienceAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FireDamageCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FireResistanceEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunKillDistanceCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunTargetTierCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.GunnerRewardsEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FarmerLivestockAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FarmerLivestockCooldownCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.FarmerStudyAbilitiesEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.HoeDurabilityEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.KnifeDurabilityEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.TastingCooldownCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.TastingEffectsEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.reward.BrewerTastingEffectsReward;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.reward.FarmerLivestockEffectsReward;
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
    public static final ActionType<CropHarvestedAction> CROP_HARVESTED =
            ActionType.register(id("on_crop_harvested"), new CropHarvestedAction.Serializer());

    // ---- action data types (no registry — validated by id in verify) ----
    public static final IActionDataType<String> RESULT_ITEM_ID = ActionDataType.register(id("result_item_id"));
    public static final IActionDataType<Integer> COUNT = ActionDataType.register(id("count"));
    public static final IActionDataType<String> RECIPE_ID = ActionDataType.register(id("recipe_id"));
    public static final IActionDataType<String> DEVICE = ActionDataType.register(id("device"));
    public static final IActionDataType<String> QUALITY = ActionDataType.register(id("quality"));
    public static final IActionDataType<String> TIER = ActionDataType.register(id("tier"));
    public static final IActionDataType<Boolean> AUTOMATED = ActionDataType.register(id("automated"));

    // ---- phase 4A.2: farming action data types ----
    public static final IActionDataType<String> CROP_ID = ActionDataType.register(id("crop_id"));
    public static final IActionDataType<String> HARVEST_METHOD = ActionDataType.register(id("harvest_method"));

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

    // ---- phase 5A: gunner action type ----
    public static final ActionType<GunKillAction> GUN_KILL =
            ActionType.register(id("on_gun_kill"), new GunKillAction.Serializer());

    // ---- phase 5A: gunner action data types ----
    public static final IActionDataType<String> WEAPON_ID = ActionDataType.register(id("weapon_id"));
    public static final IActionDataType<String> TARGET_ID = ActionDataType.register(id("target_id"));
    public static final IActionDataType<String> TARGET_TIER = ActionDataType.register(id("target_tier"));
    public static final IActionDataType<Float> GUN_KILL_DISTANCE = ActionDataType.register(id("gun_kill_distance"));

    // ---- phase 5A: gunner condition types ----
    public static final ConditionType<GunTargetTierCondition> GUN_TARGET_TIER =
            ConditionType.register(id("gun_target_tier"), new GunTargetTierCondition.Serializer());
    public static final ConditionType<GunKillDistanceCondition> GUN_KILL_DISTANCE_CONDITION =
            ConditionType.register(id("gun_kill_distance"), new GunKillDistanceCondition.Serializer());
    public static final ConditionType<GunnerRewardsEnabledCondition> GUNNER_REWARDS_ENABLED_CONDITION =
            ConditionType.register(id("gunner_rewards_enabled"), new GunnerRewardsEnabledCondition.Serializer());

    // ---- phase 5B: gunner ability-route condition types ----
    public static final ConditionType<GunnerExperienceAbilitiesEnabledCondition> GUNNER_EXPERIENCE_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("gunner_experience_abilities_enabled"),
                    new GunnerExperienceAbilitiesEnabledCondition.Serializer());

    // ---- phase 7E: brewer ability-tree condition types ----
    public static final ConditionType<BrewerStudyAbilitiesEnabledCondition> BREWER_STUDY_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("brewer_study_abilities_enabled"),
                    new BrewerStudyAbilitiesEnabledCondition.Serializer());
    public static final ConditionType<BrewerTastingAbilitiesEnabledCondition> BREWER_TASTING_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("brewer_tasting_abilities_enabled"),
                    new BrewerTastingAbilitiesEnabledCondition.Serializer());
    public static final ConditionType<BrewerDrinkCooldownCondition> BREWER_DRINK_COOLDOWN_CONDITION =
            ConditionType.register(id("brewer_drink_cooldown"),
                    new BrewerDrinkCooldownCondition.Serializer());

    // ---- phase 7E: brewer ability-tree reward type ----
    public static final IRewardType<BrewerTastingEffectsReward> BREWER_TASTING_EFFECTS =
            RewardType.register(id("brewer_tasting_effects"), new BrewerTastingEffectsReward.Serializer());

    // ---- phase 4B: farmer ability-tree condition types ----
    public static final ConditionType<HoeDurabilityEnabledCondition> HOE_DURABILITY_ENABLED_CONDITION =
            ConditionType.register(id("hoe_durability_enabled"),
                    new HoeDurabilityEnabledCondition.Serializer());
    public static final ConditionType<FarmerStudyAbilitiesEnabledCondition> FARMER_STUDY_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("farmer_study_abilities_enabled"),
                    new FarmerStudyAbilitiesEnabledCondition.Serializer());
    public static final ConditionType<FarmerLivestockAbilitiesEnabledCondition> FARMER_LIVESTOCK_ABILITIES_ENABLED_CONDITION =
            ConditionType.register(id("farmer_livestock_abilities_enabled"),
                    new FarmerLivestockAbilitiesEnabledCondition.Serializer());
    public static final ConditionType<FarmerLivestockCooldownCondition> FARMER_LIVESTOCK_COOLDOWN_CONDITION =
            ConditionType.register(id("farmer_livestock_cooldown"),
                    new FarmerLivestockCooldownCondition.Serializer());

    // ---- phase 4B: farmer ability-tree reward type ----
    public static final IRewardType<FarmerLivestockEffectsReward> FARMER_LIVESTOCK_EFFECTS =
            RewardType.register(id("farmer_livestock_effects"), new FarmerLivestockEffectsReward.Serializer());

    private TcthArcRegistrar() {
    }

    /**
     * Verifies every registered id against Arc's registries (or, for data
     * types without a registry, against their own location) and logs one DEBUG
     * line per entry. Must be called after the registrar class is loaded.
     */
    public static void verifyRegistrations() {
        checkInRegistry(ArcRegistry.ACTION, id("on_dish_cooked"), "action type");
        checkInRegistry(ArcRegistry.ACTION, id("on_crop_harvested"), "action type");
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
        checkDataType(CROP_ID, "crop_id");
        checkDataType(HARVEST_METHOD, "harvest_method");

        // Phase 3D: chef ability tree condition types.
        checkInRegistry(ArcRegistry.CONDITION, id("chef_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("tasting_effects_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("fire_resistance_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("knife_durability_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("fire_damage"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("tasting_cooldown"), "condition type");

        // Phase 3D: chef ability tree reward type.
        checkInRegistry(ArcRegistry.REWARD, id("tasting_effects"), "reward type");

        // Phase 5A: gunner action type, data types and conditions.
        checkInRegistry(ArcRegistry.ACTION, id("on_gun_kill"), "action type");
        checkDataType(WEAPON_ID, "weapon_id");
        checkDataType(TARGET_ID, "target_id");
        checkDataType(TARGET_TIER, "target_tier");
        checkDataType(GUN_KILL_DISTANCE, "gun_kill_distance");
        checkInRegistry(ArcRegistry.CONDITION, id("gun_target_tier"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("gun_kill_distance"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("gunner_rewards_enabled"), "condition type");

        // Phase 5B: gunner ability-route condition types.
        checkInRegistry(ArcRegistry.CONDITION, id("gunner_experience_abilities_enabled"), "condition type");

        // Phase 7E: brewer ability-tree condition types and reward type.
        checkInRegistry(ArcRegistry.CONDITION, id("brewer_study_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("brewer_tasting_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("brewer_drink_cooldown"), "condition type");
        checkInRegistry(ArcRegistry.REWARD, id("brewer_tasting_effects"), "reward type");

        // Phase 4B: farmer ability-tree condition types and reward type.
        checkInRegistry(ArcRegistry.CONDITION, id("hoe_durability_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("farmer_study_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("farmer_livestock_abilities_enabled"), "condition type");
        checkInRegistry(ArcRegistry.CONDITION, id("farmer_livestock_cooldown"), "condition type");
        checkInRegistry(ArcRegistry.REWARD, id("farmer_livestock_effects"), "reward type");
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
