package com.tanrunn.tcth.impl.compat.brewer.arc;

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
 * TCTH's custom Arc action type {@code tcth:on_beverage_prepared} (phase 7C).
 *
 * <p>This action carries no logic of its own: Arc executes the rewards from
 * the matching action holder (data-driven {@code jobsplus:job_exp} rewards).
 * The beverage details ride along as {@link BrewerArcRegistrar} action data
 * types so that Arc conditions can filter on them.
 *
 * <p>Only ever loaded when Jobs+ (and Arc) are installed, from the brewer
 * compat module.
 */
public class BeveragePreparedAction extends AbstractAction {

    public BeveragePreparedAction(ResourceLocation location, ResourceLocation actionHolderLocation,
                                  IActionHolderType<?> actionHolderType, boolean shouldPerformOnClient,
                                  List<IReward> rewards, List<ICondition> conditions) {
        super(location, actionHolderLocation, actionHolderType, shouldPerformOnClient, rewards, conditions);
    }

    @Override
    public IActionType<?> getType() {
        return BrewerArcRegistrar.ON_BEVERAGE_PREPARED;
    }

    @Override
    public IActionSerializer<?> getSerializer() {
        return new Serializer();
    }

    /** JSON/network (de)serialization; rewards/conditions are forwarded. */
    public static final class Serializer implements IActionSerializer<BeveragePreparedAction> {

        @Override
        public BeveragePreparedAction fromJson(ResourceLocation location, JsonObject json,
                                               ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                               boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new BeveragePreparedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }

        @Override
        public BeveragePreparedAction fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                                  ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                                  boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new BeveragePreparedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }
    }
}
