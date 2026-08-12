package com.tanrunn.tcth.impl.shadow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.tanrunn.tcth.api.shadow.ShadowTheftType;

import net.minecraft.util.RandomSource;

/**
 * The candidate pool used by the attempt coordinator to draw exactly one
 * theft type per attempt.
 *
 * <p>Rules (stage 8A design, phase 8B implementation):
 * <ul>
 *   <li>the pool only ever contains candidates that are <em>currently
 *       available</em> — the coordinator prunes unavailable types before
 *       drawing, so the remaining weights are renormalised naturally;</li>
 *   <li>default weights: ITEM 30, COIN 20, HEALTH 20, HUNGER 15, EFFECT 15;</li>
 *   <li>non-positive weights are rejected ({@link IllegalArgumentException});</li>
 *   <li>the weight sum is computed in {@code long} so huge weights cannot
 *       overflow;</li>
 *   <li>an empty pool never touches the random source
 *       ({@link #draw(RandomSource)} returns {@code null} without calling
 *       it);</li>
 *   <li>{@link #draw(RandomSource)} calls the random source exactly once and
 *       returns the drawn candidate; a later transaction failure must never
 *       cause a second draw (guaranteed by the coordinator, enforced by
 *       tests).</li>
 * </ul>
 */
public final class ShadowCandidatePool {

    /** Default draw weight of the ITEM type (stage 8A). */
    public static final int DEFAULT_ITEM_WEIGHT = 30;
    /** Default draw weight of the COIN type (stage 8A). */
    public static final int DEFAULT_COIN_WEIGHT = 20;
    /** Default draw weight of the HEALTH type (stage 8A). */
    public static final int DEFAULT_HEALTH_WEIGHT = 20;
    /** Default draw weight of the HUNGER type (stage 8A). */
    public static final int DEFAULT_HUNGER_WEIGHT = 15;
    /** Default draw weight of the EFFECT type (stage 8A). */
    public static final int DEFAULT_EFFECT_WEIGHT = 15;

    private final Map<ShadowTheftType, ShadowCandidate> candidates;

    private ShadowCandidatePool(Map<ShadowTheftType, ShadowCandidate> candidates) {
        this.candidates = candidates;
    }

    /**
     * @return an empty pool (no candidates, no draw possible)
     */
    public static ShadowCandidatePool empty() {
        return new ShadowCandidatePool(new LinkedHashMap<>());
    }

    /**
     * @return a pool containing all five theft types with their default
     *         weights (stage 8A: 30/20/20/15/15)
     */
    public static ShadowCandidatePool defaults() {
        return empty()
                .with(ShadowCandidate.plain(ShadowTheftType.ITEM, DEFAULT_ITEM_WEIGHT))
                .with(ShadowCandidate.plain(ShadowTheftType.COIN, DEFAULT_COIN_WEIGHT))
                .with(ShadowCandidate.plain(ShadowTheftType.HEALTH, DEFAULT_HEALTH_WEIGHT))
                .with(ShadowCandidate.plain(ShadowTheftType.HUNGER, DEFAULT_HUNGER_WEIGHT))
                .with(ShadowCandidate.plain(ShadowTheftType.EFFECT, DEFAULT_EFFECT_WEIGHT));
    }

    /**
     * @return a new pool with the given candidate added or replacing an
     *         existing candidate of the same type
     */
    public ShadowCandidatePool with(ShadowCandidate candidate) {
        Map<ShadowTheftType, ShadowCandidate> copy = new LinkedHashMap<>(candidates);
        copy.put(candidate.type(), candidate);
        return new ShadowCandidatePool(copy);
    }

    /**
     * @return a new pool with the given type removed (used for pruning
     *         unavailable types before the draw)
     */
    public ShadowCandidatePool without(ShadowTheftType type) {
        Objects.requireNonNull(type, "type");
        Map<ShadowTheftType, ShadowCandidate> copy = new LinkedHashMap<>(candidates);
        copy.remove(type);
        return new ShadowCandidatePool(copy);
    }

    /**
     * @return {@code true} if the pool contains no candidates
     */
    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    /**
     * @return the number of candidates in the pool
     */
    public int size() {
        return candidates.size();
    }

    /**
     * @return whether the pool contains the given type
     */
    public boolean contains(ShadowTheftType type) {
        return candidates.containsKey(type);
    }

    /**
     * @return the total weight of all candidates, computed in {@code long}
     */
    public long totalWeight() {
        long total = 0L;
        for (ShadowCandidate candidate : candidates.values()) {
            total += candidate.weight();
        }
        // Overflow safety: a wrapped (negative) sum saturates instead of
        // producing a broken draw bound.
        return total < 0L ? Long.MAX_VALUE : total;
    }

    /**
     * Draws exactly one candidate from the pool.
     *
     * <p>Calling the random source exactly once with a bound equal to the
     * total weight, then walking the candidates in insertion order. When the
     * pool is empty this method returns {@code null} and never touches the
     * random source.
     *
     * @param random the random source (server random in production, a
     *               deterministic/counting source in tests)
     * @return the drawn candidate, or {@code null} for an empty pool
     */
    public ShadowCandidate draw(RandomSource random) {
        Objects.requireNonNull(random, "random");
        if (candidates.isEmpty()) {
            return null;
        }
        // RandomSource (1.21.1) has no bounded nextLong(long); floorMod of the
        // full-range nextLong() over the total weight yields [0, total) with
        // negligible bias for game-sized weights — and still exactly one call.
        long roll = Math.floorMod(random.nextLong(), totalWeight());
        long cumulative = 0L;
        for (ShadowCandidate candidate : candidates.values()) {
            cumulative += candidate.weight();
            if (roll < cumulative) {
                return candidate;
            }
        }
        // Floating-point safety net: roll at the very end of the range. Never
        // happens with a correct bounded draw, but return the last candidate
        // instead of null so an attempt can still be produced.
        return candidates.values().iterator().hasNext() ? lastCandidate() : null;
    }

    private ShadowCandidate lastCandidate() {
        ShadowCandidate last = null;
        for (ShadowCandidate candidate : candidates.values()) {
            last = candidate;
        }
        return last;
    }
}
