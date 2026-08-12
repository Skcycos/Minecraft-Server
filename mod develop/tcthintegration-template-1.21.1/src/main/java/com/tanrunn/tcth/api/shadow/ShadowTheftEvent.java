package com.tanrunn.tcth.api.shadow;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Fired on {@code NeoForge.EVENT_BUS} (game bus) whenever a shadow theft
 * attempt has reached its final outcome.
 *
 * <p>This is TCTH's unified, <em>read-only</em> shadow-theft result event. It
 * is produced by the attempt coordinator after the transfer transaction (if
 * any) and the audit record (if any) have been settled, and it is posted by
 * the event dispatcher.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The event is <strong>not cancellable</strong> and carries no
 *       {@code settled}/{@code rewarded}/{@code cancelled} state: listeners
 *       cannot modify the transaction outcome. Reward systems must only react
 *       to {@link ShadowTheftOutcome#SUCCESS}.</li>
 *   <li>{@link #getEventId()} is generated once at the start of the attempt
 *       and stays constant for the whole attempt — the audit record, the
 *       event and the coordinator result all share it.</li>
 *   <li>{@link #getReceipt()} describes what was moved (or that nothing was)
 *       and is immutable; it never holds full {@code ItemStack}s, NBT,
 *       components or account objects. The constructor enforces the
 *       outcome/receipt invariants: only {@code SUCCESS} and
 *       {@code RECOVERY_REQUIRED} may carry a non-empty receipt, and it must
 *       match the drawn {@code theftType}.</li>
 *   <li>{@link #getPosition()} is an immutable {@code BlockPos} value.</li>
 * </ul>
 *
 * <p><b>Stability:</b> TCTH is in pre-release (0.x); this event's fields and
 * methods may change without notice until 1.0.0. See the API stability
 * statement in {@code com.tanrunn.tcth.api}.
 */
public class ShadowTheftEvent extends Event {

    private final UUID eventId;
    private final ServerPlayer thief;
    private final ShadowTargetKind targetKind;
    private final UUID targetId;
    @Nullable
    private final ResourceLocation targetType;
    @Nullable
    private final ShadowTheftType theftType;
    private final ShadowTheftOutcome outcome;
    private final ShadowTheftReceipt receipt;
    private final boolean automated;
    private final ServerLevel level;
    @Nullable
    private final BlockPos position;

    /**
     * @param eventId    unique id of the attempt; generated once per attempt
     *                   and shared with the audit record; must not be null
     * @param thief      the server player performing the theft; must not be
     *                   null (automated attempts are refused by the framework)
     * @param targetKind whether the target is a player or an entity
     * @param targetId   the UUID of the target entity/player
     * @param targetType the entity type id of the target, or {@code null} for
     *                   player targets
     * @param theftType  the drawn theft type, or {@code null} when the attempt
     *                   never drew one (pre-draw failures)
     * @param outcome    the final outcome; must not be null
     * @param receipt    what was moved (empty for non-asset outcomes); must not
     *                   be null
     * @param automated  reserved for future mechanical actors; real production
     *                   attempts are always {@code false}
     * @param level      the server level the attempt happened in; must not be
     *                   null
     * @param position   the block position of the attempt, or {@code null}
     * @throws NullPointerException if {@code eventId}, {@code thief},
     *                              {@code targetKind}, {@code targetId},
     *                              {@code outcome}, {@code receipt} or
     *                              {@code level} is null
     * @throws IllegalArgumentException if the outcome/receipt/theftType triple
     *                                  violates the API invariants:
     *                                  {@code SUCCESS} requires a non-null
     *                                  {@code theftType} and a receipt that
     *                                  {@link ShadowTheftReceipt#matches
     *                                  matches} it; {@code RECOVERY_REQUIRED}
     *                                  may carry a committed receipt (which
     *                                  then must match a non-null theftType);
     *                                  every other outcome requires an empty
     *                                  receipt; {@code ROLLED_BACK} and all
     *                                  non-asset outcomes require an empty
     *                                  receipt
     */
    public ShadowTheftEvent(UUID eventId, ServerPlayer thief, ShadowTargetKind targetKind, UUID targetId,
                            @Nullable ResourceLocation targetType, @Nullable ShadowTheftType theftType,
                            ShadowTheftOutcome outcome, ShadowTheftReceipt receipt, boolean automated,
                            ServerLevel level, @Nullable BlockPos position) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.thief = Objects.requireNonNull(thief, "thief");
        this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.targetType = targetType;
        this.theftType = theftType;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.automated = automated;
        this.level = Objects.requireNonNull(level, "level");
        this.position = position == null ? null : position.immutable();
        validateInvariants();
    }

    /**
     * Outcome/receipt/theftType invariants (8B.1 §5):
     * <ul>
     *   <li>{@code SUCCESS}: theftType non-null and receipt.matches(theftType);</li>
     *   <li>{@code RECOVERY_REQUIRED}: may carry a committed receipt; a
     *       non-empty receipt requires a non-null theftType it matches;</li>
     *   <li>every other outcome ({@code ROLLED_BACK} included): receipt must
     *       be empty;</li>
     *   <li>outcomes that never drew a type may leave theftType null; drawn
     *       failures may keep it, with an empty receipt.</li>
     * </ul>
     */
    private void validateInvariants() {
        switch (outcome) {
            case SUCCESS -> {
                if (theftType == null) {
                    throw new IllegalArgumentException("theftType is required for SUCCESS");
                }
                if (!receipt.matches(theftType)) {
                    throw new IllegalArgumentException(
                            "receipt must match theftType for SUCCESS: " + receipt + " vs " + theftType);
                }
            }
            case RECOVERY_REQUIRED -> {
                if (!receipt.isEmpty()) {
                    if (theftType == null || !receipt.matches(theftType)) {
                        throw new IllegalArgumentException(
                                "a committed RECOVERY_REQUIRED receipt must match a non-null theftType");
                    }
                }
            }
            default -> {
                if (!receipt.isEmpty()) {
                    throw new IllegalArgumentException(
                            "receipt must be empty for outcome " + outcome);
                }
            }
        }
    }

    /**
     * @return the attempt id; constant for the whole attempt and shared with
     *         the audit record
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * @return the player performing the theft
     */
    public ServerPlayer getThief() {
        return thief;
    }

    /**
     * @return whether the target is a player or an entity
     */
    public ShadowTargetKind getTargetKind() {
        return targetKind;
    }

    /**
     * @return the UUID of the target entity/player
     */
    public UUID getTargetId() {
        return targetId;
    }

    /**
     * @return the entity type id of the target, or {@code null} for player
     *         targets
     */
    @Nullable
    public ResourceLocation getTargetType() {
        return targetType;
    }

    /**
     * @return the drawn theft type, or {@code null} when the attempt never
     *         drew one
     */
    @Nullable
    public ShadowTheftType getTheftType() {
        return theftType;
    }

    /**
     * @return the final outcome
     */
    public ShadowTheftOutcome getOutcome() {
        return outcome;
    }

    /**
     * @return what was moved; empty for every non-{@code SUCCESS} outcome
     */
    public ShadowTheftReceipt getReceipt() {
        return receipt;
    }

    /**
     * @return {@code true} for mechanical actors; real production attempts
     *         are never automated
     */
    public boolean isAutomated() {
        return automated;
    }

    /**
     * @return the server level the attempt happened in
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * @return the block position of the attempt, or {@code null}
     */
    @Nullable
    public BlockPos getPosition() {
        return position;
    }
}
