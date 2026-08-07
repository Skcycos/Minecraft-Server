package com.tanrunn.tcth.impl.stats;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Pure medal evaluation against a {@link PlayerGunnerStats} snapshot (phase 5C).
 *
 * <p>Thresholds live only on {@link GunnerMedal}. Single-medal failures never
 * block other medals. Unlock order is always the fixed enum order.
 */
public final class GunnerMedalEvaluator {

    private GunnerMedalEvaluator() {
    }

    /**
     * Ordered medal display names as nested {@link Component#translatable}
     * nodes, joined by {@code tcth.gunner.medal.list_separator}.
     */
    public static Component joinMedalNames(List<GunnerMedal> medals) {
        MutableComponent names = Component.empty();
        if (medals == null || medals.isEmpty()) {
            return names;
        }
        for (int i = 0; i < medals.size(); i++) {
            if (i > 0) {
                names.append(Component.translatable("tcth.gunner.medal.list_separator"));
            }
            names.append(Component.translatable(medals.get(i).translationKey()));
        }
        return names;
    }

    /**
     * Unlocks every medal newly satisfied by {@code stats}, using
     * {@code unlockedAt} as the timestamp (live events use
     * {@code System.currentTimeMillis()}; silent migration uses {@code 0L}).
     *
     * @return newly unlocked medals in definition order (empty if none)
     */
    public static List<GunnerMedal> unlockNewlyMet(PlayerGunnerStats stats, long unlockedAt) {
        if (stats == null) {
            return List.of();
        }
        List<GunnerMedal> newly = new ArrayList<>(GunnerMedal.values().length);
        for (GunnerMedal medal : GunnerMedal.values()) {
            try {
                if (medal.isSatisfied(stats) && stats.tryUnlock(medal, unlockedAt)) {
                    newly.add(medal);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // One medal must never block the rest.
            }
        }
        return List.copyOf(newly);
    }

    /**
     * Silent reconcile for load / version migration: unlock missing medals
     * that current totals already satisfy, with {@code unlockedAt = 0}.
     *
     * @return {@code true} if any medal was added
     */
    public static boolean reconcileSilent(PlayerGunnerStats stats) {
        return !unlockNewlyMet(stats, 0L).isEmpty();
    }
}
