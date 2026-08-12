package com.tanrunn.tcth.impl.shadow;

/**
 * Extension point for area-based protection (claims, regions, spawn
 * protection, new-player protection).
 *
 * <p><b>Phase 8B:</b> only the deny-all default exists. A future conditional
 * compat module (e.g. an Open Parties and Claims provider) implements this
 * interface and supplies it to {@link ShadowBuiltinProtectionService}. This
 * framework code never references any claim/region mod.
 */
public interface ShadowAreaProtectionProvider {

    /**
     * @param context the immutable attempt context
     * @return the structured area-protection result
     */
    ShadowProtectionResult checkArea(ShadowAttemptContext context);

    /**
     * @return a provider that denies every area (production default)
     */
    static ShadowAreaProtectionProvider denyAll() {
        return context -> ShadowProtectionResult.DENIED_AREA;
    }
}
