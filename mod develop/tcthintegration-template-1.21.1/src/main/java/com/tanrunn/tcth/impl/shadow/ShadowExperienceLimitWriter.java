package com.tanrunn.tcth.impl.shadow;

import java.util.UUID;

/**
 * Write/read boundary for the per-(thief, target)-pair daily job-experience
 * quota with the eventId-idempotent reservation protocol (phase 8E §8).
 *
 * <p>Implemented by {@link ShadowExperienceLimitStore}; tests substitute an
 * in-memory fake. Fail-closed contract: storage-full, invalid dates, {@code
 * false} returns and exceptions all forbid the XP reward.
 */
public interface ShadowExperienceLimitWriter {

    /**
     * Result of a reservation attempt.
     */
    enum ReservationResult {
        /** The reservation is held (quota occupied). */
        RESERVED,
        /** The eventId already holds a COMMITTED reservation (idempotent). */
        COMMITTED_EXISTING,
        /** The eventId holds a RECOVERY reservation loaded from disk — the
         *  outcome of a pre-restart send is UNKNOWN, so the slot stays
         *  occupied and the reward must NOT be sent again (conservative). */
        RECOVERY_EXISTING,
        /** The pair has reached the daily cap. */
        LIMIT_REACHED,
        /** The reservation could not be made (storage full, invalid date,
         *  null inputs, internal error) — fail closed. */
        REJECTED
    }

    /**
     * Reserves one daily XP slot for the (thief, target) pair, keyed by
     * eventId. Idempotent per eventId.
     *
     * @param thiefId  the thief's UUID
     * @param targetId the target's UUID (player targets only)
     * @param utcDay   the strictly-valid ISO UTC date string (captured once
     *                 per attempt)
     * @param eventId  the attempt id
     * @param limit    the configured daily cap (must be positive)
     */
    ReservationResult tryReserve(UUID thiefId, UUID targetId, String utcDay, UUID eventId, long limit);

    /**
     * Moves the reservation to COMMITTED (keeps occupying the quota). Only a
     * same-JVM {@code RESERVED} reservation may be committed; a
     * {@code COMMITTED} or {@code RECOVERY} entry is refused.
     *
     * @return {@code false} when the eventId holds no RESERVED reservation
     */
    boolean commitReservation(UUID eventId);

    /**
     * Frees the quota occupied by the reservation. Only a same-JVM
     * {@code RESERVED} reservation (a clearly failed Arc send) may be
     * released; {@code COMMITTED} and {@code RECOVERY} entries are never
     * released through this path — an unknown outcome must keep the quota
     * occupied.
     *
     * @return {@code false} when the eventId holds no releaseable
     *         ({@code RESERVED}) reservation
     */
    boolean releaseReservation(UUID eventId);

    /**
     * Read-only quota check (used to skip the XP send pre-reserve).
     *
     * @return {@code true} when the cap is reached OR the inputs are invalid
     *         (fail closed)
     */
    boolean isAtPairLimit(UUID thiefId, UUID targetId, String utcDay, long limit);
}
