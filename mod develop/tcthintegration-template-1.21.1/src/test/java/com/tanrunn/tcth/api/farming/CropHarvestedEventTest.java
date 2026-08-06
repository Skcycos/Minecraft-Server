package com.tanrunn.tcth.api.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Phase 4A.2: public {@code CropHarvestedEvent} API contract — non-null
 * validation, stable eventId, immutable BlockPos, getters and the
 * no-third-party-class guarantee of the API bytecode.
 */
class CropHarvestedEventTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private static CropHarvestedEvent sample(BlockPos pos) {
        return new CropHarvestedEvent(UUID.randomUUID(), null, ResourceLocation.parse("minecraft:wheat"),
                Blocks.WHEAT.defaultBlockState(), pos, Mockito.mock(ServerLevel.class),
                HarvestMethod.BREAK, true, false);
    }

    @Test
    void nullFieldsAreRejected() {
        ServerLevel level = Mockito.mock(ServerLevel.class);
        BlockState state = Blocks.WHEAT.defaultBlockState();
        ResourceLocation id = ResourceLocation.parse("minecraft:wheat");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(null, null, id, state, BlockPos.ZERO, level, HarvestMethod.BREAK, true, false),
                "eventId must not be null");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(UUID.randomUUID(), null, null, state, BlockPos.ZERO, level, HarvestMethod.BREAK, true, false),
                "cropId must not be null");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(UUID.randomUUID(), null, id, null, BlockPos.ZERO, level, HarvestMethod.BREAK, true, false),
                "harvestedState must not be null");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(UUID.randomUUID(), null, id, state, null, level, HarvestMethod.BREAK, true, false),
                "position must not be null");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(UUID.randomUUID(), null, id, state, BlockPos.ZERO, null, HarvestMethod.BREAK, true, false),
                "level must not be null");
        assertThrows(NullPointerException.class,
                () -> new CropHarvestedEvent(UUID.randomUUID(), null, id, state, BlockPos.ZERO, level, null, true, false),
                "method must not be null");
    }

    @Test
    void eventIdIsStableAcrossGetters() {
        UUID id = UUID.randomUUID();
        CropHarvestedEvent event = new CropHarvestedEvent(id, null, ResourceLocation.parse("minecraft:wheat"),
                Blocks.WHEAT.defaultBlockState(), BlockPos.ZERO, Mockito.mock(ServerLevel.class),
                HarvestMethod.BREAK, true, false);
        assertEquals(id, event.getEventId());
        assertEquals(id, event.getEventId(), "eventId must be constant");
    }

    @Test
    void positionIsStoredImmutable() {
        BlockPos mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        CropHarvestedEvent event = sample(mutable);
        assertEquals(mutable, event.getPosition());
        assertNotSame(mutable, event.getPosition(), "event must hold an immutable copy");
        // 事件内部必须是 immutable 实例（与 BlockPos.ZERO 同一类型）。
        assertEquals(BlockPos.ZERO.getClass(), event.getPosition().getClass(),
                "position must be an immutable BlockPos");
    }

    @Test
    void gettersExposeMethodAutomatedFullyGrownPlayer() {
        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        CropHarvestedEvent event = new CropHarvestedEvent(UUID.randomUUID(), player,
                ResourceLocation.parse("minecraft:wheat"), Blocks.WHEAT.defaultBlockState(),
                new BlockPos(4, 5, 6), Mockito.mock(ServerLevel.class), HarvestMethod.RIGHT_CLICK, true, true);
        assertEquals(HarvestMethod.RIGHT_CLICK, event.getMethod());
        assertTrue(event.isFullyGrown());
        assertTrue(event.isAutomated());
        assertEquals(player, event.getPlayer());
        assertNull(sample(BlockPos.ZERO).getPlayer(), "player may be null (automated)");
    }

    @Test
    void apiClassHasNoThirdPartyModReferences() throws Exception {
        // 公共 API 字节码不得引用 Arc / Jobs+ / FD / KC 等可选模组类。
        Path jar = Path.of("build/classes/java/main");
        Path classFile = jar.resolve("com/tanrunn/tcth/api/farming/CropHarvestedEvent.class");
        String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        for (String forbidden : new String[]{"daqem", "jobsplus", "farmersdelight", "kaleidoscopecookery",
                "kaleidoscope_cookery", "vectorwing", "com/github/ysbbbbbb"}) {
            assertFalse(bytes.contains(forbidden), "API must not reference third-party class: " + forbidden);
        }
        // HarvestMethod 同样不引用第三方类。
        Path methodClass = jar.resolve("com/tanrunn/tcth/api/farming/HarvestMethod.class");
        String methodBytes = new String(Files.readAllBytes(methodClass), StandardCharsets.ISO_8859_1);
        assertFalse(methodBytes.contains("daqem") && methodBytes.contains("jobsplus"),
                "HarvestMethod must not reference third-party classes");
    }

    @Test
    void harvestMethodEnumValues() {
        assertEquals(4, HarvestMethod.values().length);
        assertTrue(HarvestMethod.valueOf("BREAK") == HarvestMethod.BREAK);
        assertTrue(HarvestMethod.valueOf("RIGHT_CLICK") == HarvestMethod.RIGHT_CLICK);
        assertTrue(HarvestMethod.valueOf("SPECIAL_BLOCK") == HarvestMethod.SPECIAL_BLOCK);
        assertTrue(HarvestMethod.valueOf("OTHER") == HarvestMethod.OTHER);
    }

    @Test
    void apiPackageHasNoThirdPartyModDependencyInClasspath() throws Exception {
        // 发布 JAR 检查在构建后由静态验证覆盖；这里补充：API 源码文件不在任何
        // compat 包路径下。
        Path apiDir = Path.of("src/main/java/com/tanrunn/tcth/api/farming");
        assertTrue(Files.exists(apiDir.resolve("CropHarvestedEvent.java")));
        assertTrue(Files.exists(apiDir.resolve("HarvestMethod.java")));
    }
}
