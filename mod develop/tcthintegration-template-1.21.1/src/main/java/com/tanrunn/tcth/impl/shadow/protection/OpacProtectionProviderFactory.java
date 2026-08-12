package com.tanrunn.tcth.impl.shadow.protection;

import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.shadow.ShadowAreaProtectionProvider;

/**
 * String-isolated factory for the Open Parties and Claims area provider
 * (phase 8C.0).
 *
 * <p>The OPAC mod id and the provider class name live here as plain strings;
 * the provider class is only resolved when the mod is actually present (same
 * pattern as {@link CompatLoader}). When OPAC is missing, or the class cannot
 * be constructed, {@link #create()} returns {@code null} — the composite
 * protection service then fails closed to {@code DENIED_AREA}.
 */
public final class OpacProtectionProviderFactory {

    private static final String MOD_ID = "openpartiesandclaims";
    private static final String PROVIDER_CLASS =
            "com.tanrunn.tcth.impl.shadow.protection.OpacProtectionProvider";

    private OpacProtectionProviderFactory() {
    }

    /**
     * @return the OPAC area provider, or {@code null} when OPAC is absent or
     *         the provider cannot be constructed (fail-closed upstream)
     */
    public static ShadowAreaProtectionProvider create() {
        if (!CompatLoader.isModLoaded(MOD_ID)) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(PROVIDER_CLASS);
            return (ShadowAreaProtectionProvider) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }
}
