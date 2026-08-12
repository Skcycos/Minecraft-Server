package com.tanrunn.tcth.impl.shadow;

import java.util.List;

/**
 * Supplies the currently available theft-type candidates for an attempt.
 *
 * <p>The returned list may contain at most one candidate per
 * {@link ShadowTheftType}. The coordinator builds the draw pool from it and
 * prunes it further (e.g. the no-op phase-8B default returns nothing at all,
 * so no real theft can ever be attempted).
 *
 * <p><b>Phase 8B:</b> the production default implementation
 * ({@link EmptyShadowCandidateProvider}) always returns an empty list — real
 * candidate discovery belongs to later phases.
 */
public interface ShadowCandidateProvider {

    /**
     * @param context the immutable attempt context
     * @return the currently available candidates; never null
     */
    List<ShadowCandidate> provide(ShadowAttemptContext context);
}
