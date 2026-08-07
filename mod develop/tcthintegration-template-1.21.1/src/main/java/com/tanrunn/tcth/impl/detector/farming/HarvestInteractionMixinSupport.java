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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Stateless support for the right-click harvest mixins (sweet berries,
 * Farmers Delight tomatoes, Kaleidoscope Cookery rice/chili, Neapolitan
 * strawberry/mint, My Nether's Delight powdery crops, and tag-filtered
 * mushroom colonies).
 *
 * <p>The mixins are <strong>RETURN-only</strong>: no {@code @Unique} snapshot
 * field, no {@code HEAD} injection. The original method arguments
 * ({@code state}, {@code level}, {@code pos}, {@code player}) and the return
 * value are passed to the handlers, which re-read the post-harvest state from
 * the level. If the original method throws, {@code RETURN} never runs and no
 * state or player reference is left behind.
 *
 * <p>A plain {@code !current.equals(oldState)} is never used as harvest
 * evidence. Each handler requires a bytecode-proven post-harvest state change
 * (age decrease, lit reset, colony age decrease, or the documented
 * right-click-remove exception for Kaleidoscope Cookery rice).
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
     * Server-side success for {@link InteractionResult} paths that use
     * {@code InteractionResult.sidedSuccess(level.isClientSide)} (server →
     * {@link InteractionResult#CONSUME}) or return plain {@code SUCCESS}.
     */
    public static boolean isSuccess(InteractionResult result) {
        return result == InteractionResult.SUCCESS || result == InteractionResult.CONSUME;
    }

    /**
     * Server-side success for {@link ItemInteractionResult} paths that use
     * {@code ItemInteractionResult.sidedSuccess(level.isClientSide)} (server →
     * {@link ItemInteractionResult#CONSUME}) or return plain {@code SUCCESS}.
     * Never treat {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} as success.
     */
    public static boolean isSuccess(ItemInteractionResult result) {
        return result == ItemInteractionResult.SUCCESS || result == ItemInteractionResult.CONSUME;
    }

    /**
     * Settles a standard max-age right-click harvest at RETURN. Publishes
     * exactly one event when every condition of the strict age-decrease check
     * passes (sweet berries, Neapolitan strawberry/mint, FD tomatoes, KC
     * crops with age reset).
     */
    public static void handleReturn(Level level, BlockPos pos, BlockState oldState, Player player,
                                    boolean interactionSucceeded) {
        if (!shouldTrack(level, player) || !interactionSucceeded) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        IntegerProperty oldAge = CropHarvestRules.findAgeProperty(oldState);
        if (oldAge == null) {
            return;
        }
        if (oldState.getValue(oldAge) != maxAgeOf(oldAge)) {
            return;
        }
        BlockState current = serverLevel.getBlockState(pos);
        boolean sameBlock = current.is(oldState.getBlock());
        if (!sameBlock) {
            if (!RIGHT_CLICK_REMOVE_CROPS.contains(
                    BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).toString())) {
                return;
            }
        } else {
            IntegerProperty currentAge = CropHarvestRules.findAgeProperty(current);
            if (currentAge == null) {
                return;
            }
            if (current.getValue(currentAge) >= oldState.getValue(oldAge)) {
                return;
            }
        }
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(oldState.getBlock());
        CropHarvestedEventDispatcher.publish(serverPlayer, cropId, oldState, pos,
                serverLevel, HarvestMethod.RIGHT_CLICK, true);
    }

    /**
     * My Nether's Delight {@code powdery_cane}: harvest when
     * {@code age > 1 && lit == true} with knife/shears; post-state
     * {@code lit=false, age=0}. Not a max-age rule (age 2 and 3 both harvest).
     */
    public static void handlePowderyCaneReturn(Level level, BlockPos pos, BlockState oldState, Player player,
                                              boolean interactionSucceeded) {
        if (!shouldTrack(level, player) || !interactionSucceeded) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        IntegerProperty age = CropHarvestRules.findAgeProperty(oldState);
        BooleanProperty lit = findBooleanProperty(oldState, "lit");
        if (age == null || lit == null) {
            return;
        }
        int oldAge = oldState.getValue(age);
        boolean oldLit = oldState.getValue(lit);
        BlockState current = serverLevel.getBlockState(pos);
        if (!current.is(oldState.getBlock())) {
            return;
        }
        IntegerProperty currentAge = CropHarvestRules.findAgeProperty(current);
        BooleanProperty currentLit = findBooleanProperty(current, "lit");
        if (currentAge == null || currentLit == null) {
            return;
        }
        if (!isPowderyCaneHarvestEvidence(oldAge, oldLit, current.getValue(currentAge), current.getValue(currentLit))) {
            return;
        }
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(oldState.getBlock());
        CropHarvestedEventDispatcher.publish(serverPlayer, cropId, oldState, pos,
                serverLevel, HarvestMethod.RIGHT_CLICK, true);
    }

    /**
     * Bytecode-proven powdery cane harvest evidence (MND 1.10.4):
     * pre {@code age > 1 && lit}, post {@code !lit && age < oldAge}.
     */
    static boolean isPowderyCaneHarvestEvidence(int oldAge, boolean oldLit, int newAge, boolean newLit) {
        return oldAge > 1 && oldLit && !newLit && newAge < oldAge;
    }

    /**
     * My Nether's Delight {@code powdery_cannon}: harvest when {@code lit == true}
     * with knife/shears; post-state {@code lit=false}. Age is not a maturity
     * criterion and must not be required.
     */
    public static void handlePowderyCannonReturn(Level level, BlockPos pos, BlockState oldState, Player player,
                                                 boolean interactionSucceeded) {
        if (!shouldTrack(level, player) || !interactionSucceeded) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        BooleanProperty lit = findBooleanProperty(oldState, "lit");
        if (lit == null) {
            return;
        }
        boolean oldLit = oldState.getValue(lit);
        BlockState current = serverLevel.getBlockState(pos);
        if (!current.is(oldState.getBlock())) {
            return;
        }
        BooleanProperty currentLit = findBooleanProperty(current, "lit");
        if (currentLit == null) {
            return;
        }
        if (!isPowderyCannonHarvestEvidence(oldLit, current.getValue(currentLit))) {
            return;
        }
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(oldState.getBlock());
        CropHarvestedEventDispatcher.publish(serverPlayer, cropId, oldState, pos,
                serverLevel, HarvestMethod.RIGHT_CLICK, true);
    }

    /**
     * Bytecode-proven powdery cannon harvest evidence (MND 1.10.4):
     * pre {@code lit}, post {@code !lit}. Age is not a maturity criterion.
     */
    static boolean isPowderyCannonHarvestEvidence(boolean oldLit, boolean newLit) {
        return oldLit && !newLit;
    }

    /**
     * Farmer's Delight MushroomColonyBlock-style harvest for blocks
     * allow-listed by the caller (TCTH tag
     * {@code tcth:farmer_colony_harvestables}). Any {@code age > 0} may be
     * harvested (shears age-1, knife age→0); not a max-age rule.
     *
     * @param tagAllowed whether the block is in the colony harvestables tag
     *                   (or a test override). When false, no event is published.
     */
    public static void handleColonyReturn(Level level, BlockPos pos, BlockState oldState, Player player,
                                          boolean interactionSucceeded, boolean tagAllowed) {
        if (!tagAllowed || !shouldTrack(level, player) || !interactionSucceeded) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        IntegerProperty age = CropHarvestRules.findAgeProperty(oldState);
        if (age == null) {
            return;
        }
        int oldAge = oldState.getValue(age);
        if (oldAge <= 0) {
            return;
        }
        BlockState current = serverLevel.getBlockState(pos);
        if (!current.is(oldState.getBlock())) {
            return;
        }
        IntegerProperty currentAge = CropHarvestRules.findAgeProperty(current);
        if (currentAge == null) {
            return;
        }
        if (current.getValue(currentAge) >= oldAge) {
            return;
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

    @Nullable
    static BooleanProperty findBooleanProperty(BlockState state, String name) {
        for (var property : state.getProperties()) {
            if (property.getName().equals(name) && property instanceof BooleanProperty boolProp) {
                return boolProp;
            }
        }
        return null;
    }

    private static int maxAgeOf(IntegerProperty age) {
        return age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
