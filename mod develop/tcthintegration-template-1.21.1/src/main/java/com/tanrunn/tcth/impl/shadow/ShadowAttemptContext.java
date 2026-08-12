package com.tanrunn.tcth.impl.shadow;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.shadow.ShadowTargetKind;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Immutable read-only context of a single shadow theft attempt.
 *
 * <p>Created by the attempt coordinator at the start of an attempt and never
 * mutated afterwards. Carries no inventories, no entity references beyond the
 * thief and no transaction objects — providers, protection services and
 * transfer executors resolve the target from the ids when they need it.
 *
 * <p>The {@code position} is defensively copied to an immutable
 * {@code BlockPos} at construction (never {@code equals}-checked against a
 * mutable source). {@code hasLineOfSight} is the reserved line-of-sight input:
 * a missing/unknown value must fail closed (no behind bonus, no watched
 * penalty).
 *
 * @param eventId       the attempt id, generated once per attempt
 * @param thief         the server player performing the attempt
 * @param targetKind    whether the target is a player or an entity
 * @param targetId      the UUID of the target
 * @param targetType    the entity type id of the target, or {@code null}
 * @param level         the server level of the attempt
 * @param position      the block position of the attempt, or {@code null}
 * @param serverTick    the server tick the attempt happens in
 * @param automated     reserved for mechanical actors; production attempts
 *                      are never automated
 * @param distance      the distance in blocks between thief and target
 * @param hasLineOfSight whether the thief can see the target (unobstructed
 *                      ray); unknown/missing must fail closed
 */
public record ShadowAttemptContext(UUID eventId, ServerPlayer thief, ShadowTargetKind targetKind,
                                   UUID targetId, @Nullable ResourceLocation targetType, ServerLevel level,
                                   @Nullable BlockPos position, long serverTick, boolean automated,
                                   double distance, boolean hasLineOfSight) {

    public ShadowAttemptContext {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(thief, "thief");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(level, "level");
        if (!Double.isFinite(distance) || distance < 0.0d) {
            throw new IllegalArgumentException("distance must be finite and non-negative: " + distance);
        }
        if (serverTick < 0L) {
            throw new IllegalArgumentException("serverTick must be non-negative: " + serverTick);
        }
        // Defensive immutable copy; never equals-detection against the source.
        position = position == null ? null : position.immutable();
    }
}
