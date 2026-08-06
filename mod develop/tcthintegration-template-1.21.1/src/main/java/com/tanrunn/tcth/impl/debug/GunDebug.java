package com.tanrunn.tcth.impl.debug;

/**
 * Lightweight, in-memory server-side debugging for gunner kill attribution
 * (phase 5A.2 review).
 *
 * <p><strong>Disabled by default.</strong> Never writes to config files; the
 * switch resets on server restart. When enabled via
 * {@code /tcth debug gunner on}, only <em>confirmed</em> gun kills are logged
 * (confirmation path, posted event, stats write) — there is no per-death
 * logging of unrelated entities, so a running mob farm cannot bloat the log.
 */
public final class GunDebug {

    private static volatile boolean enabled = false;

    private GunDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
