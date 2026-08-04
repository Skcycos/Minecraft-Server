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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
