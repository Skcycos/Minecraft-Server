package com.tanrunn.tcth.impl.shadow;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Write/read boundary for the shadow theft audit log (8B.1).
 *
 * <p>Implemented by {@link ShadowAuditStore} (a SavedData persisted in the
 * overworld's data storage). The attempt coordinator only depends on this
 * interface so tests can substitute failing fake stores to verify the
 * {@code AUDIT_FAILED} / rollback boundaries.
 *
 * <p>{@link #append} is a <em>guarded upsert</em> (8B.1.1 §5): a new eventId
 * accepts a PENDING or a FINAL record; an existing PENDING may only become a
 * FINAL record (the pre-write finalisation); an existing FINAL only accepts a
 * byte-identical idempotent re-write. Every other transition is refused and
 * returns {@code false} without touching the stored record.
 */
public interface ShadowAuditWriter {

    /**
     * Inserts or replaces a record keyed by its eventId. Returns
     * {@code false} when the record could not be written (never throws for
     * storage-level problems).
     *
     * @param record the immutable record to store
     * @return {@code true} when the record was accepted
     */
    boolean append(ShadowAuditRecord record);

    /**
     * @param eventId the attempt id
     * @return the stored record with the given eventId, or {@code null}
     */
    @Nullable
    ShadowAuditRecord byEventId(UUID eventId);

    /**
     * @param eventId the attempt id
     * @return whether a record with the given eventId exists
     */
    boolean has(UUID eventId);

    /**
     * @param thiefId the thief's UUID
     * @return an immutable snapshot of all records involving the thief
     */
    List<ShadowAuditRecord> byThief(UUID thiefId);

    /**
     * @param targetId the target's UUID
     * @return an immutable snapshot of all records involving the target
     */
    List<ShadowAuditRecord> byTarget(UUID targetId);

    /**
     * @return an immutable snapshot of all records
     */
    List<ShadowAuditRecord> all();

    /**
     * Read-only health probe (8C.2.4 §2): {@code false} when the store is in
     * a persisted fail-closed / damaged / saturated state. A coordinator
     * MUST refuse the attempt with {@code AUDIT_FAILED} before the candidate
     * pool, the date source, any random call and the executor.
     */
    boolean isHealthy();
}
