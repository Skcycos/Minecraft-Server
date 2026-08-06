package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 4A.1：真实作物覆盖矩阵的类层次实证（JAR 字节码层面的静态审计）。
 *
 * <p>依据 docs/phase-4a.1-farmer-audit.md 的矩阵逐项验证：方块类、父类链、
 * 是否 CropBlock、是否具备名为 age 的 IntegerProperty、Arc on_harvest_crop
 * 静态判定与 TCTH 兼容结论。测试用反射读取测试 classpath 中的 Minecraft /
 * Farmers Delight / Kaleidoscope Cookery 类；Arc/NeoForge JAR 从 dev-mods 与
 * 测试依赖读取。这些断言限定为类层次事实，不冒充在线实测；FakePlayer 状态
 * 必须为 NOT_COVERED / NEEDS_LIVE_TEST 之一（不得声称 VERIFIED）。
 */
class FarmerCropCoverageTest {

    /** FakePlayer 覆盖状态：VERIFIED / NOT_COVERED / NEEDS_LIVE_TEST。 */
    private static final String FAKE_PLAYER_STATUS = "NOT_COVERED";

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private record CropEntry(
            String name,
            String blockId,
            String blocksField,
            String className,
            String expectedSuper,
            boolean isCropBlock,
            boolean hasAgeProperty,
            String arcVerdict,
            boolean needsTcthCompat) {

        /** 返回用于实证的 Class：blocksField 非空则取 Blocks 注册实例类，否则 Class.forName。 */
        Class<?> resolve() throws Exception {
            if (blocksField != null && !blocksField.isBlank()) {
                Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
                return blocks.getField(blocksField).get(null).getClass();
            }
            return Class.forName(className);
        }
    }

    private static final List<CropEntry> MATRIX = List.of(
            // 原版（blocksField 指向 Blocks 静态字段，实证注册实例的运行时类）
            new CropEntry("小麦", "minecraft:wheat", "WHEAT", "net.minecraft.world.level.block.CropBlock",
                    "net.minecraft.world.level.block.BushBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("胡萝卜", "minecraft:carrots", "CARROTS", "net.minecraft.world.level.block.CarrotBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("马铃薯", "minecraft:potatoes", "POTATOES", "net.minecraft.world.level.block.PotatoBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("甜菜", "minecraft:beetroots", "BEETROOTS", "net.minecraft.world.level.block.BeetrootBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("甜浆果", "minecraft:sweet_berry_bush", "SWEET_BERRY_BUSH", "net.minecraft.world.level.block.SweetBerryBushBlock",
                    "net.minecraft.world.level.block.BushBlock", false, true, "不触发（非 CropBlock；右键采摘不走 BREAK）", true),
            new CropEntry("可可豆", "minecraft:cocoa", "COCOA", "net.minecraft.world.level.block.CocoaBlock",
                    "net.minecraft.world.level.block.HorizontalDirectionalBlock", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("下界疣", "minecraft:nether_wart", "NETHER_WART", "net.minecraft.world.level.block.NetherWartBlock",
                    "net.minecraft.world.level.block.BushBlock", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("甘蔗", "minecraft:sugar_cane", "SUGAR_CANE", "net.minecraft.world.level.block.SugarCaneBlock",
                    "net.minecraft.world.level.block.Block", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("仙人掌", "minecraft:cactus", "CACTUS", "net.minecraft.world.level.block.CactusBlock",
                    "net.minecraft.world.level.block.Block", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("南瓜", "minecraft:pumpkin", "PUMPKIN", "net.minecraft.world.level.block.PumpkinBlock",
                    "net.minecraft.world.level.block.Block", false, false, "不触发（非 CropBlock）", true),
            new CropEntry("南瓜梗", "minecraft:pumpkin_stem", "PUMPKIN_STEM", "net.minecraft.world.level.block.StemBlock",
                    "net.minecraft.world.level.block.BushBlock", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("西瓜", "minecraft:melon", "MELON", "net.minecraft.world.level.block.Block",
                    "net.minecraft.world.level.block.state.BlockBehaviour", false, false, "不触发（非 CropBlock）", true),
            new CropEntry("西瓜梗", "minecraft:melon_stem", "MELON_STEM", "net.minecraft.world.level.block.StemBlock",
                    "net.minecraft.world.level.block.BushBlock", false, true, "不触发（非 CropBlock）", true),
            // Farmers Delight
            new CropEntry("番茄（藤蔓）", "farmersdelight:tomatoes", null, "vectorwing.farmersdelight.common.block.TomatoVineBlock",
                    "vectorwing.farmersdelight.common.block.TomatoBlock", true, true, "BREAK-成熟触发；右键采摘不走 BREAK", true),
            new CropEntry("番茄（绳上）", "farmersdelight:tomatoes_on_rope", null, "vectorwing.farmersdelight.common.block.HangingTomatoBlock",
                    "vectorwing.farmersdelight.common.block.TomatoBlock", true, true, "BREAK-成熟触发；右键采摘不走 BREAK", true),
            new CropEntry("卷心菜", "farmersdelight:cabbages", null, "vectorwing.farmersdelight.common.block.CabbageBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("洋葱", "farmersdelight:onions", null, "vectorwing.farmersdelight.common.block.OnionBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            new CropEntry("水稻（下半部分）", "farmersdelight:rice", null, "vectorwing.farmersdelight.common.block.RiceBlock",
                    "net.minecraft.world.level.block.BushBlock", false, true, "不触发（非 CropBlock）", true),
            new CropEntry("水稻穗", "farmersdelight:rice_panicles", null, "vectorwing.farmersdelight.common.block.RicePaniclesBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发", false),
            // Kaleidoscope Cookery
            new CropEntry("生菜", "kaleidoscope_cookery:lettuce_crop", null, "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.LettuceCropBlock",
                    "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.BaseCropBlock", true, true, "BREAK-成熟触发；右键不走 BREAK", true),
            new CropEntry("辣椒", "kaleidoscope_cookery:chili_crop", null, "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.ChiliCropBlock",
                    "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.BaseCropBlock", true, true, "BREAK-成熟触发；右键不走 BREAK", true),
            new CropEntry("水稻", "kaleidoscope_cookery:rice_crop", null, "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock",
                    "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.BaseCropBlock", true, true, "BREAK-成熟触发；右键不走 BREAK", true),
            // 番茄：ModBlocks.TOMATO_CROP 注册 Supplier = new BaseCropBlock(TOMATO, TOMATO_SEED)
            // （KC JAR 字节码实证）→ 实际 Java 类就是 BaseCropBlock 本身，继承其 useItemOn。
            new CropEntry("番茄", "kaleidoscope_cookery:tomato_crop", null, "com.github.ysbbbbbb.kaleidoscopecookery.block.crop.BaseCropBlock",
                    "net.minecraft.world.level.block.CropBlock", true, true, "BREAK-成熟触发；右键不走 BREAK", true));

    @Test
    void coverageMatrixCoversAllSpecifiedCrops() {
        Set<String> names = MATRIX.stream().map(CropEntry::name).collect(java.util.stream.Collectors.toSet());
        for (String required : List.of("小麦", "胡萝卜", "马铃薯", "甜菜", "甜浆果", "可可豆", "下界疣",
                "甘蔗", "仙人掌", "南瓜", "南瓜梗", "西瓜", "西瓜梗",
                "番茄（藤蔓）", "卷心菜", "洋葱", "水稻（下半部分）", "水稻穗",
                "生菜", "辣椒", "水稻", "番茄")) {
            assertTrue(names.contains(required), "覆盖矩阵缺少作物: " + required);
        }
    }

    @Test
    void everyCropClassMatchesDocumentedHierarchyAndAgeProperty() throws Exception {
        for (CropEntry e : MATRIX) {
            Class<?> clazz = e.resolve();
            assertNotNull(clazz, "类不存在: " + e.className() + " / Blocks." + e.blocksField());
            assertEquals(e.className(), clazz.getName(), e.name() + " 实例类名不符");
            assertEquals(e.expectedSuper(), clazz.getSuperclass().getName(), e.name() + " 父类不符");

            boolean isCrop = isAssignableToCropBlock(clazz);
            assertEquals(e.isCropBlock(), isCrop, e.name() + " CropBlock 判定不符");

            boolean hasAge = hasAgeIntegerProperty(clazz);
            assertEquals(e.hasAgeProperty(), hasAge, e.name() + " age 属性存在性不符");

            // Arc on_harvest_crop 静态判定与类层次一致：仅 CropBlock 在 BREAK 路径触发。
            if (e.isCropBlock()) {
                assertTrue(e.arcVerdict().contains("BREAK"), e.name() + " CropBlock 应标注 BREAK 触发");
            } else {
                assertTrue(e.arcVerdict().contains("不触发"), e.name() + " 非 CropBlock 应标注不触发");
            }

            // 兼容结论：非 CropBlock 必为 TCTH 兼容项；CropBlock 仅右键路径需兼容。
            if (!e.isCropBlock()) {
                assertTrue(e.needsTcthCompat(), e.name() + " 非 CropBlock 必须标注需 TCTH 兼容");
            }
        }
    }

    @Test
    void riceBlockAndRicePaniclesAreDistinctClasses() throws Exception {
        Class<?> rice = Class.forName("vectorwing.farmersdelight.common.block.RiceBlock");
        Class<?> panicles = Class.forName("vectorwing.farmersdelight.common.block.RicePaniclesBlock");
        assertFalse(rice.equals(panicles), "RiceBlock 与 RicePaniclesBlock 不得混为一类");
        assertFalse(isAssignableToCropBlock(rice), "水稻下半部分（RiceBlock）不是 CropBlock");
        assertTrue(isAssignableToCropBlock(panicles), "水稻穗（RicePaniclesBlock）是 CropBlock");
        assertEquals("net.minecraft.world.level.block.BushBlock", rice.getSuperclass().getName());
        assertEquals("net.minecraft.world.level.block.CropBlock", panicles.getSuperclass().getName());
    }

    // ---- FakePlayer 审计（静态事实；运行时结论需在线实测） ----

    @Test
    void fakePlayerStatusMustNotClaimVerified() {
        assertTrue(FAKE_PLAYER_STATUS.equals("NOT_COVERED") || FAKE_PLAYER_STATUS.equals("NEEDS_LIVE_TEST"),
                "FakePlayer 状态必须为 NOT_COVERED / NEEDS_LIVE_TEST，不得声称 VERIFIED");
    }

    @Test
    void fakePlayerExtendsServerPlayer() throws Exception {
        Class<?> fp = Class.forName("net.neoforged.neoforge.common.util.FakePlayer");
        assertEquals("net.minecraft.server.level.ServerPlayer", fp.getSuperclass().getName(),
                "FakePlayer 必须继承 ServerPlayer");
    }

    @Test
    void arcServerPlayerIsInterfaceAndArcJarHasNoFakePlayerFilter() throws Exception {
        Class<?> arcSp = Class.forName("com.daqem.arc.api.player.ArcServerPlayer");
        assertTrue(arcSp.isInterface(), "ArcServerPlayer 是接口（ServerPlayer 由 Arc mixin 实现）");

        // Arc 全部 class 字节不得引用 FakePlayer（无过滤）。
        String arcJar = "dev-mods/arc-9.0.0.jar";
        try (ZipFile zip = new ZipFile(arcJar)) {
            var entries = zip.stream().filter(e -> e.getName().endsWith(".class")).toList();
            assertFalse(entries.isEmpty());
            for (ZipEntry entry : entries) {
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
                    assertFalse(ascii.contains("FakePlayer"),
                            "Arc 不得引用 FakePlayer（无排除机制）: " + entry.getName());
                }
            }
        }
    }

    // ---- 辅助 ----

    private static boolean isAssignableToCropBlock(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null) {
            if (c.getName().equals("net.minecraft.world.level.block.CropBlock")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean hasAgeIntegerProperty(Class<?> clazz) throws IOException {
        // 静态字段 AGE（含继承），类型名含 IntegerProperty 即可视为具备 age 属性。
        try {
            Field age = clazz.getField("AGE");
            return age.getType().getName().contains("IntegerProperty");
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
