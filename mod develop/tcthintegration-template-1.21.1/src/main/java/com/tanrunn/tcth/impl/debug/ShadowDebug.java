package com.tanrunn.tcth.impl.debug;

/**
 * Lightweight, in-memory server-side debugging for the shadow thief framework
 * (phase 8C.0).
 *
 * <p><strong>Disabled by default.</strong> Never writes to config files; the
 * switch resets on server restart. When enabled via
 * {@code /tcth debug shadow on}, the attempt coordinator logs one line per
 * attempt with only: eventId, outcome, drawn candidate type, protection
 * result and failure reason. It never logs inventory contents, effect details
 * or balances.
 */
public final class ShadowDebug {

    private static volatile boolean enabled = false;

    private ShadowDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
