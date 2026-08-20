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
 * TCTH's custom Arc action type {@code tcth:on_shadow_theft_success} (phase
 * 8E).
 *
 * <p>This action carries no logic of its own: Arc executes the rewards from
 * the matching action holder (data-driven {@code jobsplus:job_exp} rewards in
 * the {@code tcth:shadow_thief} preset). The theft details ride along as
 * {@link TcthArcRegistrar} action data types so that Arc conditions
 * ({@code tcth:shadow_target_kind}, {@code tcth:shadow_theft_type},
 * {@code tcth:automated}) can filter on them.
 *
 * <p>The same type also backs the twelve per-node powerup-holder actions of
 * the preset, which declare the {@code jobsplus:powerup_not_active}
 * exclusion structure (empty rewards — purely declarative; the actual tier
 * effects are Java-driven through the attempt snapshot).
 *
 * <p>Only ever loaded when Jobs+ (and Arc) are installed, from the
 * {@code jobsplus} compat module.
 */
public class ShadowTheftSuccessAction extends AbstractAction {

    public ShadowTheftSuccessAction(ResourceLocation location, ResourceLocation actionHolderLocation,
                                    IActionHolderType<?> actionHolderType, boolean shouldPerformOnClient,
                                    List<IReward> rewards, List<ICondition> conditions) {
        super(location, actionHolderLocation, actionHolderType, shouldPerformOnClient, rewards, conditions);
    }

    @Override
    public IActionType<?> getType() {
        return TcthArcRegistrar.SHADOW_THEFT_SUCCESS;
    }

    @Override
    public IActionSerializer<?> getSerializer() {
        return new Serializer();
    }

    /**
     * JSON/network (de)serialization. Rewards/conditions are parsed and passed
     * in by the Arc framework; this serializer only forwards them.
     */
    public static final class Serializer implements IActionSerializer<ShadowTheftSuccessAction> {

        @Override
        public ShadowTheftSuccessAction fromJson(ResourceLocation location, JsonObject json,
                                                 ResourceLocation actionHolderLocation,
                                                 IActionHolderType<?> actionHolderType,
                                                 boolean shouldPerformOnClient, List<IReward> rewards,
                                                 List<ICondition> conditions) {
            return new ShadowTheftSuccessAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }

        @Override
        public ShadowTheftSuccessAction fromNetwork(ResourceLocation location, RegistryFriendlyByteBuf buf,
                                                    ResourceLocation actionHolderLocation,
                                                    IActionHolderType<?> actionHolderType,
                                                    boolean shouldPerformOnClient, List<IReward> rewards,
                                                    List<ICondition> conditions) {
            return new ShadowTheftSuccessAction(location, actionHolderLocation, actionHolderType,
                    shouldPerformOnClient, rewards, conditions);
        }
    }
}
