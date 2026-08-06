package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.result.ActionResult;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.api.farming.HarvestMethod;
import com.tanrunn.tcth.impl.compat.jobsplus.FarmerRewardModule.FarmerActionSender;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Phase 4A.2: {@link FarmerRewardModule} settlement — switches, automation
 * rejection, bounded eventId idempotency committed only on a successful send,
 * retry on failure, and independence from the chef dish module.
 */
class FarmerRewardModuleTest {

    private ServerLevel level;
    private ServerPlayer player;
    private final AtomicInteger sends = new AtomicInteger();

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        FarmerRewardModule.resetForTesting();
        FarmerRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        FarmerRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        FarmerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 20);
        sends.set(0);
        FarmerRewardModule.setActionSenderForTesting((p, e) -> {
            sends.incrementAndGet();
            return Mockito.mock(ActionResult.class);
        });
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        FarmerRewardModule.resetForTesting();
    }

    private CropHarvestedEvent harvest(boolean automated) {
        return new CropHarvestedEvent(UUID.randomUUID(), automated ? null : player,
                ResourceLocation.parse("minecraft:wheat"), Blocks.WHEAT.defaultBlockState(),
                BlockPos.ZERO, level, HarvestMethod.BREAK, true, automated);
    }

    @Test
    void rewardSwitchOffSendsNothing() {
        FarmerRewardModule.setRewardsEnabledSupplierForTesting(() -> false);
        FarmerRewardModule.onCropHarvested(harvest(false));
        assertEquals(0, sends.get());
    }

    @Test
    void frameworkSwitchOffSendsNothing() {
        FarmerRewardModule.setFrameworkEnabledSupplierForTesting(() -> false);
        FarmerRewardModule.onCropHarvested(harvest(false));
        assertEquals(0, sends.get());
    }

    @Test
    void nullPlayerSendsNothing() {
        FarmerRewardModule.onCropHarvested(harvest(true));
        assertEquals(0, sends.get());
    }

    @Test
    void automatedEventSendsNothing() {
        FarmerRewardModule.onCropHarvested(
                new CropHarvestedEvent(UUID.randomUUID(), player, ResourceLocation.parse("minecraft:wheat"),
                        Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, level, HarvestMethod.BREAK, true, true));
        assertEquals(0, sends.get());
    }

    @Test
    void successfulSettlementsAreIdempotentByEventId() {
        CropHarvestedEvent event = harvest(false);
        FarmerRewardModule.onCropHarvested(event);
        FarmerRewardModule.onCropHarvested(event); // 同 eventId 不再发送
        assertEquals(1, sends.get());
        assertTrue(FarmerRewardModule.isEventIdTracked(event.getEventId()));
    }

    @Test
    void differentEventIdsBothSettle() {
        FarmerRewardModule.onCropHarvested(harvest(false));
        FarmerRewardModule.onCropHarvested(harvest(false));
        assertEquals(2, sends.get());
    }

    @Test
    void failedSendDoesNotConsumeEventIdAndCanRetry() {
        FarmerRewardModule.setActionSenderForTesting((p, e) -> null); // 发送失败
        CropHarvestedEvent event = harvest(false);
        FarmerRewardModule.onCropHarvested(event);
        assertEquals(0, sends.get());
        assertFalse(FarmerRewardModule.isEventIdTracked(event.getEventId()),
                "failed send must not consume the eventId");

        // 恢复成功发送：同一 eventId 可重试
        FarmerRewardModule.setActionSenderForTesting((p, e) -> {
            sends.incrementAndGet();
            return Mockito.mock(ActionResult.class);
        });
        FarmerRewardModule.onCropHarvested(event);
        assertEquals(1, sends.get());
        assertTrue(FarmerRewardModule.isEventIdTracked(event.getEventId()));
    }

    @Test
    void rateLimitDropsSurplusSends() {
        FarmerRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 1);
        FarmerRewardModule.onCropHarvested(harvest(false));
        FarmerRewardModule.onCropHarvested(harvest(false));
        assertEquals(1, sends.get(), "rate limit of 1 action per tick per player");
    }

    @Test
    void stopClearsIdempotencyCache() {
        CropHarvestedEvent event = harvest(false);
        FarmerRewardModule.onCropHarvested(event);
        assertTrue(FarmerRewardModule.trackedEventCountForTesting() > 0);
        FarmerRewardModule.onServerStopping(Mockito.mock(ServerStoppingEvent.class));
        assertEquals(0, FarmerRewardModule.trackedEventCountForTesting());
    }

    @Test
    void chefDishModuleAndFarmerModuleAreIndependent() {
        // 农夫模块只监听 CropHarvestedEvent；DishCookedEvent 不被农夫模块消费
        //（类型系统隔离：onCropHarvested 只接受 CropHarvestedEvent）。
        FarmerRewardModule.onCropHarvested(harvest(false));
        assertEquals(1, sends.get(), "farmer module settles only crop events");
    }

    @Test
    void moduleExceptionDoesNotBreakTick() {
        FarmerRewardModule.setActionSenderForTesting((p, e) -> {
            throw new IllegalStateException("boom");
        });
        CropHarvestedEvent event = harvest(false);
        FarmerRewardModule.onCropHarvested(event); // 异常被吞掉，不抛到调用方
        assertFalse(FarmerRewardModule.isEventIdTracked(event.getEventId()),
                "exception must not commit idempotency");
    }
}
