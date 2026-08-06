package com.tanrunn.tcth.impl.detector.farming;

import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.api.farming.HarvestMethod;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Stateless support for the right-click harvest mixins (sweet berries,
 * Farmers Delight tomatoes, Kaleidoscope Cookery rice/chili).
 *
 * <p>The mixins are <strong>RETURN-only</strong>: no {@code @Unique} snapshot
 * field, no {@code HEAD} injection. The original method arguments
 * ({@code state}, {@code level}, {@code pos}, {@code player}) and the return
 * value are passed to {@link #handleReturn}, which re-reads the post-harvest
 * state from the level. If the original method throws, {@code RETURN} never
 * runs and no state or player reference is left behind.
 *
 * <p>An event is published only when <em>all</em> of these hold:
 * <ol>
 *   <li>server side;</li>
 *   <li>real {@link ServerPlayer} (not {@link FakePlayer} or subclasses);</li>
 *   <li>the interaction returned success;</li>
 *   <li>the pre-harvest state has an {@code age} property;</li>
 *   <li>the pre-harvest age equals the legal maximum (mature);</li>
 *   <li><em>by default</em> the block at the position is still the same crop
 *       block, the current state has an {@code age} property of the same name
 *       and the current age is <em>strictly lower</em> than the pre-harvest
 *       age.</li>
 * </ol>
 *
 * <p>A plain {@code !current.equals(oldState)} is never used as harvest
 * evidence. Verified harvest implementations satisfy the age-decrease rule
 * (sweet berries 3→1, FD tomato max→0, KC base crops 7→5 via
 * {@code BaseCropBlock.onUseBreakCrop}).
 *
 * <p><strong>Only exception</strong>: Kaleidoscope Cookery rice
 * ({@code kaleidoscope_cookery:rice_crop}) right-click harvest <em>removes</em>
 * the block (bytecode- and live-verified: {@code onUseBreakCrop} rebuilds the
 * state from {@code defaultBlockState()} and drops the {@code LOCATION}
 * property, so the support check destroys the plant). It is the only crop in
 * {@code RIGHT_CLICK_REMOVE_CROPS} and the only one allowed to pass the
 * "not the same block" check. Every other block replacement is rejected.
 */
public final class HarvestInteractionMixinSupport {

    private HarvestInteractionMixinSupport() {
    }

    /**
     * Whether this interaction may be settled at all: server side, real
     * {@link ServerPlayer}, not a {@link FakePlayer}.
     */
    public static boolean shouldTrack(Level level, Player player) {
        return !level.isClientSide()
                && player instanceof ServerPlayer serverPlayer
                && !(serverPlayer instanceof FakePlayer);
    }

    /**
     * Settles a right-click harvest at RETURN. Publishes exactly one event
     * when every condition of the strict age-decrease check passes.
     *
     * @param level              the level the interaction ran in
     * @param pos                the crop position
     * @param oldState           the pre-harvest block state (original argument)
     * @param player             the interacting player
     * @param interactionSucceeded whether the interaction returned success
     */
    public static void handleReturn(Level level, BlockPos pos, BlockState oldState, Player player,
                                    boolean interactionSucceeded) {
        if (!shouldTrack(level, player) || !interactionSucceeded) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 4. pre-harvest state must carry an age property
        IntegerProperty oldAge = CropHarvestRules.findAgeProperty(oldState);
        if (oldAge == null) {
            return;
        }
        // 5. pre-harvest age must be the legal maximum (mature)
        if (oldState.getValue(oldAge) != maxAgeOf(oldAge)) {
            return;
        }
        BlockState current = serverLevel.getBlockState(pos);
        // 6. the block must still be the same crop block — with one
        //    bytecode-proven special rule: Kaleidoscope Cookery rice
        //    (kaleidoscope_cookery:rice_crop) right-click harvest REMOVES the
        //    block instead of resetting its age. Verified in game (right-click
        //    on a mature rice plant leaves air) and by bytecode:
        //    BaseCropBlock.onUseBreakCrop -> setBlock(getStateForAge(5)) where
        //    getStateForAge rebuilds from defaultBlockState() and drops the
        //    LOCATION property, so the support check destroys the plant. Any
        //    other crop whose current state is not the same block is rejected.
        boolean sameBlock = current.is(oldState.getBlock());
        if (!sameBlock) {
            if (!RIGHT_CLICK_REMOVE_CROPS.contains(
                    BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).toString())) {
                return;
            }
        } else {
            // 7. the current state must carry an age property of the same name
            IntegerProperty currentAge = CropHarvestRules.findAgeProperty(current);
            if (currentAge == null) {
                return;
            }
            // 8. current age must be strictly lower than the pre-harvest age
            if (current.getValue(currentAge) >= oldState.getValue(oldAge)) {
                return;
            }
        }
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(oldState.getBlock());
        CropHarvestedEventDispatcher.publish(serverPlayer, cropId, oldState, pos,
                serverLevel, HarvestMethod.RIGHT_CLICK, true);
    }

    /**
     * Crops whose right-click harvest removes the block (bytecode-proven) and
     * therefore pass the "current is not the same block" check.
     */
    private static volatile Set<String> RIGHT_CLICK_REMOVE_CROPS = Set.of(
            "kaleidoscope_cookery:rice_crop");

    static void setRightClickRemoveCropsForTesting(Set<String> ids) {
        RIGHT_CLICK_REMOVE_CROPS = ids;
    }

    private static int maxAgeOf(IntegerProperty age) {
        return age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
