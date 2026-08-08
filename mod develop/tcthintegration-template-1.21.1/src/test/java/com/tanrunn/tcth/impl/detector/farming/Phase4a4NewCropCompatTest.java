package com.tanrunn.tcth.impl.detector.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tanrunn.tcth.api.farming.CropHarvestedEvent;
import com.tanrunn.tcth.impl.event.CropHarvestedEventDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Phase 4A.4: Neapolitan / Dungeons Delight / My Nether's Delight crop
 * compatibility — special right-click rules, double-plant position
 * normalization, dependency isolation, and exclusion hygiene.
 */
class Phase4a4NewCropCompatTest {

    private static final Gson GSON = new Gson();

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private FakePlayer fakePlayer;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        CropHarvestedEventDispatcher.resetForTesting();
        bus = mock(IEventBus.class);
        CropHarvestedEventDispatcher.setGameBusForTesting(bus);
        CropHarvestedEventDispatcher.setEnabledSupplierForTesting(() -> true);
        CropHarvestedEventDispatcher.setFarmingEnabledSupplierForTesting(() -> true);
        level = mock(ServerLevel.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        fakePlayer = mock(FakePlayer.class);
        when(fakePlayer.getUUID()).thenReturn(java.util.UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        CropHarvestedEventDispatcher.resetForTesting();
    }

    // ---- A. Version / dependency boundary ----

    @Test
    void optionalModJarsHaveExpectedVersions() throws Exception {
        // Parse each JAR's embedded neoforge.mods.toml — do not trust filenames alone.
        assertModMetadata("dev-mods/neapolitan-6.0.1.jar", "neapolitan", "6.0.1");
        assertModMetadata("dev-mods/dungeonsdelight-1.5.0.jar", "dungeonsdelight", "1.5.0");
        assertModMetadata("dev-mods/mynethersdelight-1.10.4.jar", "mynethersdelight", "1.10.4");
    }

    /**
     * Reads {@code META-INF/neoforge.mods.toml} (or {@code mods.toml}) from a
     * JAR and asserts {@code modId} / {@code version} match the server pin.
     */
    private static void assertModMetadata(String jarPath, String expectedModId, String expectedVersion)
            throws Exception {
        Path path = Path.of(jarPath);
        assertTrue(Files.exists(path), "missing " + jarPath);
        String toml = readJarModsToml(path);
        assertTrue(toml.contains("modId=\"" + expectedModId + "\"")
                        || toml.contains("modId = \"" + expectedModId + "\""),
                jarPath + " must declare modId=" + expectedModId);
        assertTrue(toml.contains("version=\"" + expectedVersion + "\"")
                        || toml.contains("version = \"" + expectedVersion + "\""),
                jarPath + " must declare version=" + expectedVersion + "; got metadata snippet: "
                        + toml.substring(0, Math.min(400, toml.length())));
    }

    private static String readJarModsToml(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                entry = jar.getEntry("META-INF/mods.toml");
            }
            assertTrue(entry != null, jarPath + " missing META-INF/*mods.toml");
            try (var in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void optionalDependencyVersionRangesArePinnedToValidatedMajors() throws Exception {
        // Prevent unvalidated future minor versions from still matching exact-class mixins.
        String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"),
                StandardCharsets.UTF_8);
        assertTrue(toml.contains("versionRange=\"[6.0.1,6.1.0)\""),
                "neapolitan must be [6.0.1,6.1.0)");
        assertTrue(toml.contains("versionRange=\"[1.5.0,1.6.0)\""),
                "dungeonsdelight must be [1.5.0,1.6.0)");
        assertTrue(toml.contains("versionRange=\"[1.10.4,1.11.0)\""),
                "mynethersdelight must be [1.10.4,1.11.0)");
        // Open-ended ranges for these mods are no longer allowed.
        assertFalse(toml.contains("modId=\"neapolitan\"") && toml.contains("versionRange=\"[6.0.1,)\""));
        // Spot-check each dependency block by requiring the closed upper bound appears after the modId.
        assertVersionRangeAfterModId(toml, "neapolitan", "[6.0.1,6.1.0)");
        assertVersionRangeAfterModId(toml, "dungeonsdelight", "[1.5.0,1.6.0)");
        assertVersionRangeAfterModId(toml, "mynethersdelight", "[1.10.4,1.11.0)");
    }

    private static void assertVersionRangeAfterModId(String toml, String modId, String range) {
        int modIdx = toml.indexOf("modId=\"" + modId + "\"");
        assertTrue(modIdx >= 0, "missing modId " + modId);
        String after = toml.substring(modIdx, Math.min(toml.length(), modIdx + 400));
        assertTrue(after.contains("versionRange=\"" + range + "\""),
                modId + " dependency block must use versionRange=\"" + range + "\"");
    }

    @Test
    void neapolitanAndMndMixinConfigsGatedByRequiredMods() throws Exception {
        JsonObject nea = GSON.fromJson(Files.readString(
                Path.of("src/main/resources/neapolitan_farming_compat.mixins.json"), StandardCharsets.UTF_8),
                JsonObject.class);
        assertTrue(nea.getAsJsonArray("mixins").toString().contains("StrawberryBushBlockMixin"));
        assertTrue(nea.getAsJsonArray("mixins").toString().contains("MintBlockMixin"));

        JsonObject mnd = GSON.fromJson(Files.readString(
                Path.of("src/main/resources/mynethersdelight_farming_compat.mixins.json"), StandardCharsets.UTF_8),
                JsonObject.class);
        assertTrue(mnd.getAsJsonArray("mixins").toString().contains("PowderyCaneBlockMixin"));
        assertTrue(mnd.getAsJsonArray("mixins").toString().contains("PowderyCannonBlockMixin"));

        String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"),
                StandardCharsets.UTF_8);
        assertTrue(toml.contains("neapolitan_farming_compat.mixins.json"));
        assertTrue(toml.contains("requiredMods=[\"neapolitan\"]"));
        assertTrue(toml.contains("mynethersdelight_farming_compat.mixins.json"));
        assertTrue(toml.contains("requiredMods=[\"mynethersdelight\"]"));
        assertTrue(toml.contains("MushroomColonyBlockMixin")
                        || Files.readString(Path.of("src/main/resources/farmersdelight_compat.mixins.json"))
                        .contains("MushroomColonyBlockMixin"));
        // optional dependency declarations
        assertTrue(toml.contains("modId=\"neapolitan\"") && toml.contains("type=\"optional\""));
        assertTrue(toml.contains("modId=\"dungeonsdelight\""));
        assertTrue(toml.contains("modId=\"mynethersdelight\""));
    }

    @Test
    void mainPublicApiHasZeroThirdPartyImports() throws Exception {
        Path api = Path.of("src/main/java/com/tanrunn/tcth/api");
        try (var walk = Files.walk(api)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    assertFalse(src.contains("com.teamabnormals.neapolitan"), p + " imports Neapolitan");
                    assertFalse(src.contains("net.yirmiri.dungeonsdelight"), p + " imports DD");
                    assertFalse(src.contains("com.soytutta.mynethersdelight"), p + " imports MND");
                    assertFalse(src.contains("com.bmt.kaleidoscope_compat"), p + " imports KC-compat");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        // Common farming package must stay free of third-party type imports.
        for (String rel : new String[]{
                "src/main/java/com/tanrunn/tcth/impl/detector/farming/CropHarvestRules.java",
                "src/main/java/com/tanrunn/tcth/impl/detector/farming/CropBreakDetector.java",
                "src/main/java/com/tanrunn/tcth/impl/detector/farming/HarvestInteractionMixinSupport.java",
                "src/main/java/com/tanrunn/tcth/impl/event/CropHarvestedEventDispatcher.java",
                "src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/FarmerRewardModule.java"
        }) {
            String src = Files.readString(Path.of(rel), StandardCharsets.UTF_8);
            assertFalse(src.contains("import com.teamabnormals.neapolitan"), rel);
            assertFalse(src.contains("import net.yirmiri.dungeonsdelight"), rel);
            assertFalse(src.contains("import com.soytutta.mynethersdelight"), rel);
        }
    }

    @Test
    void thirdPartyTypesOnlyInCompatMixinPackages() throws Exception {
        try (var walk = Files.walk(Path.of("src/main/java"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String path = p.toString().replace('\\', '/');
                try {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    boolean nea = src.contains("import com.teamabnormals.neapolitan");
                    boolean mnd = src.contains("import com.soytutta.mynethersdelight");
                    boolean dd = src.contains("import net.yirmiri.dungeonsdelight");
                    if (nea) {
                        assertTrue(path.contains("/mixin/neapolitan/"),
                                "Neapolitan import only allowed in mixin.neapolitan: " + path);
                    }
                    if (mnd) {
                        assertTrue(path.contains("/mixin/mynethersdelight/"),
                                "MND import only allowed in mixin.mynethersdelight: " + path);
                    }
                    // Phase 6B: DD monster-pot mixins may import yirmiri types only in
                    // mixin.dungeonsdelight (still gated by requiredMods).
                    if (dd) {
                        assertTrue(path.contains("/mixin/dungeonsdelight/"),
                                "DD import only allowed in mixin.dungeonsdelight: " + path);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ---- B. Neapolitan-style max-age right-click (generic handleReturn) ----

    @Test
    void matureSweetBerryStyleRightClickPublishesOnce() {
        // Proxy for strawberry/mint: max-age age decrease 3→1 (sweet berry) /
        // 6→1 / 4→1 share the same support path.
        BlockState mature = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        BlockState after = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, mature, player, true);
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    @Test
    void immatureOrFailedOrFakePlayerRightClickIsZero() {
        BlockState mature = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        BlockState after = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);

        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO,
                Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1), player, true);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, mature, player, false);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, mature, fakePlayer, true);
        when(level.isClientSide()).thenReturn(true);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, mature, player, true);
        verify(bus, never()).post(any());
    }

    @Test
    void noAgeDecreaseIsZero() {
        BlockState mature = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(mature);
        HarvestInteractionMixinSupport.handleReturn(level, BlockPos.ZERO, mature, player, true);
        verify(bus, never()).post(any());
    }

    // ---- C. Double-plant position normalization (Rotbulb) ----

    @Test
    void normalizeDoublePlantUpperGoesToLower() {
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                .setValue(BlockStateProperties.AGE_4, 4);
        BlockPos upperPos = new BlockPos(3, 10, 3);
        assertEquals(upperPos.below(), CropBreakDetector.normalizeDoublePlantPosition(upperPos, upper));
    }

    @Test
    void normalizeDoublePlantLowerUnchanged() {
        BlockState lower = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.AGE_4, 4);
        BlockPos lowerPos = new BlockPos(3, 9, 3);
        assertEquals(lowerPos, CropBreakDetector.normalizeDoublePlantPosition(lowerPos, lower));
    }

    @Test
    void upperAndLowerBreakSameTickPublishAtMostOnce() {
        // Position normalization maps upper→lower; dispatcher key then dedupes.
        BlockState mature = Blocks.WHEAT.defaultBlockState().setValue(
                net.minecraft.world.level.block.CropBlock.AGE, 7);
        BlockPos lower = new BlockPos(2, 5, 2);
        BlockPos upper = lower.above();
        ResourceLocation rotbulb = ResourceLocation.fromNamespaceAndPath("dungeonsdelight", "rotbulb_crop");
        BlockPos normUpper = CropBreakDetector.normalizeDoublePlantPosition(upper,
                Blocks.PITCHER_CROP.defaultBlockState()
                        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        BlockPos normLower = CropBreakDetector.normalizeDoublePlantPosition(lower,
                Blocks.PITCHER_CROP.defaultBlockState()
                        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        assertEquals(normLower, normUpper);
        assertEquals(CropHarvestedEventDispatcher.Result.POSTED,
                CropHarvestedEventDispatcher.publish(player, rotbulb, mature, normLower, level,
                        com.tanrunn.tcth.api.farming.HarvestMethod.BREAK, true));
        assertEquals(CropHarvestedEventDispatcher.Result.DUPLICATE,
                CropHarvestedEventDispatcher.publish(player, rotbulb, mature, normUpper, level,
                        com.tanrunn.tcth.api.farming.HarvestMethod.BREAK, true));
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    // ---- D. Powdery cane / cannon evidence matrix (JAR-aligned rules) ----

    @Test
    void powderyCaneAge2Or3LitTrueWithResetIsEvidence() {
        assertTrue(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(2, true, 0, false));
        assertTrue(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(3, true, 0, false));
    }

    @Test
    void powderyCaneNegativeEvidenceMatrix() {
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(1, true, 0, false),
                "age insufficient");
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(0, true, 0, false));
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(2, false, 0, false),
                "lit false");
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(2, true, 2, true),
                "no reset");
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(2, true, 0, true),
                "still lit");
        assertFalse(HarvestInteractionMixinSupport.isPowderyCaneHarvestEvidence(2, true, 2, false),
                "age not lowered");
    }

    @Test
    void powderyCannonLitTrueToFalseIsEvidence() {
        assertTrue(HarvestInteractionMixinSupport.isPowderyCannonHarvestEvidence(true, false));
    }

    @Test
    void powderyCannonNegativeEvidenceMatrix() {
        assertFalse(HarvestInteractionMixinSupport.isPowderyCannonHarvestEvidence(false, false));
        assertFalse(HarvestInteractionMixinSupport.isPowderyCannonHarvestEvidence(true, true));
        assertFalse(HarvestInteractionMixinSupport.isPowderyCannonHarvestEvidence(false, true));
    }

    @Test
    void powderyHandlersRejectFailedOrFakeWithoutPublishing() {
        // Without a real dual-property block after registry freeze, gate paths
        // are covered by evidence matrix + success helper tests. Handler still
        // rejects failed return / fake player before reading properties.
        HarvestInteractionMixinSupport.handlePowderyCaneReturn(
                level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), player, false);
        HarvestInteractionMixinSupport.handlePowderyCaneReturn(
                level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), fakePlayer, true);
        HarvestInteractionMixinSupport.handlePowderyCannonReturn(
                level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), player, false);
        HarvestInteractionMixinSupport.handlePowderyCannonReturn(
                level, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), fakePlayer, true);
        verify(bus, never()).post(any());
    }

    // ---- E. Colony harvest (age > 0, tag gate) ----

    @Test
    void colonyAgePositiveWithDecreasePublishesWhenTagAllowed() {
        BlockState old = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 2);
        BlockState after = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO, old, player, true, true);
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    /**
     * Covers settlement gates that {@link HarvestInteractionMixinSupport#handleColonyReturn}
     * can observe: tag denied, age 0, no age decrease, failed interaction.
     * Wrong-tool rejection is enforced by Farmer's Delight bytecode before a
     * success return (not simulated here by passing a tool stack).
     */
    @Test
    void colonyTagDeniedAgeZeroNoAgeDropOrFailedReturnIsZero() {
        BlockState old = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 2);
        BlockState after = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);
        // tag denied
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO, old, player, true, false);
        // age 0
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO,
                Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 0), player, true, true);
        // no age decrease
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(old);
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO, old, player, true, true);
        // failed interaction (mod returned non-success — e.g. wrong tool / PASS)
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO, old, player, false, true);
        verify(bus, never()).post(any());
    }

    @Test
    void colonyKnifeFullResetPublishes() {
        BlockState old = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
        BlockState after = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 0);
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(after);
        HarvestInteractionMixinSupport.handleColonyReturn(level, BlockPos.ZERO, old, player, true, true);
        verify(bus, times(1)).post(any(CropHarvestedEvent.class));
    }

    // ---- F. Success helpers accept CONSUME (sidedSuccess server) ----

    @Test
    void successHelpersAcceptConsumeAndSuccess() {
        assertTrue(HarvestInteractionMixinSupport.isSuccess(net.minecraft.world.InteractionResult.SUCCESS));
        assertTrue(HarvestInteractionMixinSupport.isSuccess(net.minecraft.world.InteractionResult.CONSUME));
        assertFalse(HarvestInteractionMixinSupport.isSuccess(net.minecraft.world.InteractionResult.PASS));
        assertTrue(HarvestInteractionMixinSupport.isSuccess(net.minecraft.world.ItemInteractionResult.SUCCESS));
        assertTrue(HarvestInteractionMixinSupport.isSuccess(net.minecraft.world.ItemInteractionResult.CONSUME));
        assertFalse(HarvestInteractionMixinSupport.isSuccess(
                net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION));
    }

    // ---- G. JAR bytecode registry id audit ----

    @Test
    void serverMatchingJarBlockstatesContainExpectedIds() throws Exception {
        assertJarHas("dev-mods/neapolitan-6.0.1.jar",
                "assets/neapolitan/blockstates/strawberry_bush.json",
                "assets/neapolitan/blockstates/mint.json",
                "assets/neapolitan/blockstates/adzuki_sprouts.json");
        assertJarHas("dev-mods/dungeonsdelight-1.5.0.jar",
                "assets/dungeonsdelight/blockstates/rotbulb_crop.json",
                "assets/dungeonsdelight/blockstates/rotten_crop.json",
                "assets/dungeonsdelight/blockstates/rotbulb_plant.json");
        assertJarHas("dev-mods/mynethersdelight-1.10.4.jar",
                "assets/mynethersdelight/blockstates/powdery_cane.json",
                "assets/mynethersdelight/blockstates/powdery_cannon.json",
                "assets/mynethersdelight/blockstates/warped_fungus_colony.json",
                "assets/mynethersdelight/blockstates/crimson_fungus_colony.json",
                "assets/mynethersdelight/blockstates/bullet_pepper.json",
                "assets/mynethersdelight/blockstates/powdery_chubby_sapling.json");
    }

    private static void assertJarHas(String jarPath, String... entries) throws Exception {
        Path path = Path.of(jarPath);
        assertTrue(Files.exists(path), "missing " + jarPath);
        try (JarFile jar = new JarFile(path.toFile())) {
            for (String entry : entries) {
                assertTrue(jar.getEntry(entry) != null, jarPath + " missing " + entry);
            }
        }
    }

    @Test
    void releaseJarTemplateDoesNotShipThirdPartyClassFilesInResources() throws Exception {
        try (var walk = Files.walk(Path.of("src/main/resources"))) {
            assertTrue(walk.filter(Files::isRegularFile)
                    .noneMatch(p -> p.toString().endsWith(".class")
                            || p.toString().contains("teamabnormals")
                            || p.toString().contains("soytutta")
                            || p.toString().contains("yirmiri")));
        }
    }
}
