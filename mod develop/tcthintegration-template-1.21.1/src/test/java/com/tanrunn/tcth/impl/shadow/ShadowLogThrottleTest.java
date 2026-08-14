package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Tests for {@link ShadowLogThrottle} (8D.3.1 §2): the first message passes
 * immediately, the 60 s window throttles per template, distinct templates are
 * independent, clock rollback passes through, and the long boundaries never
 * overflow.
 */
class ShadowLogThrottleTest {

    private final Logger counting = mock(Logger.class);

    private int logged() {
        return org.mockito.Mockito.mockingDetails(counting).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("warn")).count() > 0
                ? (int) org.mockito.Mockito.mockingDetails(counting).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("warn")).count()
                : 0;
    }

    @AfterEach
    void tearDown() {
        ShadowLogThrottle.resetForTesting();
    }

    @Test
    void firstMessageIsLoggedImmediately() {
        ShadowLogThrottle.setClockForTesting(() -> 1_000L);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        verify(counting, times(1)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void suppressedWithin59999Millis() {
        long[] now = { 0L };
        ShadowLogThrottle.setClockForTesting(() -> now[0]);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        now[0] = 59_999L;
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(1)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void passesAfter60000Millis() {
        long[] now = { 0L };
        ShadowLogThrottle.setClockForTesting(() -> now[0]);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        now[0] = 60_000L;
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void distinctTemplatesAreIndependent() {
        ShadowLogThrottle.setClockForTesting(() -> 1_000L);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        ShadowLogThrottle.warnOncePerMinute(counting, "template-b {}", "x");
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void clockRollbackPassesThrough() {
        long[] now = { 5_000L };
        ShadowLogThrottle.setClockForTesting(() -> now[0]);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        now[0] = 100L; // rollback
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void longMinAndMaxBoundariesDoNotOverflow() {
        long[] now = { Long.MIN_VALUE };
        ShadowLogThrottle.setClockForTesting(() -> now[0]);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        verify(counting, times(1)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        now[0] = 0L; // a negative stored clock is treated as never-seen
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        // Long.MAX_VALUE after a normal clock: full window → pass.
        now[0] = Long.MAX_VALUE;
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "z");
        verify(counting, times(3)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }


    // ---- 8D.3.2: atomicity & negative-time semantics ----

    @Test
    void concurrentSameTemplateLogsExactlyOnce() throws Exception {
        // 8D.3.2 §1: many threads racing on the same template must produce a
        // single log line (the atomic compute gate).
        ShadowLogThrottle.setClockForTesting(System::currentTimeMillis);
        ShadowLogThrottle.resetForTesting();
        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    ShadowLogThrottle.warnOncePerMinute(counting, "template-concurrent {}", idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        verify(counting, times(1)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void identicalNegativeClockValuesAreThrottled() {
        // 8D.3.2 §1: the SAME Long.MIN_VALUE twice must be throttled (zero
        // difference), not logged twice.
        ShadowLogThrottle.setClockForTesting(() -> Long.MIN_VALUE);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(1)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void negativeClockThenNormalClockPasses() {
        long[] now = { Long.MIN_VALUE };
        ShadowLogThrottle.setClockForTesting(() -> now[0]);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        now[0] = 1_000L; // a negative stored clock can never overflow now
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "y");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }


    @Test
    void resetClearsTheWholeCache() {
        ShadowLogThrottle.setClockForTesting(() -> 1_000L);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        ShadowLogThrottle.resetForTesting();
        ShadowLogThrottle.setClockForTesting(() -> 1_000L);
        ShadowLogThrottle.warnOncePerMinute(counting, "template-a {}", "x");
        verify(counting, times(2)).warn(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }
}
