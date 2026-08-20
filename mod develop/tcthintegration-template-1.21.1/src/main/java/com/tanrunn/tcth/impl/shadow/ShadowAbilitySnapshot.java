package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;

/**
 * Immutable, pure-Minecraft snapshot of the {@code tcth:shadow_thief} ability
 * tiers for ONE theft attempt (phase 8E).
 *
 * <p>Queried at most once per attempt (through {@link ShadowAbilityAccess})
 * and then threaded through the whole attempt: candidate pool, success
 * chance, transfer prepare, cooldown and feedback all read the SAME
 * snapshot. It deliberately carries no Jobs+/Arc objects — only the four
 * route tiers — so it can flow through the public and transaction layers.
 *
 * <p>The tiers are already route-gated by the ability module: a route whose
 * config switch (or master ability switch) is off, or whose Jobs+ query
 * fails, reports {@link ShadowAbilityTier#NONE} (basic behaviour).
 *
 * @param sleight      妙手路线 tier
 * @param lifeSiphon   夺生路线 tier
 * @param spellTheft   窃法路线 tier
 * @param shadowEscape 潜影路线 tier
 */
public record ShadowAbilitySnapshot(ShadowAbilityTier sleight, ShadowAbilityTier lifeSiphon,
                                    ShadowAbilityTier spellTheft, ShadowAbilityTier shadowEscape) {

    public ShadowAbilitySnapshot {
        Objects.requireNonNull(sleight, "sleight");
        Objects.requireNonNull(lifeSiphon, "lifeSiphon");
        Objects.requireNonNull(spellTheft, "spellTheft");
        Objects.requireNonNull(shadowEscape, "shadowEscape");
    }

    /**
     * @return a snapshot with every route at {@link ShadowAbilityTier#NONE} —
     *         the fail-closed default (no Jobs+, no job, switches off or
     *         query failure)
     */
    public static ShadowAbilitySnapshot none() {
        return new ShadowAbilitySnapshot(ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                ShadowAbilityTier.NONE, ShadowAbilityTier.NONE);
    }

    /** @return the tier of the given route */
    public ShadowAbilityTier tier(ShadowAbilityRoute route) {
        Objects.requireNonNull(route, "route");
        return switch (route) {
            case SLEIGHT -> sleight;
            case LIFE_SIPHON -> lifeSiphon;
            case SPELL_THEFT -> spellTheft;
            case SHADOW_ESCAPE -> shadowEscape;
        };
    }

    /** @return whether any route has an active tier */
    public boolean hasAny() {
        return sleight != ShadowAbilityTier.NONE || lifeSiphon != ShadowAbilityTier.NONE
                || spellTheft != ShadowAbilityTier.NONE || shadowEscape != ShadowAbilityTier.NONE;
    }
}
