package com.tanrunn.tcth.impl.shadow;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory fake of {@link ShadowDailyLimitWriter} for unit tests, mirroring
 * the store's reservation protocol: RESERVED and COMMITTED entries both
 * occupy the quota; release frees it; eventId is idempotent.
 */
public final class FakeDailyLimits implements ShadowDailyLimitWriter {

    private enum State {
        RESERVED, COMMITTED
    }

    private record ReservationEntry(UUID victimId, String utcDay, State state) {
    }

    /** eventId → reservation. */
    private final Map<UUID, ReservationEntry> reservations = new LinkedHashMap<>();
    /** victim → number of COMMITTED entries (survives the fake's lifetime). */
    private final Map<UUID, Integer> committed = new HashMap<>();
    private boolean failNextCommit;
    private boolean throwNextCommit;

    public FakeDailyLimits() {
    }

    /** The next commitReservation returns {@code false} (storage failure). */
    public void failNextCommit() {
        this.failNextCommit = true;
    }

    /** The next commitReservation throws (storage failure). */
    public void throwNextCommit() {
        this.throwNextCommit = true;
    }

    @Override
    public ReservationResult tryReserve(UUID victimId, String utcDay, UUID eventId, long limit) {
        if (victimId == null || eventId == null || limit <= 0L
                || utcDay == null || utcDay.isEmpty()) {
            return ReservationResult.REJECTED; // fail closed
        }
        ReservationEntry existing = reservations.get(eventId);
        if (existing != null) {
            if (!existing.victimId().equals(victimId)
                    || !existing.utcDay().equals(utcDay)) {
                return ReservationResult.REJECTED;
            }
            return existing.state() == State.COMMITTED
                    ? ReservationResult.COMMITTED_EXISTING
                    : ReservationResult.RESERVED;
        }
        if (occupiedCount(victimId) >= limit) {
            return ReservationResult.LIMIT_REACHED;
        }
        reservations.put(eventId, new ReservationEntry(victimId, utcDay, State.RESERVED));
        return ReservationResult.RESERVED;
    }

    @Override
    public boolean commitReservation(UUID eventId) {
        if (failNextCommit) {
            failNextCommit = false;
            return false;
        }
        if (throwNextCommit) {
            throwNextCommit = false;
            throw new IllegalStateException("fake storage boom");
        }
        ReservationEntry entry = reservations.get(eventId);
        if (entry == null || entry.state() != State.RESERVED) {
            return false;
        }
        reservations.put(eventId, new ReservationEntry(entry.victimId(), entry.utcDay(), State.COMMITTED));
        committed.merge(entry.victimId(), 1, Integer::sum);
        return true;
    }

    @Override
    public boolean releaseReservation(UUID eventId) {
        ReservationEntry entry = reservations.remove(eventId);
        if (entry == null) {
            return false;
        }
        if (entry.state() == State.COMMITTED) {
            committed.merge(entry.victimId(), -1, Integer::sum);
        }
        return true;
    }

    @Override
    public boolean isAtItemLimit(UUID victimId, String utcDay, long limit) {
        if (victimId == null || limit <= 0L) {
            return true; // fail closed
        }
        return occupiedCount(victimId) >= limit;
    }

    /** Total quota occupied by the victim (RESERVED + COMMITTED). COMMITTED
     *  entries are counted once via the settled map; only still-RESERVED
     *  entries add via the index (mirrors the store's single counter). */
    public int occupiedCount(UUID victimId) {
        int count = committed.getOrDefault(victimId, 0);
        for (ReservationEntry e : reservations.values()) {
            if (e.victimId().equals(victimId) && e.state() == State.RESERVED) {
                count++;
            }
        }
        return count;
    }

    /** Number of COMMITTED entries for the victim (a successful theft). */
    public int itemLossCount(UUID victimId) {
        return committed.getOrDefault(victimId, 0);
    }

    /** Seeds COMMITTED entries directly (pre-filled quota for tests). */
    public void setCount(UUID victimId, int count) {
        committed.put(victimId, count);
    }

    /** Total outstanding reservations (RESERVED + COMMITTED). */
    public int totalEntries() {
        return reservations.size();
    }
}
