package com.tanrunn.tcth.impl.shadow;

import org.slf4j.Logger;

/**
 * Throttled warning helper for the shadow theft framework.
 *
 * <p>Frequent framework-level failures (protection exceptions, executor
 * failures, audit failures) must never spam the log: at most one WARN per
 * message site per 60 seconds. The clock is injectable for tests.
 */
public final class ShadowLogThrottle {

    /** Throttle window in milliseconds. */
    public static final long WARN_WINDOW_MILLIS = 60_000L;

    private static volatile long lastWarnMillis = Long.MIN_VALUE;
    private static java.util.function.LongSupplier clock = System::currentTimeMillis;

    private ShadowLogThrottle() {
    }

    /**
     * Logs the message at WARN level at most once per 60 seconds.
     *
     * @param logger the logger to use
     * @param format the SLF4J message format
     * @param args   the message arguments
     */
    public static void warnOncePerMinute(Logger logger, String format, Object... args) {
        long now = clock.getAsLong();
        if (now - lastWarnMillis >= WARN_WINDOW_MILLIS) {
            lastWarnMillis = now;
            logger.warn(format, args);
        }
    }

    // ---- test hooks ----

    public static void setClockForTesting(java.util.function.LongSupplier clock) {
        ShadowLogThrottle.clock = clock;
    }

    public static void resetForTesting() {
        lastWarnMillis = Long.MIN_VALUE;
        clock = System::currentTimeMillis;
    }
}
