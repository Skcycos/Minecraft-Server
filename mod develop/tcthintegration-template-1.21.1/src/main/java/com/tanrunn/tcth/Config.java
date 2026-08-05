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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
