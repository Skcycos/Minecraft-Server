package com.tanrunn.tcth.impl.shadow;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

/**
 * Throttled warning helper for the shadow theft framework (8D.3.2 §1).
 *
 * <p>Frequent framework-level failures must never spam the log: at most one
 * WARN per message template per 60 seconds. The cache is keyed by the message
 * FORMAT (pre-argument), so distinct templates never suppress each other.
 * Because callers only ever pass code-fixed format strings, the cache is
 * naturally bounded.
 *
 * <p>Atomicity: the decision is made inside {@code ConcurrentHashMap.compute}
 * — concurrent callers of the SAME template race on one atomic update and
 * exactly one of them observes the recorded timestamp and logs. The
 * {@code logger.warn} itself runs AFTER the atomic decision.
 *
 * <p>Time semantics: the subtraction uses {@link Math#subtractExact}; a
 * subtraction overflow is treated as a full window (pass through, never
 * crash); a clock rollback ({@code now < last}) passes through and resets;
 * identical consecutive clock values (e.g. the same {@code Long.MIN_VALUE})
 * yield a zero difference and are THROTTLED.
 */
public final class ShadowLogThrottle {

    /** Throttle window in milliseconds. */
    public static final long WARN_WINDOW_MILLIS = 60_000L;

    private static final java.util.Map<String, Long> lastWarnByFormat = new ConcurrentHashMap<>();
    private static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    private ShadowLogThrottle() {
    }

    /**
     * Logs the message at WARN level at most once per 60 seconds per format.
     *
     * @param logger the logger to use
     * @param format the SLF4J message format (fixed code template)
     * @param args   the message arguments
     */
    public static void warnOncePerMinute(Logger logger, String format, Object... args) {
        long now = clock.getAsLong();
        boolean[] pass = { false };
        // The decision runs inside the atomic compute — concurrent callers of
        // the SAME template race on one update; exactly one observes the pass
        // flag. The logger runs AFTER the atomic decision.
        lastWarnByFormat.compute(format, (key, last) -> {
            if (last == null) {
                pass[0] = true; // first message → pass
                return now;
            }
            long diff;
            try {
                diff = Math.subtractExact(now, last);
            } catch (ArithmeticException e) {
                diff = Long.MAX_VALUE; // cannot express safely → full window
            }
            if (now < last) {
                pass[0] = true; // clock rollback → pass and reset
                return now;
            }
            if (diff >= WARN_WINDOW_MILLIS) {
                pass[0] = true; // full window elapsed → pass
                return now;
            }
            return last; // throttled: keep the old timestamp, do NOT pass
        });
        if (pass[0]) {
            logger.warn(format, args);
        }
    }

    // ---- test hooks ----

    public static void setClockForTesting(java.util.function.LongSupplier clock) {
        ShadowLogThrottle.clock = clock;
    }

    /** Fully clears the per-format cache and restores the real clock. */
    public static void resetForTesting() {
        lastWarnByFormat.clear();
        clock = System::currentTimeMillis;
    }
}
