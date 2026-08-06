package com.tanrunn.tcth.impl.detector.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.ChiliCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.LettuceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Phase 4A.2.1: mixin structural boundaries —
 * <ul>
 *   <li>no right-click mixin keeps a {@code @Unique} snapshot field
 *       (RETURN-only, stateless);</li>
 *   <li>KC dynamic dispatch: {@code RiceCropBlock} inherits the base
 *       {@code useItemOn} (base mixin covers it), {@code ChiliCropBlock}
 *       overrides it (own mixin required), {@code LettuceCropBlock} overrides
 *       it with a non-harvest implementation (BREAK-only — never claim
 *       right-click support);</li>
 *   <li>the break detector registers at {@link EventPriority#LOWEST} with
 *       {@code receiveCanceled=false}.</li>
 * </ul>
 */
class FarmerMixinBoundaryTest {

    private static final List<String> RIGHT_CLICK_MIXINS = List.of(
            "mixin/SweetBerryBushBlockMixin.java",
            "mixin/farmersdelight/TomatoBlockMixin.java",
            "mixin/kaleidoscope/KcBaseCropBlockMixin.java",
            "mixin/kaleidoscope/KcChiliCropBlockMixin.java");

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void rightClickMixinsAreStatelessReturnOnly() throws Exception {
        for (String relative : RIGHT_CLICK_MIXINS) {
            String source = new String(Files.readAllBytes(
                    Path.of("src/main/java/com/tanrunn/tcth", relative)), StandardCharsets.UTF_8);
            assertFalse(source.lines().anyMatch(l -> l.matches("\\s*@Unique.*")),
                    "mixin must not declare @Unique fields (stateless RETURN-only): " + relative);
            assertFalse(source.contains("@Inject(method = \"useWithoutItem\", at = @At(\"HEAD\"))"),
                    "mixin must not inject HEAD: " + relative);
            assertFalse(source.contains("@Inject(method = \"useItemOn\", at = @At(\"HEAD\"))"),
                    "mixin must not inject HEAD: " + relative);
        }
    }

    @Test
    void kcDispatchBoundaries() {
        // Rice：不声明自己的 useItemOn（继承 BaseCropBlock.useItemOn → Base Mixin 覆盖）
        assertFalse(declaresUseItemOn(RiceCropBlock.class),
                "RiceCropBlock must not declare its own useItemOn");
        // Chili：声明自己的 useItemOn（需专项 Mixin）
        assertTrue(declaresUseItemOn(ChiliCropBlock.class),
                "ChiliCropBlock overrides useItemOn and needs its own mixin");
        // Lettuce：声明自己的 useItemOn 但实现不是收获路径
        assertTrue(declaresUseItemOn(LettuceCropBlock.class),
                "LettuceCropBlock declares its own useItemOn");
    }

    @Test
    void lettuceRightClickIsNotAHarvestPath() throws Exception {
        // 字节码实证：LettuceCropBlock.useItemOn 直接返回 PASS_TO_DEFAULT_BLOCK_INTERACTION。
        try (ZipFile zip = new ZipFile("dev-mods/kaleidoscopecookery-1.4.1.jar")) {
            var entry = zip.getEntry("com/github/ysbbbbbb/kaleidoscopecookery/block/crop/LettuceCropBlock.class");
            byte[] bytes = zip.getInputStream(entry).readAllBytes();
            String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(ascii.contains("PASS_TO_DEFAULT_BLOCK_INTERACTION"),
                    "Lettuce useItemOn returns PASS (no harvest); BREAK is the only harvest path");
            // 不得包含收获特征（SUCCESS + popResource）
            assertFalse(ascii.contains("popResource"), "Lettuce useItemOn must not harvest");
        }
    }

    @Test
    void sweetBerryServerSideSuccessValueIsConsume() throws Exception {
        // 字节码实证：sidedSuccess(false)（服务端）返回 CONSUME，不是 SUCCESS。
        // 甜浆果 Mixin 必须把 CONSUME 视为成功（成熟右键才会发布）。
        assertEquals(InteractionResult.CONSUME, InteractionResult.sidedSuccess(false));
        assertEquals(InteractionResult.SUCCESS, InteractionResult.sidedSuccess(true));

        String source = new String(Files.readAllBytes(
                Path.of("src/main/java/com/tanrunn/tcth/mixin/SweetBerryBushBlockMixin.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("InteractionResult.CONSUME"),
                "sweet berry mixin must treat server-side CONSUME as a successful pick");
    }

    @Test
    void breakDetectorRegistersAtLowestPriorityWithReceiveCanceledFalse() {
        CropBreakDetector.resetForTesting();
        IEventBus bus = mock(IEventBus.class);
        CropBreakDetector.registerBreakListener(bus);
        verify(bus).addListener(ArgumentMatchers.eq(EventPriority.LOWEST),
                ArgumentMatchers.eq(false),
                ArgumentMatchers.eq(BlockEvent.BreakEvent.class),
                ArgumentMatchers.any());
    }

    private static boolean declaresUseItemOn(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("useItemOn"));
    }
}
