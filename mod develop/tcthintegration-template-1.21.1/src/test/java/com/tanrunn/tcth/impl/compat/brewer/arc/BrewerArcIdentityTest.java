package com.tanrunn.tcth.impl.compat.brewer.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.type.IActionDataType;
import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.impl.compat.brewer.arc.condition.BeverageTierCondition;
import com.tanrunn.tcth.impl.compat.brewer.arc.condition.BrewerRewardsEnabledCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.TcthArcRegistrar;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.AutomatedCondition;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 7C.1 identity and real-condition tests:
 * <ul>
 *   <li>the brewer side reuses the SAME data type objects as
 *       {@link TcthArcRegistrar} (assertSame, not location-equal);</li>
 *   <li>building a beverage ActionData and executing the REAL
 *       {@link AutomatedCondition} — automated=false matches,
 *       automated=true does not;</li>
 *   <li>COMMON and T2 full condition combos each hit exactly once.</li>
 * </ul>
 */
class BrewerArcIdentityTest {

    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getGameProfile())
                .thenReturn(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "tester"));
        // rewards-enabled condition reads static config suppliers; force all on.
        BrewerRewardsEnabledCondition.frameworkEnabledSupplier = () -> true;
        BrewerRewardsEnabledCondition.integrationEnabledSupplier = () -> true;
        BrewerRewardsEnabledCondition.rewardsEnabledSupplier = () -> true;
    }

    @AfterEach
    void tearDown() {
        BrewerRewardsEnabledCondition.frameworkEnabledSupplier = () -> com.tanrunn.tcth.Config.ENABLED.get();
        BrewerRewardsEnabledCondition.integrationEnabledSupplier =
                () -> com.tanrunn.tcth.Config.BREWER_INTEGRATION_ENABLED.get();
        BrewerRewardsEnabledCondition.rewardsEnabledSupplier =
                () -> com.tanrunn.tcth.Config.BREWER_REWARDS_ENABLED.get();
        BrewerRewardsEnabledCondition.resetThrottleForTesting();
    }

    // ---- identity (assertSame) ----

    @Test
    void brewerDataTypesAreSameObjectsAsTcthArcRegistrar() {
        assertSame(TcthArcRegistrar.RESULT_ITEM_ID, BrewerArcRegistrar.sharedDataType("result_item_id"),
                "brewer must reuse the SAME result_item_id object");
        assertSame(TcthArcRegistrar.COUNT, BrewerArcRegistrar.sharedDataType("count"));
        assertSame(TcthArcRegistrar.RECIPE_ID, BrewerArcRegistrar.sharedDataType("recipe_id"));
        assertSame(TcthArcRegistrar.DEVICE, BrewerArcRegistrar.sharedDataType("device"));
        assertSame(TcthArcRegistrar.TIER, BrewerArcRegistrar.sharedDataType("tier"));
        assertSame(TcthArcRegistrar.AUTOMATED, BrewerArcRegistrar.sharedDataType("automated"));
    }

    @Test
    void dispatcherWritesWithSharedDataTypes() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/tanrunn/tcth/impl/compat/brewer/arc/BeverageActionDispatcher.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("TcthArcRegistrar.RESULT_ITEM_ID"), "dispatcher must write shared data types");
        assertTrue(src.contains("TcthArcRegistrar.COUNT"));
        assertTrue(src.contains("TcthArcRegistrar.TIER"));
        assertTrue(src.contains("TcthArcRegistrar.AUTOMATED"));
    }

    // ---- real AutomatedCondition execution ----

    @Test
    void automatedConditionMatchesFalseAndRejectsTrue() {
        // automated=false action data
        ActionData dataFalse = buildActionData(false);
        AutomatedCondition condFalse = new AutomatedCondition(false, false);
        assertTrue(condFalse.isMet(dataFalse), "automated=false must match for automated=false action");

        // automated=true action data
        ActionData dataTrue = buildActionData(true);
        assertFalse(condFalse.isMet(dataTrue), "automated=false condition must NOT match automated=true action");
    }

    // ---- COMMON / T2 full combos each hit once ----

    @Test
    void commonAndT2FullConditionCombosHit() {
        // COMMON combo: rewards_enabled + tier=COMMON + automated=false
        ActionData commonData = buildActionData(false, BeverageTier.COMMON);
        boolean commonHit = new BrewerRewardsEnabledCondition(false).isMet(commonData)
                && new BeverageTierCondition(false, "COMMON").isMet(commonData)
                && new AutomatedCondition(false, false).isMet(commonData);
        assertTrue(commonHit, "COMMON combo must hit");

        // T2 combo: tier=T2
        ActionData t2Data = buildActionData(false, BeverageTier.T2);
        boolean t2Hit = new BrewerRewardsEnabledCondition(false).isMet(t2Data)
                && new BeverageTierCondition(false, "T2").isMet(t2Data)
                && new AutomatedCondition(false, false).isMet(t2Data);
        assertTrue(t2Hit, "T2 combo must hit");

        // A COMMON-condition must NOT hit a T2 action and vice versa (mutual exclusion).
        assertFalse(new BeverageTierCondition(false, "COMMON").isMet(t2Data),
                "COMMON condition must reject T2 action");
        assertFalse(new BeverageTierCondition(false, "T2").isMet(commonData),
                "T2 condition must reject COMMON action");
    }

    private ActionData buildActionData(boolean automated) {
        return buildActionData(automated, BeverageTier.T2);
    }

    private ActionData buildActionData(boolean automated, BeverageTier tier) {
        BeveragePreparedEvent event = new BeveragePreparedEvent(UUID.randomUUID(), player, null,
                new ItemStack(Items.POTION), BeverageDevice.KEG, tier, automated, level, null);
        return BeverageActionDispatcher.buildActionData(
                Mockito.mock(com.daqem.arc.api.player.ArcPlayer.class), event, tier);
    }
}
