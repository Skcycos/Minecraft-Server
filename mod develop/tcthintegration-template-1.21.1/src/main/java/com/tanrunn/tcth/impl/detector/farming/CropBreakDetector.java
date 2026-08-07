package com.tanrunn.tcth.impl.detector.farming;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.farming.HarvestMethod;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Detects real-player crop harvests performed by breaking blocks.
 *
 * <p>Listens to the server-side NeoForge {@link BlockEvent.BreakEvent}:
 * <ul>
 *   <li>uses the pre-break {@code BlockState} ({@code event.getState()});</li>
 *   <li>the player must be a real {@link ServerPlayer} — {@link FakePlayer}
 *       (and subclasses) are rejected here and by the dispatcher;</li>
 *   <li>cancelled events are not received ({@code receiveCanceled=false});</li>
 *   <li>each break posts at most one event, never keyed to drop counts.</li>
 * </ul>
 *
 * <p>Maturity is evaluated by {@link CropHarvestRules}; immature crops produce
 * no event. The Create harvester (which posts {@code BreakEvent} with a
 * {@code null} player) is rejected by the {@code ServerPlayer} check.
 */
public final class CropBreakDetector {

    private static boolean initialized = false;

    private CropBreakDetector() {
    }

    /**
     * Registers the break listener. Idempotent.
     *
     * <p>Priority is {@link EventPriority#LOWEST} with {@code receiveCanceled=false}
     * so that harvests already cancelled by other listeners (e.g. protected
     * regions) are never posted. This is a best-effort ordering guarantee, not
     * an absolute one: a third-party listener registered at an even lower
     * priority could still cancel afterwards — documented, not claimed away.
     */
    public static void init(IEventBus bus) {
        if (initialized) {
            return;
        }
        initialized = true;
        registerBreakListener(bus);
        TCTHIntegration.LOGGER.debug("[TCTH] Crop break detector registered (priority LOWEST)");
    }

    /**
     * Registers the break listener on the given bus. Separate from
     * {@link #init} so tests can assert the priority and receiveCanceled
     * settings without touching the init flag.
     */
    static void registerBreakListener(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class,
                CropBreakDetector::onBreak);
    }

    static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            // No player context (e.g. Create harvester) or fake player: rejected.
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        CropHarvestRules.Assessment assessment = CropHarvestRules.assess(level, pos, state);
        if (!assessment.harvestable || !assessment.fullyGrown) {
            return; // immature or not a crop: 0 events
        }
        // Double-plant crops (e.g. dungeonsdelight:rotbulb_crop / PitcherCropBlock):
        // NeoForge may deliver the player's BreakEvent on either half. Normalize
        // the published position to the lower half so upper+lower never produce
        // two events (dispatcher idempotency keys on position). The secondary
        // half is often removed via setBlock without a second BreakEvent, but
        // position normalization does not rely on that alone.
        pos = normalizeDoublePlantPosition(pos, state);
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        CropHarvestedEventDispatcher.publish(player, cropId, state, pos,
                level, HarvestMethod.BREAK, true);
    }

    /**
     * If {@code state} has a {@code half=upper} double-block property, returns
     * {@code pos.below()}; otherwise returns {@code pos} unchanged.
     */
    static BlockPos normalizeDoublePlantPosition(BlockPos pos, BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if ("half".equals(property.getName()) && property instanceof EnumProperty<?> enumProp) {
                Comparable<?> value = state.getValue(enumProp);
                if (value == DoubleBlockHalf.UPPER) {
                    return pos.below();
                }
                return pos;
            }
        }
        return pos;
    }

    static boolean isInitializedForTesting() {
        return initialized;
    }

    static void resetForTesting() {
        initialized = false;
    }
}
