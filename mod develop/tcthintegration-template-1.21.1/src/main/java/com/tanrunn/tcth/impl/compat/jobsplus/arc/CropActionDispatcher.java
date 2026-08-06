package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import org.jetbrains.annotations.Nullable;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.ActionDataBuilder;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.action.result.ActionResult;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;

import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a {@code tcth:on_crop_harvested} Arc action for a settled harvest
 * event.
 *
 * <p>Mirror of {@link DishActionDispatcher} for farming: builds an
 * {@link ActionData} with the Arc-native {@code BLOCK_STATE},
 * {@code BLOCK_POSITION}, {@code WORLD} data plus the TCTH {@code crop_id},
 * {@code harvest_method} and {@code automated} fields, then fires
 * {@code sendToAction()}. Arc matches the player's data-driven action holders
 * and executes their rewards; this class never touches Jobs+ internals.
 *
 * <p>A failure only affects the current event: it is logged and never breaks
 * the server tick.
 */
public final class CropActionDispatcher {

    private CropActionDispatcher() {
    }

    /**
     * @param player the player to target (must be an {@link ArcServerPlayer})
     * @param event  the harvest event
     * @return the Arc action result, or {@code null} if sending failed
     */
    @Nullable
    public static ActionResult sendCropHarvestedAction(ServerPlayer player, CropHarvestedEvent event) {
        try {
            ArcServerPlayer arcPlayer = (ArcServerPlayer) player;
            return buildActionData(arcPlayer, event).sendToAction();
        } catch (Exception e) {
            TCTHIntegration.LOGGER.error("[TCTH] Failed to send crop-harvested action for {} ({}): {}",
                    player.getGameProfile().getName(), event.getCropId(), e.toString());
            return null;
        }
    }

    /** Builds the {@code tcth:on_crop_harvested} action data. */
    public static ActionData buildActionData(ArcServerPlayer player, CropHarvestedEvent event) {
        return new ActionDataBuilder(player, TcthArcRegistrar.CROP_HARVESTED)
                .withData(ActionDataType.BLOCK_STATE, event.getHarvestedState())
                .withData(ActionDataType.BLOCK_POSITION, event.getPosition())
                .withData(ActionDataType.WORLD, event.getLevel())
                .withData(TcthArcRegistrar.CROP_ID, event.getCropId().toString())
                .withData(TcthArcRegistrar.HARVEST_METHOD, event.getMethod().name())
                .withData(TcthArcRegistrar.AUTOMATED, event.isAutomated())
                .build();
    }
}
