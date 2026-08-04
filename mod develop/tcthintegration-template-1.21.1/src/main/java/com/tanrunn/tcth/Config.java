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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
