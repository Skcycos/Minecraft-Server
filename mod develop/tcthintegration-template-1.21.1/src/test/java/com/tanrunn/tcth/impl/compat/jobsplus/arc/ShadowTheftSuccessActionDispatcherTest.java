package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.player.ArcServerPlayer;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftEvent;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.compat.jobsplus.ShadowSendResult;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Phase 8E: {@link ShadowTheftSuccessActionDispatcher} — the
 * {@code tcth:on_shadow_theft_success} action data carries exactly the
 * attempt facts the data-driven conditions need (kind/type/target/item/
 * numeric/effect/automated), with nullable fields only when present.
 */
class ShadowTheftSuccessActionDispatcherTest {

    private static final ResourceLocation DIAMOND = ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");
    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("minecraft", "speed");
    private static final ResourceLocation COW = ResourceLocation.fromNamespaceAndPath("minecraft", "cow");

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private ShadowTheftEvent event(ShadowTargetKind kind, ShadowTheftType type,
                                   ShadowTheftReceipt receipt, boolean automated) {
        return new ShadowTheftEvent(UUID.randomUUID(), mock(net.minecraft.server.level.ServerPlayer.class),
                kind, UUID.randomUUID(),
                kind == ShadowTargetKind.ENTITY ? COW : null,
                type, ShadowTheftOutcome.SUCCESS, receipt, automated,
                mock(ServerLevel.class), new BlockPos(0, 0, 0));
    }

    @Test
    void itemPlayerEventCarriesItemFieldsOnly() {
        ArcServerPlayer player = mock(ArcServerPlayer.class);
        ActionData data = ShadowTheftSuccessActionDispatcher.buildActionData(player,
                event(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                        ShadowTheftReceipt.item(DIAMOND, 1), false));
        assertNotNull(data);
        assertEquals("PLAYER", data.getData(TcthArcRegistrar.SHADOW_TARGET_KIND));
        assertEquals("ITEM", data.getData(TcthArcRegistrar.SHADOW_THEFT_TYPE));
        assertNull(data.getData(TcthArcRegistrar.SHADOW_TARGET_TYPE), "player targets carry no target_type");
        assertEquals("minecraft:diamond", data.getData(TcthArcRegistrar.SHADOW_ITEM_ID));
        assertEquals(1, data.getData(TcthArcRegistrar.SHADOW_ITEM_COUNT));
        assertEquals(0.0d, data.getData(TcthArcRegistrar.SHADOW_NUMERIC_AMOUNT));
        assertNull(data.getData(TcthArcRegistrar.SHADOW_EFFECT_ID));
        assertEquals(0, data.getData(TcthArcRegistrar.SHADOW_EFFECT_DURATION_TICKS));
        assertEquals(false, data.getData(TcthArcRegistrar.AUTOMATED));
    }

    @Test
    void entityEventCarriesTargetType() {
        ArcServerPlayer player = mock(ArcServerPlayer.class);
        ActionData data = ShadowTheftSuccessActionDispatcher.buildActionData(player,
                event(ShadowTargetKind.ENTITY, ShadowTheftType.ITEM,
                        ShadowTheftReceipt.item(DIAMOND, 2), false));
        assertEquals("ENTITY", data.getData(TcthArcRegistrar.SHADOW_TARGET_KIND));
        assertEquals("minecraft:cow", data.getData(TcthArcRegistrar.SHADOW_TARGET_TYPE));
        assertEquals(2, data.getData(TcthArcRegistrar.SHADOW_ITEM_COUNT));
    }

    @Test
    void healthEventCarriesNumericAmount() {
        ArcServerPlayer player = mock(ArcServerPlayer.class);
        ActionData data = ShadowTheftSuccessActionDispatcher.buildActionData(player,
                event(ShadowTargetKind.PLAYER, ShadowTheftType.HEALTH,
                        ShadowTheftReceipt.numeric(2.0d), false));
        assertEquals("HEALTH", data.getData(TcthArcRegistrar.SHADOW_THEFT_TYPE));
        assertEquals(2.0d, data.getData(TcthArcRegistrar.SHADOW_NUMERIC_AMOUNT));
        assertNull(data.getData(TcthArcRegistrar.SHADOW_ITEM_ID));
        assertNull(data.getData(TcthArcRegistrar.SHADOW_EFFECT_ID));
    }

    @Test
    void effectEventCarriesEffectFields() {
        ArcServerPlayer player = mock(ArcServerPlayer.class);
        ActionData data = ShadowTheftSuccessActionDispatcher.buildActionData(player,
                event(ShadowTargetKind.PLAYER, ShadowTheftType.EFFECT,
                        ShadowTheftReceipt.effect(SPEED, 200), true));
        assertEquals("EFFECT", data.getData(TcthArcRegistrar.SHADOW_THEFT_TYPE));
        assertEquals("minecraft:speed", data.getData(TcthArcRegistrar.SHADOW_EFFECT_ID));
        assertEquals(200, data.getData(TcthArcRegistrar.SHADOW_EFFECT_DURATION_TICKS));
        assertEquals(true, data.getData(TcthArcRegistrar.AUTOMATED));
        assertNull(data.getData(TcthArcRegistrar.SHADOW_ITEM_ID));
        assertEquals(0, data.getData(TcthArcRegistrar.SHADOW_ITEM_COUNT));
    }

    @Test
    void sendFailureReturnsClearFailureWithoutThrowing() {
        // A player that is NOT an ArcServerPlayer makes the cast fail inside
        // the dispatcher — caught and reported as CLEAR_FAILURE, never thrown.
        net.minecraft.server.level.ServerPlayer plain = mock(net.minecraft.server.level.ServerPlayer.class);
        when(plain.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(
                UUID.randomUUID(), "thief"));
        ShadowTheftEvent e = event(ShadowTargetKind.PLAYER, ShadowTheftType.ITEM,
                ShadowTheftReceipt.item(DIAMOND, 1), false);
        assertEquals(ShadowSendResult.CLEAR_FAILURE,
                ShadowTheftSuccessActionDispatcher.sendShadowTheftSuccessAction(plain, e));
    }
}
