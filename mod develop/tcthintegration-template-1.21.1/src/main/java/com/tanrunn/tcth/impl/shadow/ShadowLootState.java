package com.tanrunn.tcth.impl.shadow;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Authoritative per-entity "shadow loot" state carried as a NeoForge entity
 * attachment (8D.1).
 *
 * <p>State machine (8D.1 §2):
 * <ul>
 *   <li>{@link State#AVAILABLE} — default; the entity may be looted again
 *       (not actually written to the attachment);</li>
 *   <li>{@link State#PENDING} — a loot attempt is in flight (eventId,
 *       thiefUuid, startedAt); further attempts are blocked;</li>
 *   <li>{@link State#LOOTED} — the entity was looted exactly once (eventId,
 *       itemId, count, completedAt); further attempts are blocked;</li>
 *   <li>{@link State#CORRUPT} — the stored payload failed strict validation;
 *       ALL attempts are blocked (fail-closed).</li>
 * </ul>
 *
 * <p>This attachment is the ONLY authority for "may this entity be looted
 * again". It is persisted with the entity NBT (save/load chain via
 * {@code Entity.saveWithoutId} / {@code load}, NeoForge patch) and survives
 * chunk unload, restart and dimension travel ({@code restoreFrom} copies
 * serializable attachments). The audit log stays in {@link ShadowAuditStore}.
 */
public record ShadowLootState(State state, @Nullable UUID eventId, @Nullable UUID thiefUuid,
                              long startedAt, @Nullable ResourceLocation itemId, int count,
                              long completedAt) {

    public enum State {
        AVAILABLE, PENDING, LOOTED, CORRUPT
    }

    public ShadowLootState {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        switch (state) {
            case AVAILABLE, CORRUPT -> {
                if (eventId != null || thiefUuid != null || itemId != null || count != 0
                        || startedAt != 0L || completedAt != 0L) {
                    throw new IllegalArgumentException(state + " must carry no payload");
                }
            }
            case PENDING -> {
                if (eventId == null || thiefUuid == null || startedAt <= 0L) {
                    throw new IllegalArgumentException(
                            "PENDING requires eventId, thiefUuid, startedAt>0 (8D.1.1)");
                }
                if (itemId != null || count != 0 || completedAt != 0L) {
                    throw new IllegalArgumentException("PENDING must not carry loot fields");
                }
            }
            case LOOTED -> {
                if (eventId == null || itemId == null || count <= 0 || count > 4
                        || completedAt <= 0L) {
                    throw new IllegalArgumentException(
                            "LOOTED requires eventId, itemId, count(1..4), completedAt>0 (8D.1.1)");
                }
                if (thiefUuid != null || startedAt != 0L) {
                    throw new IllegalArgumentException("LOOTED must not carry pending fields");
                }
            }
        }
    }

    public static ShadowLootState available() {
        return new ShadowLootState(State.AVAILABLE, null, null, 0L, null, 0, 0L);
    }

    public static ShadowLootState pending(UUID eventId, UUID thiefUuid, long startedAt) {
        return new ShadowLootState(State.PENDING, eventId, thiefUuid, startedAt, null, 0, 0L);
    }

    public static ShadowLootState looted(UUID eventId, ResourceLocation itemId, int count,
                                         long completedAt) {
        return new ShadowLootState(State.LOOTED, eventId, null, 0L, itemId, count, completedAt);
    }

    /** The fixed count bound (8D.1.1 §4): 1..4. */
    public static final int MAX_COUNT = 4;

    public static ShadowLootState corrupt() {
        return new ShadowLootState(State.CORRUPT, null, null, 0L, null, 0, 0L);
    }

    /** Whether this state forbids another loot attempt (8D.1 §2). */
    public boolean blocksTheft() {
        return state != State.AVAILABLE;
    }
}
