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
 * TCTH's custom Arc action type {@code tcth:on_dish_cooked}.
 *
 * <p>This action carries no logic of its own: Arc executes the rewards from
 * the matching action holder (data-driven {@code jobsplus:job_exp} rewards).
 * The dish details ride along as {@link TcthArcRegistrar} action data types so
 * that Arc conditions can filter on them.
 *
 * <p>Only ever loaded when Jobs+ (and Arc) are installed, from the
 * {@code jobsplus} compat module.
 */
public class DishCookedAction extends AbstractAction {

    public DishCookedAction(ResourceLocation location, ResourceLocation actionHolderLocation,
                            IActionHolderType<?> actionHolderType, boolean shouldPerformOnClient,
                            List<IReward> rewards, List<ICondition> conditions) {
        super(location, actionHolderLocation, actionHolderType, shouldPerformOnClient, rewards, conditions);
    }

    @Override
    public IActionType<?> getType() {
        return TcthArcRegistrar.DISH_COOKED;
    }

    @Override
    public IActionSerializer<?> getSerializer() {
        return new Serializer();
    }

    /**
     * JSON/network (de)serialization. Rewards/conditions are parsed and passed
     * in by the Arc framework; this serializer only forwards them.
     */
    public static final class Serializer implements IActionSerializer<DishCookedAction> {

        @Override
        public DishCookedAction fromJson(ResourceLocation location, JsonObject json,
                                         ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                         boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new DishCookedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }

        @Override
        public DishCookedAction fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                            ResourceLocation actionHolderLocation, IActionHolderType<?> actionHolderType,
                                            boolean shouldPerformOnClient, List<IReward> rewards, List<ICondition> conditions) {
            return new DishCookedAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }
    }
}
