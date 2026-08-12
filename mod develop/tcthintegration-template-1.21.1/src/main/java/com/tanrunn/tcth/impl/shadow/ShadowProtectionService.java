package com.tanrunn.tcth.impl.shadow;

/**
 * Protection query used by the coordinator before any attempt proceeds.
 *
 * <p>Fail-closed contract (stage 8A §6, phase 8B):
 * <ul>
 *   <li>{@link ShadowProtectionResult#UNKNOWN} is always treated as a denial
 *       by the coordinator;</li>
 *   <li>an exception thrown by a service is also a denial;</li>
 *   <li>the phase-8B production default
 *       ({@link #denyAll()}) denies every attempt — real area protection
 *       (Open Parties and Claims etc.) is added by conditional compat modules
 *       in later phases and is never referenced here.</li>
 * </ul>
 */
public interface ShadowProtectionService {

    /**
     * @param context the immutable attempt context
     * @return the structured protection result
     */
    ShadowProtectionResult check(ShadowAttemptContext context);

    /**
     * @return a service that denies every attempt (production phase-8B
     *         default)
     */
    static ShadowProtectionService denyAll() {
        return DenyAllShadowProtectionService.INSTANCE;
    }
}
