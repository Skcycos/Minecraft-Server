package com.tanrunn.tcth.impl.shadow;

import java.util.UUID;

/**
 * Write/read boundary for the per-victim daily item-loss quota with the
 * eventId-idempotent reservation protocol (8C.2.1 §2).
 *
 * <p>Implemented by {@link ShadowDailyLimitStore}; tests substitute an
 * in-memory fake. Fail-closed contract: storage-full, invalid dates, {@code
 * false} returns and exceptions all forbid the ITEM transfer.
 */
public interface ShadowDailyLimitWriter {

    /**
     * Result of a reservation attempt.
     */
    enum ReservationResult {
        /** The reservation is held (quota occupied). */
        RESERVED,
        /** The eventId already holds a COMMITTED reservation (idempotent). */
        COMMITTED_EXISTING,
        /** The victim has reached the daily cap. */
        LIMIT_REACHED,
        /** The reservation could not be made (storage full, invalid date,
         *  null inputs, internal error) — fail closed. */
        REJECTED
    }

    /**
     * Reserves one daily-ITEM slot for the victim, keyed by eventId.
     * Idempotent per eventId.
     *
     * @param victimId the victim's UUID
     * @param utcDay   the strictly-valid ISO UTC date string (captured once
     *                 per attempt)
     * @param eventId  the attempt id
     * @param limit    the configured daily cap (must be positive)
     */
    ReservationResult tryReserve(UUID victimId, String utcDay, UUID eventId, long limit);

    /**
     * Moves the reservation to COMMITTED (keeps occupying the quota).
     *
     * @return {@code false} when the eventId holds no RESERVED reservation
     */
    boolean commitReservation(UUID eventId);

    /**
     * Frees the quota occupied by the reservation.
     *
     * @return {@code false} when the eventId holds no reservation
     */
    boolean releaseReservation(UUID eventId);

    /**
     * Read-only quota check (used to prune ITEM from the pool pre-draw).
     *
     * @return {@code true} when the cap is reached OR the inputs are invalid
     *         (fail closed)
     */
    boolean isAtItemLimit(UUID victimId, String utcDay, long limit);
}
