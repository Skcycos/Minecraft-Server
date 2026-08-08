package com.tanrunn.tcth.impl.compat.cooking;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe suppression state coordinating the Shift-click take-out path
 * between a menu mixin and the result-slot mixin.
 *
 * <p>When a Shift-click runs, {@code quickMoveStack} moves the meal and then
 * still invokes {@code ResultSlot.onTake} (with a stack whose count changed).
 * Without coordination the result-slot mixin would sign/publish a second
 * time. The menu mixin therefore marks this menu as "shift-take in progress"
 * for the duration of the call; the result-slot mixin checks
 * {@link #isSuppressed} and skips its own sign/publish. The menu mixin clears
 * the mark on its {@code RETURN} injection; on an in-flight exception the
 * mark is not guaranteed to be cleared and is reset by the next
 * {@code quickMoveStack} HEAD (known limitation, see the menu mixin). The
 * per-invocation token pattern used for the normal path is:
 *
 * <pre>{@code
 * ShiftTakeToken token = ShiftTakeSuppression.enter(menu);
 * try {
 *     // run the guarded call ...
 * } finally {
 *     token.close(); // clears this menu's mark on the normal path
 * }
 * }</pre>
 *
 * <p>Keys are the menu instances, so concurrent players on different menus do
 * not interfere; the same menu instance cannot be re-entered concurrently
 * (a server player drives one menu at a time).
 */
public final class ShiftTakeSuppression {

    private static final Set<Object> SUPPRESSED = ConcurrentHashMap.newKeySet();

    private ShiftTakeSuppression() {
    }

    /** Marks the menu as shift-taking; returns a token that must be closed. */
    public static ShiftTakeToken enter(Object menu) {
        SUPPRESSED.add(menu);
        return new ShiftTakeToken(menu);
    }

    /** True while the given menu is inside a guarded Shift-click call. */
    public static boolean isSuppressed(Object menu) {
        return SUPPRESSED.contains(menu);
    }

    /** Test hook: clears all suppression marks. */
    public static void resetForTesting() {
        SUPPRESSED.clear();
    }

    /** Closes a suppression mark, clearing the menu on close. */
    public static final class ShiftTakeToken implements AutoCloseable {
        private final Object menu;
        private boolean closed = false;

        private ShiftTakeToken(Object menu) {
            this.menu = menu;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                SUPPRESSED.remove(menu);
            }
        }
    }
}
