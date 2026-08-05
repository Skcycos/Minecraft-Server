package com.tanrunn.tcth.impl.compat.fieldguide;

import com.evandev.fieldguide.server.ServerFieldGuideManager;
import com.evandev.fieldguide.server.progress.FieldGuideProgressManager;
import com.evandev.fieldguide.server.progress.PlayerFieldGuideProgress;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Production {@link FieldGuideApi} backed by the Field Guide public API.
 *
 * <p>All Field Guide type references in this project are confined to the
 * {@code impl.compat.fieldguide} conditional-compat package (this class and
 * {@link FieldGuideCompatModule}); Field Guide classes must never ship inside
 * the TCTH JAR.
 *
 * <p>All lookups are defensive: the progress manager may still be the pre-init
 * NOOP (returns {@code null} from {@code getProgress}) and the server manager
 * may not know an entry yet — both cases are reported as "not available"
 * rather than throwing.
 */
final class FieldGuideApiAdapter implements FieldGuideApi {

    @Override
    public boolean isProgressAvailable(ServerPlayer player) {
        return progress(player) != null;
    }

    @Override
    public boolean hasEntry(ResourceLocation entryId) {
        try {
            return ServerFieldGuideManager.getInstance().hasEntry(entryId);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean isUnlocked(ServerPlayer player, ResourceLocation entryId) {
        PlayerFieldGuideProgress progress = progress(player);
        return progress != null && progress.isUnlocked(entryId);
    }

    @Override
    public boolean unlock(ServerPlayer player, ResourceLocation entryId) {
        PlayerFieldGuideProgress progress = progress(player);
        if (progress == null) {
            return false;
        }
        if (progress.isUnlocked(entryId)) {
            return false;
        }
        // unlock(player, entryId, source, silent): empty source, non-silent.
        // Field Guide handles notification + persistence (tick -> flushDirty).
        progress.unlock(player, entryId, "", false);
        return true;
    }

    private static PlayerFieldGuideProgress progress(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            return FieldGuideProgressManager.getInstance().getProgress(player);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
