package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import java.util.List;

import com.daqem.arc.api.action.AbstractAction;
import com.daqem.arc.api.action.holder.type.IActionHolderType;
import com.daqem.arc.api.action.serializer.IActionSerializer;
import com.daqem.arc.api.action.type.IActionType;
import com.daqem.arc.api.condition.ICondition;
import com.daqem.arc.api.reward.IReward;
import com.google.gson.JsonObject;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * TCTH's custom Arc action type {@code tcth:on_crop_harvested}.
 *
 * <p>Like {@link DishCookedAction}, this action carries no logic of its own:
 * Arc executes the rewards from the matching action holder (data-driven
 * {@code jobsplus:job_exp} rewards). The harvest details ride along as
 * {@link TcthArcRegistrar} action data types so that Arc conditions can
 * filter on them (e.g. {@code tcth:automated} value false).
 *
 * <p>Only ever loaded when Jobs+ (and Arc) are installed.
 */
public class CropHarvestedAction extends AbstractAction {

    public CropHarvestedAction(ResourceLocation location, ResourceLocation actionHolderLocation,
                               IActionHolderType<?> actionHolderType, boolean shouldPerformOnClient,
                               List<IReward> rewards, List<ICondition> conditions) {
        super(location, actionHolderLocation, actionHolderType, shouldPerformOnClient, rewards, conditions);
    }

    @Override
    public IActionType<?> getType() {
        return TcthArcRegistrar.CROP_HARVESTED;
    }

    public static class Serializer implements IActionSerializer<CropHarvestedAction> {

        @Override
        public CropHarvestedAction fromJson(ResourceLocation location, JsonObject json,
                                            ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                            boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new CropHarvestedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }

        @Override
        public CropHarvestedAction fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                               ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                               boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new CropHarvestedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }
    }
}
