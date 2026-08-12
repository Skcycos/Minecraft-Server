package com.tanrunn.tcth.impl.shadow;

import java.util.List;

/**
 * Production-safe default {@link ShadowCandidateProvider} (phase 8B).
 *
 * <p>Always returns an empty candidate list: without a real candidate the
 * coordinator can never draw a type, roll a success or invoke a transfer
 * executor — the framework stays inert even if every config switch is flipped
 * to {@code true}. Real candidate discovery is implemented in later phases.
 */
public final class EmptyShadowCandidateProvider implements ShadowCandidateProvider {

    public static final EmptyShadowCandidateProvider INSTANCE = new EmptyShadowCandidateProvider();

    private static final List<ShadowCandidate> EMPTY = List.of();

    private EmptyShadowCandidateProvider() {
    }

    @Override
    public List<ShadowCandidate> provide(ShadowAttemptContext context) {
        return EMPTY;
    }
}
