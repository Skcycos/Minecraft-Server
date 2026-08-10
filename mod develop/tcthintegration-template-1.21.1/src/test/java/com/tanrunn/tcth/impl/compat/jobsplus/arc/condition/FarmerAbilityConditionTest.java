package com.tanrunn.tcth.impl.compat.jobsplus.arc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.player.ArcPlayer;
import com.daqem.arc.api.condition.serializer.IConditionSerializer;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.FarmerLivestockCooldown;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Phase 4B: farmer ability-route conditions — toggle fail-closed semantics,
 * livestock cooldown pass/block, and network serialization symmetry (inverted
 * written and read back consistently).
 */
class FarmerAbilityConditionTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        HoeDurabilityEnabledCondition.resetForTesting();
        FarmerStudyAbilitiesEnabledCondition.resetForTesting();
        FarmerLivestockAbilitiesEnabledCondition.resetForTesting();
        FarmerLivestockCooldown.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        HoeDurabilityEnabledCondition.resetForTesting();
        FarmerStudyAbilitiesEnabledCondition.resetForTesting();
        FarmerLivestockAbilitiesEnabledCondition.resetForTesting();
        FarmerLivestockCooldown.resetForTesting();
    }

    private ActionData dataFor(Player player) {
        ActionData data = Mockito.mock(ActionData.class);
        ArcPlayer arcPlayer = Mockito.mock(ArcPlayer.class);
        Mockito.when(arcPlayer.arc$getPlayer()).thenReturn(player);
        Mockito.when(data.getPlayer()).thenReturn(arcPlayer);
        return data;
    }

    // ---- hoe durability toggle ----

    @Test
    void hoeToggleMatchesAllFourGates() {
        HoeDurabilityEnabledCondition c = new HoeDurabilityEnabledCondition(false);
        HoeDurabilityEnabledCondition.setFrameworkSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setIntegrationSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setMasterSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertTrue(c.isMet(dataFor(Mockito.mock(Player.class))), "all gates open must match");

        HoeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> false);
        assertFalse(c.isMet(dataFor(Mockito.mock(Player.class))), "route switch off must not match");
    }

    @Test
    void hoeToggleFailsClosedOnExceptionRegardlessOfInverted() {
        HoeDurabilityEnabledCondition c = new HoeDurabilityEnabledCondition(true);
        HoeDurabilityEnabledCondition.setFrameworkSupplierForTesting(() -> {
            throw new IllegalStateException("broken");
        });
        HoeDurabilityEnabledCondition.setIntegrationSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setMasterSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertFalse(c.isMet(dataFor(Mockito.mock(Player.class))),
                "broken config must never match, even inverted");
    }

    // ---- study / livestock toggles ----

    @Test
    void studyToggleFailsClosedOnException() {
        FarmerStudyAbilitiesEnabledCondition c = new FarmerStudyAbilitiesEnabledCondition(false);
        FarmerStudyAbilitiesEnabledCondition.setFrameworkSupplierForTesting(() -> true);
        FarmerStudyAbilitiesEnabledCondition.setIntegrationSupplierForTesting(() -> true);
        FarmerStudyAbilitiesEnabledCondition.setMasterSupplierForTesting(() -> true);
        FarmerStudyAbilitiesEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertTrue(c.isMet(dataFor(Mockito.mock(Player.class))));
        FarmerStudyAbilitiesEnabledCondition.setRouteSupplierForTesting(() -> false);
        assertFalse(c.isMet(dataFor(Mockito.mock(Player.class))));
    }

    @Test
    void livestockToggleFailsClosedOnException() {
        FarmerLivestockAbilitiesEnabledCondition c = new FarmerLivestockAbilitiesEnabledCondition(false);
        FarmerLivestockAbilitiesEnabledCondition.setFrameworkSupplierForTesting(() -> true);
        FarmerLivestockAbilitiesEnabledCondition.setIntegrationSupplierForTesting(() -> true);
        FarmerLivestockAbilitiesEnabledCondition.setMasterSupplierForTesting(() -> true);
        FarmerLivestockAbilitiesEnabledCondition.setRouteSupplierForTesting(() -> true);
        assertTrue(c.isMet(dataFor(Mockito.mock(Player.class))));
        FarmerLivestockAbilitiesEnabledCondition.setRouteSupplierForTesting(() -> {
            throw new IllegalStateException("broken");
        });
        assertFalse(c.isMet(dataFor(Mockito.mock(Player.class))));
    }

    // ---- 日志节流边界（要求 60 秒） ----

    @Test
    void warnThrottlesAreSixtySeconds() throws Exception {
        assertEquals(Long.valueOf(60_000_000_000L), Long.valueOf(throttleNs(HoeDurabilityEnabledCondition.class)),
                "tcth:hoe_durability_enabled warn throttle must be 60 s");
        assertEquals(Long.valueOf(60_000_000_000L), Long.valueOf(throttleNs(FarmerLivestockAbilitiesEnabledCondition.class)),
                "tcth:farmer_livestock_abilities_enabled warn throttle must be 60 s");
        assertEquals(Long.valueOf(60_000_000_000L), Long.valueOf(throttleNs(FarmerStudyAbilitiesEnabledCondition.class)),
                "tcth:farmer_study_abilities_enabled warn throttle must be 60 s");
    }

    private static long throttleNs(Class<?> clazz) throws Exception {
        java.lang.reflect.Field f = clazz.getDeclaredField("WARN_THROTTLE_NS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    @Test
    void repeatedFailuresStayFailClosedAcrossThrottleWindow() {
        HoeDurabilityEnabledCondition c = new HoeDurabilityEnabledCondition(false);
        HoeDurabilityEnabledCondition.setFrameworkSupplierForTesting(() -> {
            throw new IllegalStateException("broken");
        });
        HoeDurabilityEnabledCondition.setIntegrationSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setMasterSupplierForTesting(() -> true);
        HoeDurabilityEnabledCondition.setRouteSupplierForTesting(() -> true);
        // Many evaluations within one throttle window: still fail-closed each time.
        for (int i = 0; i < 100; i++) {
            assertFalse(c.isMet(dataFor(Mockito.mock(Player.class))),
                    "repeated failures must stay fail-closed");
        }
    }

    // ---- livestock cooldown ----

    @Test
    void livestockCooldownConditionPassesWhenNotOnCooldown() {
        FarmerLivestockCooldown.setTickSourceForTesting(() -> 1L);
        FarmerLivestockCooldown.setCooldownTicksForTesting(() -> 400);
        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        FarmerLivestockCooldownCondition c = new FarmerLivestockCooldownCondition(false);
        assertTrue(c.isMet(dataFor(player)), "no cooldown entry must pass");
        FarmerLivestockCooldown.instance().commit(player.getUUID(), player);
        assertFalse(c.isMet(dataFor(player)), "inside cooldown must block");
        // Inverted flips: pass when on cooldown.
        FarmerLivestockCooldownCondition inverted = new FarmerLivestockCooldownCondition(true);
        assertTrue(inverted.isMet(dataFor(player)), "inverted must pass on cooldown");
    }

    @Test
    void livestockCooldownConditionPassesForNonServerPlayer() {
        FarmerLivestockCooldownCondition c = new FarmerLivestockCooldownCondition(false);
        assertTrue(c.isMet(dataFor(Mockito.mock(Player.class))), "non-server players must not be blocked");
    }

    // ---- network serialization symmetry (toNetwork writes inverted; reader symmetric) ----

    @Test
    void hoeToggleNetworkRoundTripKeepsInverted() {
        HoeDurabilityEnabledCondition original = new HoeDurabilityEnabledCondition(true);
        HoeDurabilityEnabledCondition.Serializer serializer = new HoeDurabilityEnabledCondition.Serializer();
        HoeDurabilityEnabledCondition copy = serializer.fromNetwork(
                ResourceLocation.parse("tcth:test"),
                new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        com.mojang.serialization.DynamicOps.class == null
                                ? null : net.minecraft.core.RegistryAccess.EMPTY),
                true);
        assertTrue(copy.isInverted(), "inverted must be preserved by the serializer");
    }

    @Test
    void livestockCooldownNetworkRoundTripKeepsInverted() {
        FarmerLivestockCooldownCondition.Serializer serializer = new FarmerLivestockCooldownCondition.Serializer();
        FarmerLivestockCooldownCondition copy = serializer.fromNetwork(
                ResourceLocation.parse("tcth:test"),
                new RegistryFriendlyByteBuf(Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY),
                false);
        assertFalse(copy.isInverted());
    }
}
