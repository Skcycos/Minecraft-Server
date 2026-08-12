package com.tanrunn.tcth;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * TCTH Integration configuration.
 *
 * <p>This is the framework-level configuration skeleton. Every compat feature
 * shipped in later phases must add its own toggle here so that it can be
 * disabled independently (see the project architecture requirements).
 *
 * <p>All config fields must carry an English comment; player-visible labels
 * live in the lang files ({@code assets/tcth/lang/en_us.json} and
 * {@code assets/tcth/lang/zh_cn.json}).
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Master switch for the whole integration framework.
     *
     * <p>Enforcement, as of phase 1A: the unified dish-cooked event dispatcher
     * ({@code DishCookedEventDispatcher}) mechanically refuses to publish any
     * event when this is {@code false}. Additionally, every compat module is
     * expected to check this (or its own toggle) before performing business
     * logic.
     */
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for the TCTH integration framework.",
                    "When false, the dish-cooked event dispatcher posts no events",
                    "and compat modules must not perform business logic.")
            .define("enabled", true);

    /**
     * Brewer (Mystic Brewer) beverage integration switch (phase 7B).
     *
     * <p>Default OFF. Controls whether {@code BeveragePreparedEvent}s are
     * published by the brewing dispatcher and whether brewing compat modules
     * may perform detection logic. Independent of the master {@link #ENABLED}
     * switch; both must be true for an event to be posted.
     */
    public static final ModConfigSpec.BooleanValue BREWER_INTEGRATION_ENABLED = BUILDER
            .comment("Brewer (Mystic Brewer) beverage integration switch.",
                    "Default off. When false, no BeveragePreparedEvent is posted",
                    "and brewing compat modules must not perform detection logic.")
            .define("brewerIntegrationEnabled", false);

    /**
     * Brewer experience rewards module.
     *
     * <p>Default OFF. Controls whether the {@code tcth:on_beverage_prepared}
     * Arc action is sent for beverage events (and thus whether brewer rewards
     * settle). Requires {@link #ENABLED} and
     * {@link #BREWER_INTEGRATION_ENABLED} to also be true. No gold rewards.
     */
    public static final ModConfigSpec.BooleanValue BREWER_REWARDS_ENABLED = BUILDER
            .comment("Brewer (Mystic Brewer) experience rewards switch.",
                    "Default off. When false, no tcth:on_beverage_prepared Arc",
                    "action is sent and no brewer rewards settle. Requires",
                    "enabled + brewerIntegrationEnabled as well. No gold.")
            .define("brewerRewardsEnabled", false);

    /** Per-player per-tick cap on brewer reward actions (phase 7C). */
    public static final ModConfigSpec.IntValue MAX_BREWER_REWARDS_PER_TICK_PER_PLAYER = BUILDER
            .comment("Maximum brewer reward actions settled per player per tick.",
                    "Default 20. Protects against event storms; excess events are",
                    "dropped (they never occupy idempotency or rate-limit state).")
            .defineInRange("maxBrewerRewardsPerTickPerPlayer", 20, 1, 1000);

    /**
     * Brewer (Mystic Brewer) ability-tree master switch (phase 7E).
     *
     * <p>When false, all four brewer ability routes stop applying their
     * effects. Does not delete purchased powerups and does not affect the Jobs+
     * GUI or brewer experience rewards.
     */
    public static final ModConfigSpec.BooleanValue BREWER_ABILITIES_ENABLED = BUILDER
            .comment("Master switch for the brewer ability tree (brewing / tasting /",
                    "resistance / study routes). When false, all four routes stop",
                    "applying their effects. Does not delete purchased powerups and",
                    "does not affect the Jobs+ GUI or brewer rewards.")
            .define("brewerAbilitiesEnabled", true);

    /**
     * 调饮路线 (brewing route): preparing a graded beverage grants a short
     * status package — I: Speed I 5 s; II: Speed I 8 s + Luck I 8 s;
     * III: Speed I 12 s + Luck I 12 s. Higher tier overwrites, never stacks.
     */
    public static final ModConfigSpec.BooleanValue BREWER_BREWING_ABILITIES_ENABLED = BUILDER
            .comment("Brewing route: beverage preparation grants short status effects",
                    "I: Speed I 5s; II: Speed I 8s + Luck I 8s; III: Speed I 12s + Luck I 12s.",
                    "Higher tier overwrites, never stacks. Requires brewerAbilitiesEnabled.")
            .define("brewerBrewingAbilitiesEnabled", true);

    /**
     * 品鉴路线 (tasting route): drinking {@code #tcth:brewer_drinks} grants a
     * status package with a shared 20 s cooldown — I: Regeneration I 5 s;
     * II: Regeneration I 5 s + Resistance I 8 s;
     * III: Regeneration I 5 s + Resistance I 8 s + Speed I 15 s.
     */
    public static final ModConfigSpec.BooleanValue BREWER_TASTING_ABILITIES_ENABLED = BUILDER
            .comment("Tasting route: drinking #tcth:brewer_drinks grants status effects",
                    "I: Regeneration I 5s; II: Regeneration I 5s + Resistance I 8s;",
                    "III: Regeneration I 5s + Resistance I 8s + Speed I 15s. Shared 20s",
                    "cooldown. Requires brewerAbilitiesEnabled.")
            .define("brewerTastingAbilitiesEnabled", true);

    /**
     * 魔酿耐受路线 (resistance route): reliably-recognised magical /
     * indirect-magical / wither damage to the player is reduced by
     * 10% / 20% / 35%. Never full immunity; fire, fall, melee and projectile
     * damage are unaffected.
     */
    public static final ModConfigSpec.BooleanValue BREWER_RESISTANCE_ABILITIES_ENABLED = BUILDER
            .comment("Resistance route: magical / indirect-magical / wither damage taken",
                    "is reduced by 10% / 20% / 35%. Never full immunity; fire, fall,",
                    "melee and projectile damage are unaffected.",
                    "Requires brewerAbilitiesEnabled.")
            .define("brewerResistanceAbilitiesEnabled", true);

    /**
     * 研修路线 (study route): tcth:brewer job experience multiplier
     * ×1.15 / ×1.35 / ×1.60. Only the highest active tier applies (no
     * multiplicative stacking).
     */
    public static final ModConfigSpec.BooleanValue BREWER_STUDY_ABILITIES_ENABLED = BUILDER
            .comment("Study route: tcth:brewer job experience multiplier",
                    "1.15 / 1.35 / 1.60. Only the highest active tier applies,",
                    "never stacked. Requires brewerAbilitiesEnabled.")
            .define("brewerStudyAbilitiesEnabled", true);

    /**
     * 品鉴路线共享冷却（tick；默认 400 = 20 s）。
     */
    public static final ModConfigSpec.IntValue BREWER_DRINK_COOLDOWN_TICKS = BUILDER
            .comment("Tasting-route shared anti-farm cooldown in ticks (default 400 = 20 s).",
                    "Per-player, in-memory only, shared by all three tasting nodes.",
                    "Range 1 ~ 72000.")
            .defineInRange("brewerDrinkCooldownTicks", 400, 1, 72000);

    /**
     * Brewer statistics archive (phase 7D).
     *
     * <p>Independent of Jobs+/Arc and of brewer rewards. When false, no
     * {@code BeveragePreparedEvent} is recorded into {@code tcth_brewing_stats.dat}.
     */
    public static final ModConfigSpec.BooleanValue BREWER_STATS_ENABLED = BUILDER
            .comment("Enable the per-player brewing statistics archive.",
                    "Independent of brewerRewardsEnabled and of Jobs+/Arc.",
                    "Default true.")
            .define("brewerStatsEnabled", true);

    /**
     * Field Guide brewer beverage catalogue (phase 7D).
     *
     * <p>When true, real-player {@code BeveragePreparedEvent}s unlock the
     * {@code item:*} entries of the two brewer categories (COMMON 18 / T2 46)
     * in the Field Guide. Default true. No effect when Field Guide is not
     * installed; the implementation classes are not loaded then.
     */
    public static final ModConfigSpec.BooleanValue FIELD_GUIDE_BREWER_ENABLED = BUILDER
            .comment("Unlock Field Guide brewer beverage entries when a beverage",
                    "is prepared (real player, non-automated).",
                    "Independent of the chef cookbook and of Jobs+/Arc.",
                    "Default true. No effect when Field Guide is not installed.")
            .define("fieldGuideBrewerEnabled", true);

    /**
     * Jobs+ dish-cooking experience rewards module.
     *
     * <p>Default OFF. Controls whether the {@code tcth:on_dish_cooked} Arc
     * action is sent for dish events. Note: the preset's {@code taste_meal}
     * action is a separate {@code arc:on_eat} action — as soon as the
     * {@code tcth-chef} data pack is enabled, eating {@code #tcth:chef_meals}
     * grants 1 XP regardless of this flag. During zero-reward drills, do not
     * eat those dishes.
     */
    public static final ModConfigSpec.BooleanValue JOBS_PLUS_REWARDS_ENABLED = BUILDER
            .comment("Send tcth:on_dish_cooked dish actions (Jobs+ rewards).",
                    "Does NOT gate the separate arc:on_eat taste_meal action:",
                    "with the tcth-chef data pack enabled, eating #tcth:chef_meals",
                    "grants 1 XP even when this is false.",
                    "Default off. Only enable after on-server verification of the",
                    "seven player take-out scenarios (workbench, furnace, smoker,",
                    "FD cooking pot, KC pot, KC stockpot, KC steamer).")
            .define("jobsPlusRewardsEnabled", false);

    /**
     * Rate limit: maximum dish events settled per player per tick. Guards
     * against short bursts of automated/bulk production flooding the reward
     * pipeline. Only successfully sent actions count against the limit.
     */
    public static final ModConfigSpec.IntValue MAX_EVENTS_PER_TICK_PER_PLAYER = BUILDER
            .comment("Maximum dish actions sent per player per tick (rate limit).")
            .defineInRange("maxEventsPerTickPerPlayer", 20, 1, 1000);

    /**
     * Cooking-statistics module (per-player cooking archive).
     *
     * <p>Independent of Jobs+/Arc and of the reward switch. Default ON.
     */
    public static final ModConfigSpec.BooleanValue COOKING_STATS_ENABLED = BUILDER
            .comment("Enable the per-player cooking statistics archive.",
                    "Independent of jobsPlusRewardsEnabled and of Jobs+/Arc.")
            .define("cookingStatsEnabled", true);

    /**
     * Field Guide integration master switch.
     *
     * <p>Master switch for ALL TCTH → Field Guide unlocks (chef cookbook and
     * brewer catalogue). When false, neither dish take-out nor beverage
     * preparation unlocks any Field Guide entry. Independent of the Field
     * Guide mod itself, of cooking/brewing statistics, and of Jobs+/Arc.
     * Default true; no effect when Field Guide is not installed.
     */
    public static final ModConfigSpec.BooleanValue FIELD_GUIDE_ENABLED = BUILDER
            .comment("Master switch for TCTH Field Guide unlocks (chef cookbook +",
                    "brewer catalogue). When false, no dish or beverage unlock is",
                    "sent to Field Guide. Default true. No effect when Field Guide",
                    "is not installed.")
            .define("fieldGuideEnabled", true);

    /**
     * Field Guide cookbook unlock module.
     *
     * <p>Controls ONLY whether TCTH unlocks Field Guide entries when a dish is
     * taken out of a cooking device ({@code DishCookedEvent}). It does not
     * control the Field Guide mod itself, does not affect cooking statistics,
     * and does not affect Jobs+/Arc experience rewards. Default ON; has no
     * effect when Field Guide is not installed.
     */
    public static final ModConfigSpec.BooleanValue FIELD_GUIDE_COOKBOOK_ENABLED = BUILDER
            .comment("Unlock Field Guide entries when a dish is cooked (take-out).",
                    "Only controls the TCTH dish-cooked unlock; does not control the",
                    "Field Guide mod itself, cooking stats, or Jobs+/Arc rewards.",
                    "Default true. No effect when Field Guide is not installed.")
            .define("fieldGuideCookbookEnabled", true);

    /**
     * Dish signing (chef signature component).
     *
     * <p>Controls ONLY whether newly produced dishes get the
     * {@code tcth:cooking_signature} component. Disabling it does not remove
     * existing signatures, does not affect cooking stats, Field Guide unlocks,
     * or Jobs+/Arc rewards, and does not affect unsigned dishes.
     */
    public static final ModConfigSpec.BooleanValue DISH_SIGNATURES_ENABLED = BUILDER
            .comment("Sign finished dishes with the chef's tcth:cooking_signature component.",
                    "Only controls signing of NEWLY produced dishes; does not remove",
                    "existing signatures, and does not affect cooking stats, Field Guide,",
                    "or Jobs+/Arc. Default true.")
            .define("dishSignaturesEnabled", true);

    /**
     * Chef ability tree master switch (phase 3D).
     *
     * <p>When {@code false}, the four chef ability routes (knife, hearth,
     * tasting, study) must all stop their business effects. The study-route
     * multipliers are driven by Arc data, so the preset's Arc actions carry
     * TCTH conditions that read this switch. Job data loading and the Jobs+
     * GUI are never affected.
     */
    public static final ModConfigSpec.BooleanValue CHEF_ABILITIES_ENABLED = BUILDER
            .comment("Master switch for the chef ability tree (knife / hearth / tasting / study routes).",
                    "When false, all four routes stop applying their effects.",
                    "Does not delete purchased powerups and does not affect Jobs+ GUI.",
                    "Default true.")
            .define("chefAbilitiesEnabled", true);

    /**
     * Tasting-route effects toggle (phase 3D).
     *
     * <p>Controls the regeneration/resistance/speed effects granted after
     * eating a {@code #tcth:chef_meals} dish. Independent of
     * {@link #CHEF_ABILITIES_ENABLED} (both must be true for the effects).
     */
    public static final ModConfigSpec.BooleanValue TASTING_EFFECTS_ENABLED = BUILDER
            .comment("Tasting route: grant short status effects after eating #tcth:chef_meals.",
                    "Default true.")
            .define("tastingEffectsEnabled", true);

    /**
     * Hearth-route fire resistance toggle (phase 3D).
     *
     * <p>Controls the {@code minecraft:is_fire} damage reduction (15/30/50%).
     * Independent of {@link #CHEF_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue FIRE_RESISTANCE_ABILITIES_ENABLED = BUILDER
            .comment("Hearth route: reduce fire damage (#minecraft:is_fire) by 15/30/50%.",
                    "Default true.")
            .define("fireResistanceAbilitiesEnabled", true);

    /**
     * Knife-route durability toggle (phase 3D).
     *
     * <p>Controls the chance to skip durability loss on {@code #c:tools/knife}
     * items (10/20/35%). Independent of {@link #CHEF_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue KNIFE_DURABILITY_ABILITIES_ENABLED = BUILDER
            .comment("Knife route: chance to skip durability loss on #c:tools/knife items.",
                    "Default true.")
            .define("knifeDurabilityAbilitiesEnabled", true);

    /**
     * Tasting-route per-player cooldown in ticks (phase 3D).
     *
     * <p>Once a tasting effect set has been granted, further grants for the
     * same player are blocked for this many ticks. Shared between all three
     * tasting nodes. Never written to player NBT; cleared on logout and on
     * server stop.
     */
    public static final ModConfigSpec.IntValue TASTING_EFFECT_COOLDOWN_TICKS = BUILDER
            .comment("Tasting route anti-farm cooldown in ticks (default 400 = 20 s).",
                    "Per-player, in-memory only, shared by all tasting nodes.",
                    "Default: 400. Range: 1 ~ 72000.")
            .defineInRange("tastingEffectCooldownTicks", 400, 1, 72000);

    /**
     * Unified farming framework switch (phase 4A.2).
     *
     * <p>Controls the detection and posting of {@code CropHarvestedEvent}
     * (break detector + right-click harvest mixins). When {@code false},
     * nothing is detected or posted. Independent of the reward switch and of
     * the cooking framework.
     */
    public static final ModConfigSpec.BooleanValue FARMER_INTEGRATION_ENABLED = BUILDER
            .comment("Unified crop-harvest event detection and posting (CropHarvestedEvent).",
                    "When false, no farming events are detected or posted.",
                    "Independent of farmerRewardsEnabled and of the cooking framework.",
                    "Default true.")
            .define("farmerIntegrationEnabled", true);

    /**
     * Farmer job reward switch (phase 4A.2).
     *
     * <p>Controls ONLY whether the {@code tcth:on_crop_harvested} Arc action
     * is sent for {@code CropHarvestedEvent}s (Jobs+/Arc experience rewards).
     * It does not gate event detection itself (see
     * {@link #FARMER_INTEGRATION_ENABLED}) and must not be confused with
     * {@link #JOBS_PLUS_REWARDS_ENABLED}, which only controls the chef dish
     * rewards. Default OFF — enable only after live verification.
     */
    public static final ModConfigSpec.BooleanValue FARMER_REWARDS_ENABLED = BUILDER
            .comment("Send tcth:on_crop_harvested Arc actions (farmer job rewards).",
                    "Only controls the Jobs+/Arc farmer reward settlement; does not",
                    "gate farming event detection (farmerIntegrationEnabled).",
                    "Independent of jobsPlusRewardsEnabled (chef dish rewards).",
                    "Default off. Enable only after live player verification.")
            .define("farmerRewardsEnabled", false);

    // ---- phase 4B: farmer ability routes ----

    /**
     * Master switch for the four farmer ability routes (tilling / harvest /
     * livestock / study). When false, no farmer ability has any effect.
     * Does not delete purchased powerups and does not affect the Jobs+ GUI.
     */
    public static final ModConfigSpec.BooleanValue FARMER_ABILITIES_ENABLED = BUILDER
            .comment("Master switch for the farmer ability tree (4 routes).",
                    "When false, no farmer ability takes effect.",
                    "Does not delete purchased powerups and does not affect the Jobs+ GUI.",
                    "Default: true.")
            .define("farmerAbilitiesEnabled", true);

    /**
     * Tilling route: chance to skip hoe durability loss on break/use of a
     * {@code #minecraft:hoes} tool (10% / 20% / 35%). Requires
     * {@link #FARMER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue TILLING_DURABILITY_ABILITIES_ENABLED = BUILDER
            .comment("Tilling route: chance to skip durability loss on #minecraft:hoes",
                    "tools 10% / 20% / 35%. Requires farmerAbilitiesEnabled.",
                    "Never repairs, copies or affects non-hoe items.",
                    "Default: true.")
            .define("tillingDurabilityAbilitiesEnabled", true);

    /**
     * Harvest route: a real successful {@code CropHarvestedEvent} grants
     * short status effects I: Haste I 5 s; II: Haste I + Speed I 8 s;
     * III: Haste I + Speed I 12 s. Higher tier overwrites, never stacks;
     * automated / fake-player / immature / failed harvests never trigger;
     * shared 10 s cooldown committed only on success.
     * Requires {@link #FARMER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue FARMER_HARVEST_ABILITIES_ENABLED = BUILDER
            .comment("Harvest route: real CropHarvestedEvent grants short effects",
                    "I: Haste I 5s; II: Haste I + Speed I 8s; III: Haste I + Speed I 12s.",
                    "Higher tier overwrites, never stacks; automated/fake/immature",
                    "harvests never trigger; shared 10s cooldown, success-driven.",
                    "Requires farmerAbilitiesEnabled.",
                    "Default: true.")
            .define("farmerHarvestAbilitiesEnabled", true);

    /**
     * Livestock route: successful breeding, taming or shearing grants
     * I: Regeneration I 5 s; II: Regeneration I 5 s + Resistance I 8 s;
     * III: Regeneration I 5 s + Resistance I 8 s + Speed I 15 s. Shared 20 s
     * cooldown; failed operations, non-player actors and mechanical paths
     * never trigger. Requires {@link #FARMER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue FARMER_LIVESTOCK_ABILITIES_ENABLED = BUILDER
            .comment("Livestock route: breeding/taming/shearing grants short effects",
                    "I: Regeneration I 5s; II: + Resistance I 8s; III: + Speed I 15s.",
                    "Shared 20s cooldown; failed ops, non-player actors and",
                    "mechanical paths never trigger.",
                    "Requires farmerAbilitiesEnabled.",
                    "Default: true.")
            .define("farmerLivestockAbilitiesEnabled", true);

    /**
     * Farmer-study route: tcth:farmer job experience multiplier
     * (×1.15 / ×1.35 / ×1.60). Requires {@link #FARMER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue FARMER_STUDY_ABILITIES_ENABLED = BUILDER
            .comment("Farmer-study route: tcth:farmer job experience",
                    "×1.15 / ×1.35 / ×1.60. Requires farmerAbilitiesEnabled.",
                    "Default: true.")
            .define("farmerStudyAbilitiesEnabled", true);

    /**
     * Harvest-route shared anti-farm cooldown in ticks (default 200 = 10 s).
     * Per-player, in-memory only, shared by all three harvest nodes.
     */
    public static final ModConfigSpec.IntValue FARMER_HARVEST_COOLDOWN_TICKS = BUILDER
            .comment("Harvest-route shared anti-farm cooldown in ticks (default 200 = 10 s).",
                    "Per-player, in-memory only, shared by all three harvest nodes.",
                    "Range 1 ~ 72000.")
            .defineInRange("farmerHarvestCooldownTicks", 200, 1, 72000);

    /**
     * Livestock-route shared anti-farm cooldown in ticks (default 400 = 20 s).
     * Per-player, in-memory only, shared by all three livestock nodes.
     */
    public static final ModConfigSpec.IntValue FARMER_LIVESTOCK_COOLDOWN_TICKS = BUILDER
            .comment("Livestock-route shared anti-farm cooldown in ticks (default 400 = 20 s).",
                    "Per-player, in-memory only, shared by all three livestock nodes.",
                    "Range 1 ~ 72000.")
            .defineInRange("farmerLivestockCooldownTicks", 400, 1, 72000);

    // ---- phase 5A: gunner profession ----

    /**
     * Gunner profession master switch (phase 5A).
     *
     * <p>When {@code false}, the Scorched Guns compat module is not initialised:
     * no {@code GunKillEvent} detection, no posting, no stats, no reward. Does
     * not affect chef or farmer.
     */
    public static final ModConfigSpec.BooleanValue GUNNER_INTEGRATION_ENABLED = BUILDER
            .comment("Gunner profession master switch (Scorched Guns firearm kills).",
                    "When false, no firearm-kill events are detected or posted.",
                    "Independent of chef/farmer and of Jobs+/Arc.",
                    "Default true.")
            .define("gunnerIntegrationEnabled", true);

    /**
     * Gunner reward switch (phase 5A).
     *
     * <p>Controls ONLY whether the {@code tcth:on_gun_kill} Arc action is sent
     * for {@code GunKillEvent}s (Jobs+/Arc experience rewards). It does not gate
     * event detection or statistics. Default OFF — enable only after live
     * verification.
     */
    public static final ModConfigSpec.BooleanValue GUNNER_REWARDS_ENABLED = BUILDER
            .comment("Send tcth:on_gun_kill Arc actions (gunner job rewards).",
                    "Only controls the Jobs+/Arc gunner reward settlement; does not",
                    "gate firearm-kill detection (gunnerIntegrationEnabled) or stats",
                    "(gunnerStatsEnabled). Independent of jobsPlusRewardsEnabled",
                    "(chef) and farmerRewardsEnabled.",
                    "Default off. Enable only after live player verification.")
            .define("gunnerRewardsEnabled", false);

    /**
     * Gunner statistics switch (phase 5A).
     *
     * <p>Controls ONLY whether per-player gunner statistics
     * ({@code world/data/tcth_gunner_stats.dat}) are updated. Independent of
     * the reward switch. Default ON.
     */
    public static final ModConfigSpec.BooleanValue GUNNER_STATS_ENABLED = BUILDER
            .comment("Update per-player gunner statistics (tcth_gunner_stats.dat).",
                    "Independent of gunnerRewardsEnabled. Default true.")
            .define("gunnerStatsEnabled", true);

    /**
     * Personal chat announcement when a gunner medal is newly unlocked (phase 5C).
     *
     * <p>When false, medals still unlock and persist; only the chat line is
     * suppressed. Re-enabling never re-announces historical unlocks. Config
     * read failures fail closed (no announce).
     */
    public static final ModConfigSpec.BooleanValue GUNNER_MEDAL_ANNOUNCEMENTS_ENABLED = BUILDER
            .comment("Chat the player when a gunner battlefield medal unlocks.",
                    "Medals still unlock and persist when this is false.",
                    "Historical / migrated medals are never announced.",
                    "Default: true.")
            .define("gunnerMedalAnnouncementsEnabled", true);

    /**
     * Rate limit: maximum gun-kill actions settled per player per tick.
     */
    public static final ModConfigSpec.IntValue MAX_GUN_KILL_ACTIONS_PER_TICK = BUILDER
            .comment("Maximum gun-kill actions sent per player per tick (rate limit).")
            .defineInRange("maxGunKillActionsPerTick", 10, 1, 1000);

    /**
     * BOSS-tier target cooldown (phase 5A).
     *
     * <p>After a BOSS-tier kill, further BOSS-tier kills by the same player are
     * blocked for this many ticks. Prevents boss-respawn farming. Per-player,
     * in-memory only.
     */
    public static final ModConfigSpec.IntValue GUNNER_BOSS_COOLDOWN_TICKS = BUILDER
            .comment("BOSS-tier gun-kill cooldown per player in ticks (default 1200 = 60 s).",
                    "Per-player, in-memory only, prevents boss-respawn farming.",
                    "Default: 1200. Range: 0 ~ 72000.")
            .defineInRange("gunnerBossCooldownTicks", 1200, 0, 72000);

    // ---- gunner ability routes (phase 5B) ----

    /**
     * Master switch for the four gunner ability routes. When false, no gunner
     * ability has any effect (damage, ammo, defense, experience).
     */
    public static final ModConfigSpec.BooleanValue GUNNER_ABILITIES_ENABLED = BUILDER
            .comment("Master switch for the tcth:gunner ability tree (4 routes).",
                    "When false, no gunner ability takes effect.",
                    "Default: true.")
            .define("gunnerAbilitiesEnabled", true);

    /**
     * Marksmanship route: SG firearm damage dealt to non-player targets
     * (×1.05 / ×1.10 / ×1.15). Requires {@link #GUNNER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue GUN_DAMAGE_ABILITIES_ENABLED = BUILDER
            .comment("Marksmanship route: SG firearm damage dealt to non-player targets",
                    "×1.05 / ×1.10 / ×1.15. Requires gunnerAbilitiesEnabled.",
                    "Default: true.")
            .define("gunDamageAbilitiesEnabled", true);

    /**
     * Ammo-saver route: chance to not consume ammo on a successful shot
     * (5% / 10% / 15%). Requires {@link #GUNNER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue GUN_AMMO_ABILITIES_ENABLED = BUILDER
            .comment("Ammo-saver route: chance not to consume ammo on a successful shot",
                    "5% / 10% / 15%. Requires gunnerAbilitiesEnabled.",
                    "Default: true.")
            .define("gunAmmoAbilitiesEnabled", true);

    /**
     * Battlefield-defense route: SG firearm/explosion damage taken by the
     * player (×0.90 / ×0.80 / ×0.70). Requires {@link #GUNNER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue GUN_DEFENSE_ABILITIES_ENABLED = BUILDER
            .comment("Battlefield-defense route: SG firearm damage taken by the player",
                    "×0.90 / ×0.80 / ×0.70. Requires gunnerAbilitiesEnabled.",
                    "Default: true.")
            .define("gunDefenseAbilitiesEnabled", true);

    /**
     * Gunner-study route: tcth:gunner job experience multiplier
     * (×1.15 / ×1.35 / ×1.60). Requires {@link #GUNNER_ABILITIES_ENABLED}.
     */
    public static final ModConfigSpec.BooleanValue GUN_EXPERIENCE_ABILITIES_ENABLED = BUILDER
            .comment("Gunner-study route: tcth:gunner job experience",
                    "×1.15 / ×1.35 / ×1.60. Requires gunnerAbilitiesEnabled.",
                    "Default: true.")
            .define("gunExperienceAbilitiesEnabled", true);

    // ---- phase 8B: shadow thief (framework only) ----

    /**
     * Shadow thief framework master switch (phase 8B).
     *
     * <p>Default OFF. When false, the attempt coordinator and the event
     * dispatcher refuse every attempt. Even when true, the phase-8B defaults
     * (empty candidate provider, no-op transfer executor, deny-all
     * protection) make real transfers impossible.
     */
    public static final ModConfigSpec.BooleanValue SHADOW_THIEF_INTEGRATION_ENABLED = BUILDER
            .comment("Master switch for the shadow thief framework.",
                    "Default off. When false, no attempt is ever coordinated",
                    "and no ShadowTheftEvent is posted. Even with this and",
                    "shadowPlayerTheftEnabled on, REAL asset transfers stay",
                    "locked behind shadowRealAssetTransfersEnabled (default",
                    "off); the audit log is a plain SavedData, not an fsync",
                    "WAL — live enablement needs operator confirmation.")
            .define("shadowThiefIntegrationEnabled", false);

    /**
     * Player-target theft switch (phase 8B).
     *
     * <p>Default OFF. When false, attempts against player targets are refused
     * with {@code INVALID_CONTEXT} before any further processing.
     */
    public static final ModConfigSpec.BooleanValue SHADOW_PLAYER_THEFT_ENABLED = BUILDER
            .comment("Allow shadow theft attempts against player targets.",
                    "Default off. When false, player-target attempts are",
                    "rejected at context validation. Real transfers still",
                    "require shadowRealAssetTransfersEnabled.")
            .define("shadowPlayerTheftEnabled", false);

    /**
     * Entity-target theft switch (phase 8B).
     *
     * <p>Default OFF. When false, attempts against non-player entity targets
     * are refused with {@code INVALID_CONTEXT} before any further processing.
     */
    public static final ModConfigSpec.BooleanValue SHADOW_ENTITY_THEFT_ENABLED = BUILDER
            .comment("Allow shadow theft attempts against entity targets.",
                    "Default off. When false, entity-target attempts are",
                    "rejected at context validation.")
            .define("shadowEntityTheftEnabled", false);

    /**
     * Shadow theft audit log switch (phase 8B).
     *
     * <p>Default ON. A {@code SUCCESS} outcome is only ever posted after the
     * audit record was written; when audit is disabled (or the write fails)
     * the attempt ends in {@code AUDIT_FAILED} and never reports success.
     */
    public static final ModConfigSpec.BooleanValue SHADOW_AUDIT_ENABLED = BUILDER
            .comment("Write shadow theft attempts to tcth_shadow_audit.dat.",
                    "Default true. SUCCESS is only posted after a successful",
                    "audit write; disabling audit therefore blocks SUCCESS",
                    "outcomes (fail closed).")
            .define("shadowAuditEnabled", true);

    /**
     * Base success chance of a shadow theft attempt (phase 8B, stage 8A §10).
     *
     * <p>Default 0.35. Clamped into [shadowMinSuccessChance,
     * shadowMaxSuccessChance]; non-finite values fail closed.
     */
    public static final ModConfigSpec.DoubleValue SHADOW_BASE_SUCCESS_CHANCE = BUILDER
            .comment("Base success chance of a shadow theft attempt (default 0.35).",
                    "Final balance is decided in a later phase; the value is",
                    "clamped into [shadowMinSuccessChance, shadowMaxSuccessChance].",
                    "Range: 0.0 ~ 1.0.")
            .defineInRange("shadowBaseSuccessChance", 0.35d, 0.0d, 1.0d);

    /**
     * Lower clamp of the success chance (phase 8B, stage 8A §10).
     *
     * <p>Default 0.05. A success chance never goes below this value, and a
     * non-finite calculation fails closed to this value.
     */
    public static final ModConfigSpec.DoubleValue SHADOW_MIN_SUCCESS_CHANCE = BUILDER
            .comment("Lower clamp of the shadow theft success chance (default 0.05).",
                    "A non-finite chance calculation fails closed to this value.",
                    "Range: 0.0 ~ 1.0.")
            .defineInRange("shadowMinSuccessChance", 0.05d, 0.0d, 1.0d);

    /**
     * Upper clamp of the success chance (phase 8B, stage 8A §10).
     *
     * <p>Default 0.85. A success chance never reaches 100%.
     */
    public static final ModConfigSpec.DoubleValue SHADOW_MAX_SUCCESS_CHANCE = BUILDER
            .comment("Upper clamp of the shadow theft success chance (default 0.85).",
                    "A success chance never reaches 100%.",
                    "Range: 0.0 ~ 1.0.")
            .defineInRange("shadowMaxSuccessChance", 0.85d, 0.0d, 1.0d);

    /**
     * Per-thief global action cooldown in ticks (phase 8B).
     *
     * <p>Committed after a successful theft. In-memory only; tick-based;
     * never written to player NBT.
     */
    public static final ModConfigSpec.LongValue SHADOW_GLOBAL_COOLDOWN_TICKS = BUILDER
            .comment("Per-thief global action cooldown after a successful theft,",
                    "in ticks (default 200 = 10 s). In-memory, tick-based only.",
                    "Range: 0 ~ 1728000.")
            .defineInRange("shadowGlobalCooldownTicks", 200L, 0L, 1_728_000L);

    /**
     * Short per-thief cooldown after an empty-candidate attempt (phase 8B).
     */
    public static final ModConfigSpec.LongValue SHADOW_NO_CANDIDATE_COOLDOWN_TICKS = BUILDER
            .comment("Short per-thief cooldown after an empty-candidate attempt,",
                    "in ticks (default 40 = 2 s). In-memory, tick-based only.",
                    "Range: 0 ~ 1728000.")
            .defineInRange("shadowNoCandidateCooldownTicks", 40L, 0L, 1_728_000L);

    /**
     * Per-thief cooldown after a failed roll or a failed transfer (phase 8B).
     */
    public static final ModConfigSpec.LongValue SHADOW_FAILURE_COOLDOWN_TICKS = BUILDER
            .comment("Per-thief cooldown after a failed roll or a failed transfer,",
                    "in ticks (default 400 = 20 s). In-memory, tick-based only.",
                    "Range: 0 ~ 1728000.")
            .defineInRange("shadowFailureCooldownTicks", 400L, 0L, 1_728_000L);

    /**
     * Per-victim grace period after a successful theft (phase 8B).
     */
    public static final ModConfigSpec.LongValue SHADOW_VICTIM_PROTECTION_TICKS = BUILDER
            .comment("Per-victim grace period after a successful theft, in ticks",
                    "(default 1200 = 60 s). In-memory, tick-based only.",
                    "Range: 0 ~ 1728000.")
            .defineInRange("shadowVictimProtectionTicks", 1_200L, 0L, 1_728_000L);

    /**
     * Per-target alert window in ticks (phase 8B).
     *
     * <p>Set after a failed roll or a failed transfer ("exposure"); while
     * active the target is considered alerted and the success chance is
     * reduced.
     */
    public static final ModConfigSpec.LongValue SHADOW_ALERT_TICKS = BUILDER
            .comment("Per-target alert window after a failed attempt (exposure),",
                    "in ticks (default 100 = 5 s). While active the target is",
                    "considered alerted (-0.20 success chance).",
                    "In-memory, tick-based only. Range: 0 ~ 1728000.")
            .defineInRange("shadowAlertTicks", 100L, 0L, 1_728_000L);

    /**
     * New-player protection threshold in ticks of verified play time (phase
     * 8C.0).
     *
     * <p>A player target whose server-side {@code Stats.PLAY_TIME} is below
     * this value is protected from shadow theft
     * ({@code DENIED_NEW_PLAYER}). The time source is the verified play-time
     * stat, never wall-clock guesses. Default 72 000 ticks = 1 hour.
     */
    public static final ModConfigSpec.LongValue SHADOW_NEW_PLAYER_PROTECTION_TICKS = BUILDER
            .comment("New-player protection: a player target whose verified",
                    "play time (Stats.PLAY_TIME, server ticks) is below this",
                    "value is protected from shadow theft.",
                    "Default 72000 = 1 hour. Range: 0 ~ 1728000.")
            .defineInRange("shadowNewPlayerProtectionTicks", 72_000L, 0L, 1_728_000L);

    /**
     * Master gate for REAL asset transfers (phase 8C.2).
     *
     * <p>Default OFF. The transaction engine is wired into the production
     * coordinator, but no item, health, hunger or effect may move until an
     * operator explicitly enables this switch (together with
     * {@code enabled} + {@code shadowThiefIntegrationEnabled} +
     * {@code shadowPlayerTheftEnabled}). A config read failure fails closed.
     * Enabling on the live server requires operator confirmation — the audit
     * log is a plain SavedData, not an fsync WAL.
     */
    public static final ModConfigSpec.BooleanValue SHADOW_REAL_ASSET_TRANSFERS_ENABLED = BUILDER
            .comment("Master gate for REAL shadow theft asset transfers.",
                    "Default off. Even with the framework and player switches",
                    "on, no item/health/hunger/effect may move until this is",
                    "explicitly enabled by an operator. Fail-closed on config",
                    "read errors. Live-server enablement needs operator",
                    "confirmation (the audit log is not an fsync WAL).")
            .define("shadowRealAssetTransfersEnabled", false);

    /**
     * Per-victim daily successful-ITEM theft cap (phase 8C.2).
     *
     * <p>Keyed by victim UUID + UTC date; when the cap is reached the ITEM
     * type is removed from the candidate pool for that victim (HEALTH /
     * HUNGER / EFFECT stay available). Conservative default 3.
     */
    public static final ModConfigSpec.LongValue SHADOW_DAILY_ITEM_LOSS_LIMIT = BUILDER
            .comment("Per-victim daily cap on successful ITEM thefts (default 3).",
                    "Keyed by victim UUID + UTC date; at the cap the ITEM",
                    "type is removed from the victim's candidates.",
                    "Range: 1 ~ 10000.")
            .defineInRange("shadowDailyItemLossLimit", 3L, 1L, 10_000L);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
