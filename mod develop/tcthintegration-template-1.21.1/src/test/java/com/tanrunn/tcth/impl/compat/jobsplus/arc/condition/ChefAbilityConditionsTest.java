package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.action.data.type.ActionDataType;
import com.daqem.arc.api.player.ArcPlayer;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * Phase 3D: unit tests for the TCTH ability-tree conditions (fire tag check,
 * config toggles, tasting cooldown).
 */
class ChefAbilityConditionsTest {

    private DamageSource fireSource;
    private DamageSource fallSource;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        // Mocked DamageSource: FireDamageCondition relies only on
        // DamageSource.is(DamageTypeTags.IS_FIRE), which is tag-based and
        // never name-based.
        fireSource = Mockito.mock(DamageSource.class);
        Mockito.when(fireSource.is(DamageTypeTags.IS_FIRE)).thenReturn(true);
        fallSource = Mockito.mock(DamageSource.class);
        Mockito.when(fallSource.is(DamageTypeTags.IS_FIRE)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        ChefAbilitiesEnabledCondition.resetForTesting();
        TastingEffectsEnabledCondition.resetForTesting();
        FireResistanceEnabledCondition.resetForTesting();
        KnifeDurabilityEnabledCondition.resetForTesting();
        ChefTastingCooldown.resetForTesting();
    }

    private ActionData hurtData(DamageSource source) {
        ActionData data = Mockito.mock(ActionData.class);
        Mockito.when(data.getData(ActionDataType.DAMAGE_SOURCE)).thenReturn(source);
        return data;
    }

    // ---- tcth:fire_damage ----

    @Test
    void fireDamageMatchesFireTaggedSourcesOnly() {
        FireDamageCondition condition = new FireDamageCondition(false);
        assertTrue(condition.isMet(hurtData(fireSource)), "in_fire is tagged #minecraft:is_fire");
        assertFalse(condition.isMet(hurtData(fallSource)), "fall is not fire damage");
    }

    @Test
    void fireDamageSupportsInverted() {
        FireDamageCondition inverted = new FireDamageCondition(true);
        assertFalse(inverted.isMet(hurtData(fireSource)));
        assertTrue(inverted.isMet(hurtData(fallSource)));
    }

    @Test
    void fireDamageNeverMatchesMissingSource() {
        ActionData data = Mockito.mock(ActionData.class);
        Mockito.when(data.getData(ActionDataType.DAMAGE_SOURCE)).thenReturn(null);
        assertFalse(new FireDamageCondition(false).isMet(data));
    }

    // ---- config toggles（master + route 组合） ----

    @Test
    void masterSwitchBlocksWhenOff() {
        ChefAbilitiesEnabledCondition.setMasterSupplierForTesting(() -> false);
        assertFalse(new ChefAbilitiesEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new ChefAbilitiesEnabledCondition(true).isMet(Mockito.mock(ActionData.class)),
                "inverted flips the toggle result");
        ChefAbilitiesEnabledCondition.setMasterSupplierForTesting(() -> true);
        assertTrue(new ChefAbilitiesEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new ChefAbilitiesEnabledCondition(true).isMet(Mockito.mock(ActionData.class)));
    }

    @Test
    void masterOffStopsAllFourRoutes() {
        // chefAbilitiesEnabled=false 时，即使各路线开关为 true，四路线全部不生效。
        setAllMasters(false);
        setAllRoutes(true);
        assertFalse(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new FireResistanceEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new ChefAbilitiesEnabledCondition(false).isMet(Mockito.mock(ActionData.class)),
                "master off must also stop the study route");
    }

    @Test
    void routeOffStopsOnlyItsOwnRoute() {
        // master=true；品鉴开关 false → 仅品鉴关闭，炉火/刀工/研修仍生效。
        setAllMasters(true);
        TastingEffectsEnabledCondition.setRouteSupplierForTesting(() -> false);
        FireResistanceEnabledCondition.setRouteSupplierForTesting(() -> true);
        KnifeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertFalse(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new FireResistanceEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new ChefAbilitiesEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));

        // master=true；炉火开关 false → 仅炉火关闭。
        FireResistanceEnabledCondition.setRouteSupplierForTesting(() -> false);
        TastingEffectsEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertTrue(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new FireResistanceEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));

        // master=true；刀工开关 false → 仅刀工关闭。
        KnifeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> false);
        FireResistanceEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertTrue(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new FireResistanceEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertFalse(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
    }

    @Test
    void allSwitchesOnLetsEveryRoutePass() {
        setAllMasters(true);
        setAllRoutes(true);
        assertTrue(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new FireResistanceEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
        assertTrue(new ChefAbilitiesEnabledCondition(false).isMet(Mockito.mock(ActionData.class)));
    }

    @Test
    void routeConditionsFailClosedOnRuntimeException() {
        setAllMasters(true);
        setAllRoutes(true);
        TastingEffectsEnabledCondition.setRouteSupplierForTesting(() -> {
            throw new IllegalStateException("config not loaded");
        });
        assertFalse(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)),
                "a broken route config read must fail CLOSED (disabled), never enable");
    }

    @Test
    void routeConditionsFailClosedOnLinkageError() {
        setAllMasters(true);
        setAllRoutes(true);
        KnifeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> {
            throw new LinkageError("config class missing");
        });
        assertFalse(new KnifeDurabilityEnabledCondition(false).isMet(Mockito.mock(ActionData.class)),
                "a LinkageError during config read must fail CLOSED (disabled)");
    }

    @Test
    void masterConditionsFailClosedOnRuntimeException() {
        TastingEffectsEnabledCondition.setMasterSupplierForTesting(() -> {
            throw new IllegalStateException("master config missing");
        });
        TastingEffectsEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertFalse(new TastingEffectsEnabledCondition(false).isMet(Mockito.mock(ActionData.class)),
                "a broken master config read must fail CLOSED (disabled)");
    }

    private static void setAllMasters(boolean value) {
        ChefAbilitiesEnabledCondition.setMasterSupplierForTesting(() -> value);
        TastingEffectsEnabledCondition.setMasterSupplierForTesting(() -> value);
        FireResistanceEnabledCondition.setMasterSupplierForTesting(() -> value);
        KnifeDurabilityEnabledCondition.setMasterSupplierForTesting(() -> value);
    }

    private static void setAllRoutes(boolean value) {
        TastingEffectsEnabledCondition.setRouteSupplierForTesting(() -> value);
        FireResistanceEnabledCondition.setRouteSupplierForTesting(() -> value);
        KnifeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> value);
    }

    // ---- tcth:tasting_cooldown ----

    @Test
    void cooldownConditionBlocksInsideWindowAndPassesOutside() {
        AtomicLong now = new AtomicLong(0);
        ChefTastingCooldown.setTickSourceForTesting(now::get);
        ChefTastingCooldown.setCooldownTicksForTesting(() -> 400);

        MinecraftServer server = Mockito.mock(MinecraftServer.class);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(level.getServer()).thenReturn(server);
        Mockito.when(server.getTickCount()).thenAnswer(invocation -> now.get());
        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.serverLevel()).thenReturn(level);
        Mockito.when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());

        ArcPlayer arcPlayer = Mockito.mock(ArcPlayer.class);
        Mockito.when(arcPlayer.arc$getPlayer()).thenReturn(player);
        ActionData data = Mockito.mock(ActionData.class);
        Mockito.when(data.getPlayer()).thenReturn(arcPlayer);

        TastingCooldownCondition condition = new TastingCooldownCondition(false);
        assertTrue(condition.isMet(data), "no commit yet -> must pass");

        ChefTastingCooldown.instance().commit(player.getUUID(), player);
        assertFalse(condition.isMet(data), "inside cooldown -> must block");

        now.set(500);
        assertTrue(condition.isMet(data), "after 400 ticks -> must pass again");
    }
}
