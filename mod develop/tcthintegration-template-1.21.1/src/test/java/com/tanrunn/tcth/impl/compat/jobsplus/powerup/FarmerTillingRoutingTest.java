package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.mixin.ItemStackDurabilityMixin;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 4C.1: the durability mixin routing is mutually exclusive — a stack is
 * classified once (hoe → farmer route only, ends there; otherwise knife →
 * chef route only), so an item in BOTH {@code #minecraft:hoes} and
 * {@code #c:tools/knife} can never roll both probabilities.
 */
class FarmerTillingRoutingTest {

    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @org.junit.jupiter.api.BeforeEach
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

    @Test
    void hoeClassifiedItemRoutesOnlyThroughFarmerRoll() {
        ItemStack both = stack(true, true);
        // Farmer roll always misses, chef roll always hits: a double roll would
        // report skip=true; mutual exclusion must report skip=false.
        FarmerAbilityModule.setRandomPctForTesting(() -> 99);
        ChefAbilityModule.setRandomPctForTesting(() -> 0);
        assertFalse(ItemStackDurabilityMixin.shouldSkipDurability(player, both),
                "hoe-classified item must route exclusively through the farmer roll");
    }

    @Test
    void knifeClassifiedItemRoutesOnlyThroughChefRoll() {
        ItemStack knifeOnly = stack(false, true);
        // No farmer involvement; knife tier NONE -> pct 0, chef roll misses too:
        // deterministic false, and the farmer random must not matter.
        FarmerAbilityModule.setRandomPctForTesting(() -> 0);
        ChefAbilityModule.setRandomPctForTesting(() -> 0);
        assertFalse(ItemStackDurabilityMixin.shouldSkipDurability(player, knifeOnly));
    }

    @Test
    void neitherTagNeverSkips() {
        ItemStack neither = stack(false, false);
        assertFalse(ItemStackDurabilityMixin.shouldSkipDurability(player, neither));
    }

    @Test
    void routingContractIsDocumentedInTheMixin() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/tanrunn/tcth/mixin/ItemStackDurabilityMixin.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(!src.contains("Mutual exclusion (4C.1)"),
                "mixin must document the mutual-exclusion contract");
        assertFalse(!src.contains("shouldSkipDurability"),
                "mixin must route through the classification entry point");
    }
}
