package com.tanrunn.tcth.impl.compat.fieldguide;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Injectable seam around the Field Guide public API.
 *
 * <p>Production uses {@link FieldGuideApiAdapter} (the only class in the
 * project that touches {@code com.evandev.fieldguide.*} types); unit tests
 * substitute a fake so no live Field Guide classes, player saves or a running
 * client are required.
 */
interface FieldGuideApi {

    /**
     * @return {@code true} if the Field Guide progress system is live and has
     *         a progress record for the player (i.e. the manager is not the
     *         pre-init NOOP and the player has joined)
     */
    boolean isProgressAvailable(ServerPlayer player);

    /**
     * @return {@code true} if the Field Guide data defines an entry with this
     *         id (auto-populated from a tag, explicit, etc.)
     */
    boolean hasEntry(ResourceLocation entryId);

    /**
     * @return {@code true} if this player has already unlocked the entry
     */
    boolean isUnlocked(ServerPlayer player, ResourceLocation entryId);

    /**
     * Unlocks the entry for the player via the public API. Idempotent.
     *
     * @return {@code true} if this call performed the unlock (the entry was
     *         not unlocked before); {@code false} if it was already unlocked
     *         or the progress system was unavailable
     */
    boolean unlock(ServerPlayer player, ResourceLocation entryId);
}
