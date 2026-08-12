package com.tanrunn.tcth.impl.shadow;

/**
 * Production-safe default {@link ShadowProtectionService} (phase 8B).
 *
 * <p>Denies every attempt with {@link ShadowProtectionResult#DENIED_AREA}:
 * without real area protection wired in, no shadow theft can ever proceed
 * even if every config switch is flipped to {@code true}. Later phases
 * provide real area providers through conditional compat modules (Open
 * Parties and Claims etc.) without touching this class.
 */
public final class DenyAllShadowProtectionService implements ShadowProtectionService {

    public static final DenyAllShadowProtectionService INSTANCE = new DenyAllShadowProtectionService();

    private DenyAllShadowProtectionService() {
    }

    @Override
    public ShadowProtectionResult check(ShadowAttemptContext context) {
        return ShadowProtectionResult.DENIED_AREA;
    }
}
