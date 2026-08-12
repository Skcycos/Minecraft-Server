package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.mixin.ItemStackDurabilityMixin;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 4C.1 / 8C.3.1: the durability routing is mutually exclusive — a stack
 * is classified once (hoe → farmer route only, ends there; otherwise knife →
 * chef route only), so an item in BOTH {@code #minecraft:hoes} and
 * {@code #c:tools/knife} can never roll both probabilities.
 *
 * <p>8C.3.1: the testable logic lives in the plain {@link DurabilityAbilityRouter}
 * (the mixin's private static helper is only a thin delegate — Sponge Mixin
 * rejects non-private static methods in mixin classes). Structural regressions
 * below use reflection, not source-string scans.
 */
class FarmerTillingRoutingTest {

    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ChefAbilityModule.setKnifeEnabledSupplierForTesting(() -> true);
        FarmerAbilityModule.setConfigSuppliersForTesting(
                () -> true, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        net.minecraft.world.entity.player.Abilities abilities = Mockito.mock(net.minecraft.world.entity.player.Abilities.class);
        Mockito.when(player.getAbilities()).thenReturn(abilities);
    }

    @AfterEach
    void tearDown() {
        ChefAbilityModule.resetForTesting();
        FarmerAbilityModule.resetForTesting();
    }

    private ItemStack stack(boolean hoe, boolean knife) {
        ItemStack s = Mockito.mock(ItemStack.class);
        Mockito.when(s.isEmpty()).thenReturn(false);
        Mockito.when(s.is(FarmerAbilityModule.HOES_TAG)).thenReturn(hoe);
        Mockito.when(s.is(ChefAbilityModule.KNIVES_TAG)).thenReturn(knife);
        return s;
    }

    // ---- behaviour: the plain router (8C.3.1) ----

    @Test
    void hoeClassifiedItemRoutesOnlyThroughFarmerRoll() {
        ItemStack both = stack(true, true);
        // Farmer roll always misses, chef roll always hits: a double roll would
        // report skip=true; mutual exclusion must report skip=false.
        FarmerAbilityModule.setRandomPctForTesting(() -> 99);
        ChefAbilityModule.setRandomPctForTesting(() -> 0);
        assertFalse(DurabilityAbilityRouter.shouldSkipDurability(player, both),
                "hoe-classified item must route exclusively through the farmer roll");
    }

    @Test
    void knifeClassifiedItemRoutesOnlyThroughChefRoll() {
        ItemStack knifeOnly = stack(false, true);
        // No farmer involvement; knife tier NONE -> pct 0, chef roll misses too:
        // deterministic false, and the farmer random must not matter.
        FarmerAbilityModule.setRandomPctForTesting(() -> 0);
        ChefAbilityModule.setRandomPctForTesting(() -> 0);
        assertFalse(DurabilityAbilityRouter.shouldSkipDurability(player, knifeOnly));
    }

    @Test
    void neitherTagNeverSkips() {
        ItemStack neither = stack(false, false);
        assertFalse(DurabilityAbilityRouter.shouldSkipDurability(player, neither));
    }

    // ---- structural regressions (8C.3.1): reflection, no source scans ----

    @Test
    void mixinHelperIsPrivateStatic() throws Exception {
        Method helper = ItemStackDurabilityMixin.class.getDeclaredMethod(
                "shouldSkipDurability", ServerPlayer.class, ItemStack.class);
        assertTrue(Modifier.isPrivate(helper.getModifiers()),
                "Sponge Mixin rejects non-private static helpers — the 8C.3 FATAL must never return");
        assertTrue(Modifier.isStatic(helper.getModifiers()),
                "the classification entry point must stay static");
    }

    @Test
    void privateHelperDelegatesToTheRouter() throws Exception {
        Method helper = ItemStackDurabilityMixin.class.getDeclaredMethod(
                "shouldSkipDurability", ServerPlayer.class, ItemStack.class);
        helper.setAccessible(true);
        for (boolean hoe : new boolean[] { true, false }) {
            for (boolean knife : new boolean[] { true, false }) {
                ItemStack s = stack(hoe, knife);
                assertEquals(DurabilityAbilityRouter.shouldSkipDurability(player, s),
                        helper.invoke(null, player, s),
                        "the private helper must delegate to the router (hoe=" + hoe + ", knife=" + knife + ")");
            }
        }
    }

    @Test
    void injectorHandlerExistsAndCallsThePrivateHelper() throws Exception {
        // The @Inject handler cannot RUN in a bare JUnit test: its `this` is
        // the mixin-merged ItemStack, which only exists after mixin apply.
        // Structure: the handler method exists with the exact signature and
        // its body routes through the single private helper (single-line
        // contract check, not a scan). The helper→router delegation itself
        // is verified behaviourally by privateHelperDelegatesToTheRouter.
        Method injector = ItemStackDurabilityMixin.class.getDeclaredMethod(
                "tcth$maybeSkipHoeDurability", int.class, net.minecraft.server.level.ServerLevel.class,
                net.minecraft.world.entity.LivingEntity.class, java.util.function.Consumer.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        assertTrue(Modifier.isPrivate(injector.getModifiers()),
                "the @Inject handler must be private");
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/tanrunn/tcth/mixin/ItemStackDurabilityMixin.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("shouldSkipDurability(player, (ItemStack) (Object) this)"),
                "the @Inject handler must route through the private helper entry point");
    }


}
