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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
