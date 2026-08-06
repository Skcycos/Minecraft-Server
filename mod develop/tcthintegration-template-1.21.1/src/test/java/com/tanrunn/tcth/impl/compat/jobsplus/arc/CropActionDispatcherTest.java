package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.api.farming.HarvestMethod;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Phase 4A.2: {@code tcth:on_crop_harvested} Arc action — registration and
 * action-data field mapping (Arc-native BLOCK_STATE/BLOCK_POSITION/WORLD plus
 * crop_id / harvest_method / automated).
 */
class CropActionDispatcherTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void actionTypeAndDataTypesAreRegistered() {
        assertNotNull(TcthArcRegistrar.CROP_HARVESTED);
        assertEquals("tcth", TcthArcRegistrar.CROP_HARVESTED.getLocation().getNamespace());
        assertEquals("on_crop_harvested", TcthArcRegistrar.CROP_HARVESTED.getLocation().getPath());
        assertNotNull(TcthArcRegistrar.CROP_ID);
        assertNotNull(TcthArcRegistrar.HARVEST_METHOD);
        assertEquals("tcth:crop_id", TcthArcRegistrar.CROP_ID.getLocation().toString());
        assertEquals("tcth:harvest_method", TcthArcRegistrar.HARVEST_METHOD.getLocation().toString());
    }

    @Test
    void actionDataFieldsAreCompleteAndCorrect() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        CropHarvestedEvent event = new CropHarvestedEvent(UUID.randomUUID(), player,
                ResourceLocation.parse("minecraft:wheat"), Blocks.WHEAT.defaultBlockState(),
                new BlockPos(7, 8, 9), level, HarvestMethod.RIGHT_CLICK, true, false);
        ArcServerPlayer arcPlayer = Mockito.mock(ArcServerPlayer.class);

        var data = CropActionDispatcher.buildActionData(arcPlayer, event);
        assertEquals(Blocks.WHEAT.defaultBlockState(), data.getData(ActionDataType.BLOCK_STATE));
        assertEquals(new BlockPos(7, 8, 9), data.getData(ActionDataType.BLOCK_POSITION));
        assertEquals(level, data.getData(ActionDataType.WORLD));
        assertEquals("minecraft:wheat", data.getData(TcthArcRegistrar.CROP_ID));
        assertEquals("RIGHT_CLICK", data.getData(TcthArcRegistrar.HARVEST_METHOD));
        assertEquals(false, data.getData(TcthArcRegistrar.AUTOMATED));
    }

    @Test
    void breakHarvestMethodMapsToName() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        CropHarvestedEvent event = new CropHarvestedEvent(UUID.randomUUID(), player,
                ResourceLocation.parse("minecraft:cocoa"), Blocks.COCOA.defaultBlockState(),
                BlockPos.ZERO, level, HarvestMethod.BREAK, true, false);
        var data = CropActionDispatcher.buildActionData(Mockito.mock(ArcServerPlayer.class), event);
        assertEquals("BREAK", data.getData(TcthArcRegistrar.HARVEST_METHOD));
    }
}
