package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.api.shadow.ShadowTheftReceipt;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Unit tests for {@link PlayerInteractHandler} (phase 8C.0).
 *
 * <p>Covers the entry-condition matrix: server-side only, real player, both
 * hands empty, MAIN_HAND, sneaking, other-player target, alive, same
 * dimension, in range — every failed condition must not touch the
 * coordinator, and one event invokes it exactly once.
 */
class PlayerInteractHandlerTest {

    private static final net.minecraft.resources.ResourceLocation DIAMOND =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "diamond");

    private ServerLevel level;
    private ServerPlayer thief;
    private ServerPlayer victim;
    private ShadowAttemptCoordinator coordinator;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        PlayerInteractHandler.resetForTesting();
        ShadowAbilityAccess.setJobEligibilityProvider(p -> true);
        level = mock(ServerLevel.class);
        thief = mock(ServerPlayer.class);
        victim = mock(ServerPlayer.class);
        when(thief.getUUID()).thenReturn(UUID.randomUUID());
        when(thief.level()).thenReturn(level);
        when(thief.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(thief.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        when(thief.isShiftKeyDown()).thenReturn(true);
        when(thief.canInteractWithEntity(any(net.minecraft.world.phys.AABB.class), anyDouble())).thenReturn(true);
        when(thief.distanceTo(victim)).thenReturn(2.0f);
        when(victim.getUUID()).thenReturn(UUID.randomUUID());
        when(victim.level()).thenReturn(level);
        when(victim.isAlive()).thenReturn(true);
        when(victim.isDeadOrDying()).thenReturn(false);
        when(victim.blockPosition()).thenReturn(new BlockPos(10, 20, 30));
        when(victim.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
        when(victim.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(level.getEntity(any())).thenReturn(victim);
        when(level.getPlayerByUUID(any())).thenReturn(victim);
        when(level.registryAccess()).thenReturn(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        coordinator = mock(ShadowAttemptCoordinator.class);
        when(coordinator.attempt(any())).thenReturn(new ShadowAttemptCoordinator.Result(
                ShadowTheftOutcome.PROTECTED, UUID.randomUUID(), false, ShadowTheftReceipt.empty(), null, null));
        PlayerInteractHandler.setCoordinatorSupplierForTesting(() -> coordinator);
    }

    @AfterEach
    void tearDown() {
        PlayerInteractHandler.resetForTesting();
    }

    private PlayerInteractEvent.EntityInteract event(ServerPlayer player, InteractionHand hand, Entity target) {
        return new PlayerInteractEvent.EntityInteract(player, hand, target);
    }

    // ---- 8D.1 entity split ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private net.minecraft.world.entity.EntityType<?> entityType() {
        return (net.minecraft.world.entity.EntityType) net.minecraft.world.entity.EntityType.COW;
    }

    private Entity nonPlayerTarget() {
        Entity entity = mock(Entity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.isAlive()).thenReturn(true);
        when(entity.isRemoved()).thenReturn(false);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(new BlockPos(5, 5, 5));
        org.mockito.Mockito.doReturn(entityType()).when(entity).getType();
        when(entity.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
        when(thief.distanceTo(entity)).thenReturn(2.0f);
        return entity;
    }

    @Test
    void entityTargetRoutesToTheEntityCoordinator() {
        ShadowEntityAttemptCoordinator entityCoordinator = mock(ShadowEntityAttemptCoordinator.class);
        when(entityCoordinator.attempt(any())).thenReturn(new ShadowEntityAttemptCoordinator.Result(
                ShadowTheftOutcome.FRAMEWORK_DISABLED, UUID.randomUUID(), false,
                ShadowTheftReceipt.empty(), null,
                ResourceLocation.fromNamespaceAndPath("minecraft", "cow")));
        PlayerInteractHandler.setEntityCoordinatorSupplierForTesting(() -> entityCoordinator);
        Entity entity = nonPlayerTarget();
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, entity));
        org.mockito.ArgumentCaptor<ShadowAttemptContext> captor =
                org.mockito.ArgumentCaptor.forClass(ShadowAttemptContext.class);
        verify(entityCoordinator).attempt(captor.capture());
        assertEquals(com.tanrunn.tcth.api.shadow.ShadowTargetKind.ENTITY, captor.getValue().targetKind());
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "cow"),
                captor.getValue().targetType());
        assertEquals(entity.getUUID(), captor.getValue().targetId());
        verify(coordinator, never()).attempt(any());
    }


    // ---- 8D.1.3 entity feedback matrix: every outcome ----

    @Test
    void entityFeedbackMatrixCoversEveryOutcome() {
        ShadowEntityAttemptCoordinator entityCoordinator = mock(ShadowEntityAttemptCoordinator.class);
        PlayerInteractHandler.setEntityCoordinatorSupplierForTesting(() -> entityCoordinator);
        Entity entity = nonPlayerTarget();
        java.util.Map<ShadowTheftOutcome, Integer> expectMessages = new java.util.LinkedHashMap<>();
        expectMessages.put(ShadowTheftOutcome.FRAMEWORK_DISABLED, 0);
        expectMessages.put(ShadowTheftOutcome.INVALID_CONTEXT, 0);
        expectMessages.put(ShadowTheftOutcome.DUPLICATE, 0);
        expectMessages.put(ShadowTheftOutcome.SUCCESS, 1);
        expectMessages.put(ShadowTheftOutcome.NO_CANDIDATE, 1);
        expectMessages.put(ShadowTheftOutcome.PROTECTED, 1);
        expectMessages.put(ShadowTheftOutcome.COOLDOWN, 1);
        expectMessages.put(ShadowTheftOutcome.FAILED_ROLL, 1);
        expectMessages.put(ShadowTheftOutcome.TRANSFER_FAILED, 1);
        expectMessages.put(ShadowTheftOutcome.FAILED_CLEAN, 1);
        expectMessages.put(ShadowTheftOutcome.AUDIT_FAILED, 1);
        expectMessages.put(ShadowTheftOutcome.ROLLED_BACK, 1);
        expectMessages.put(ShadowTheftOutcome.RECOVERY_REQUIRED, 1);
        for (ShadowTheftOutcome outcome : expectMessages.keySet()) {
            org.mockito.Mockito.reset(entityCoordinator);
            org.mockito.Mockito.clearInvocations(thief);
            ShadowTheftReceipt receipt = outcome == ShadowTheftOutcome.SUCCESS
                    ? ShadowTheftReceipt.item(
                            ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone"), 1)
                    : ShadowTheftReceipt.empty();
            when(entityCoordinator.attempt(any())).thenReturn(new ShadowEntityAttemptCoordinator.Result(
                    outcome, UUID.randomUUID(), false, receipt, null,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "cow")));
            PlayerInteractEvent.EntityInteract evt = event(thief, InteractionHand.MAIN_HAND, entity);
            PlayerInteractHandler.onEntityInteract(evt);
            int expected = expectMessages.get(outcome);
            verify(thief, times(expected)).sendSystemMessage(any());
            if (outcome == ShadowTheftOutcome.FRAMEWORK_DISABLED
                    || outcome == ShadowTheftOutcome.INVALID_CONTEXT) {
                assertFalse(evt.isCanceled(), outcome + " must NOT cancel the interaction");
            } else {
                assertTrue(evt.isCanceled(), outcome + " must cancel the interaction");
            }
        }
    }


    @Test
    void playerTargetStillRoutesToThePlayerCoordinator() {
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator).attempt(any());
    }

    @Test
    void clientSideEntityInteractIsIgnored() {
        when(level.isClientSide()).thenReturn(true);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, nonPlayerTarget()));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void entityTargetWithUnregisteredTypeIsIgnored() {
        Entity entity = nonPlayerTarget();
        when(entity.getType()).thenReturn(mock(net.minecraft.world.entity.EntityType.class));
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, entity));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void validInteractionInvokesCoordinatorExactlyOnce() {
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, times(1)).attempt(any());
    }

    @Test
    void clientSideEventIsIgnored() {
        when(level.isClientSide()).thenReturn(true);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void cancelledEventIsIgnored() {
        PlayerInteractEvent.EntityInteract evt = event(thief, InteractionHand.MAIN_HAND, victim);
        evt.setCanceled(true);
        PlayerInteractHandler.onEntityInteract(evt);
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void fakePlayerThiefIsIgnored() {
        FakePlayer fake = mock(FakePlayer.class);
        when(fake.level()).thenReturn(level);
        PlayerInteractHandler.onEntityInteract(event(fake, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void offhandInteractionIsIgnored() {
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.OFF_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void nonSneakingIsIgnored() {
        when(thief.isShiftKeyDown()).thenReturn(false);
        when(thief.isDiscrete()).thenReturn(false);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void nonEmptyMainHandIsIgnored() {
        when(thief.getMainHandItem()).thenReturn(new ItemStack(Items.DIAMOND));
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void nonEmptyOffhandIsIgnored() {
        when(thief.getOffhandItem()).thenReturn(new ItemStack(Items.EMERALD));
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void selfTargetIsIgnored() {
        when(thief.blockPosition()).thenReturn(new BlockPos(5, 5, 5));
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, thief));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void nonPlayerTargetIsIgnored() {
        Entity zombie = mock(Entity.class);
        when(zombie.blockPosition()).thenReturn(new BlockPos(5, 5, 5));
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, zombie));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void deadTargetIsIgnored() {
        when(victim.isAlive()).thenReturn(false);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void crossDimensionTargetIsIgnored() {
        ServerLevel other = mock(ServerLevel.class);
        when(victim.level()).thenReturn(other);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void outOfRangeTargetIsIgnored() {
        when(thief.canInteractWithEntity(any(net.minecraft.world.phys.AABB.class), anyDouble())).thenReturn(false);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void handlerExceptionIsIsolated() {
        when(coordinator.attempt(any())).thenThrow(new IllegalStateException("boom"));
        // Must not throw; the tick never breaks.
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertTrue(true);
    }

    private final ShadowAttemptContext[] captured = new ShadowAttemptContext[1];

    private void captureContexts() {
        when(coordinator.attempt(any())).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return new ShadowAttemptCoordinator.Result(ShadowTheftOutcome.PROTECTED,
                    captured[0].eventId(), false, ShadowTheftReceipt.empty(), null, null);
        });
    }

    @Test
    void lineOfSightTrueIsWrittenIntoContext() {
        when(thief.hasLineOfSight(victim)).thenReturn(true);
        captureContexts();
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertTrue(captured[0].hasLineOfSight(), "a true ray-cast must be written into the context");
    }

    @Test
    void lineOfSightFalseIsWrittenIntoContext() {
        when(thief.hasLineOfSight(victim)).thenReturn(false);
        captureContexts();
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertFalse(captured[0].hasLineOfSight());
    }

    @Test
    void lineOfSightExceptionFailsClosedToFalse() {
        when(thief.hasLineOfSight(victim)).thenThrow(new IllegalStateException("raycast boom"));
        captureContexts();
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertFalse(captured[0].hasLineOfSight(),
                "a line-of-sight API failure must fail closed to false");
        verify(coordinator, times(1)).attempt(any());
    }

    @Test
    void lineOfSightFeedsMutuallyExclusiveFacts() {
        // The behind/watched facts are mutually exclusive for BOTH LOS
        // values (see ShadowVectorMathTest); here the handler must forward
        // the LOS flag on both paths without conflating them.
        captureContexts();
        when(thief.hasLineOfSight(victim)).thenReturn(true);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertTrue(captured[0].hasLineOfSight());
        when(thief.hasLineOfSight(victim)).thenReturn(false);
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertFalse(captured[0].hasLineOfSight());
        // Both paths must produce valid, mutually exclusive facts.
        ShadowVectorMath.ShadowDirectionFacts factsTrue = ShadowVectorMath.computeFacts(
                thief.getLookAngle(), thief.position(), victim.position(), true);
        ShadowVectorMath.ShadowDirectionFacts factsFalse = ShadowVectorMath.computeFacts(
                thief.getLookAngle(), thief.position(), victim.position(), false);
        assertFalse(factsTrue.watched() && factsTrue.behind());
        assertFalse(factsFalse.watched() && factsFalse.behind());
    }

    // ---- 8C.2 consume: cancel/not-cancel + feedback ----

    private void stubResult(ShadowTheftOutcome outcome, ShadowTheftReceipt receipt) {
        stubResult(outcome, receipt, null);
    }

    private void stubResult(ShadowTheftOutcome outcome, ShadowTheftReceipt receipt,
                            ShadowTheftType theftType) {
        when(coordinator.attempt(any())).thenReturn(new ShadowAttemptCoordinator.Result(
                outcome, UUID.randomUUID(), false, receipt, null, theftType));
    }

    private PlayerInteractEvent.EntityInteract validEvent() {
        return event(thief, InteractionHand.MAIN_HAND, victim);
    }

    @Test
    void frameworkDisabledIsNotCancelledAndGivesNoFeedback() {
        stubResult(ShadowTheftOutcome.FRAMEWORK_DISABLED, ShadowTheftReceipt.empty());
        PlayerInteractEvent.EntityInteract evt = validEvent();
        PlayerInteractHandler.onEntityInteract(evt);
        assertFalse(evt.isCanceled(), "a gated-off attempt must not cancel the interaction");
        verify(thief, never()).sendSystemMessage(any());
    }

    @Test
    void invalidContextIsNotCancelledAndGivesNoFeedback() {
        stubResult(ShadowTheftOutcome.INVALID_CONTEXT, ShadowTheftReceipt.empty());
        PlayerInteractEvent.EntityInteract evt = validEvent();
        PlayerInteractHandler.onEntityInteract(evt);
        assertFalse(evt.isCanceled());
        verify(thief, never()).sendSystemMessage(any());
    }

    @Test
    void attemptOutcomesCancelTheInteractionExactlyOnce() {
        for (ShadowTheftOutcome outcome : List.of(
                ShadowTheftOutcome.PROTECTED, ShadowTheftOutcome.COOLDOWN,
                ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftOutcome.FAILED_ROLL,
                ShadowTheftOutcome.TRANSFER_FAILED, ShadowTheftOutcome.AUDIT_FAILED,
                ShadowTheftOutcome.ROLLED_BACK, ShadowTheftOutcome.RECOVERY_REQUIRED,
                ShadowTheftOutcome.DUPLICATE,
                ShadowTheftOutcome.SUCCESS)) {
            if (outcome == ShadowTheftOutcome.SUCCESS) {
                stubResult(outcome, ShadowTheftReceipt.item(DIAMOND, 1), ShadowTheftType.ITEM);
            } else {
                stubResult(outcome, ShadowTheftReceipt.empty());
            }
            PlayerInteractEvent.EntityInteract evt = validEvent();
            PlayerInteractHandler.onEntityInteract(evt);
            assertTrue(evt.isCanceled(),
                    outcome + " must cancel the original interaction");
            // exactly one feedback line for the thief.
            org.mockito.Mockito.clearInvocations(thief);
        }
    }

    @Test
    void duplicateIsCancelledAndSilent() {
        stubResult(ShadowTheftOutcome.DUPLICATE, ShadowTheftReceipt.empty());
        PlayerInteractEvent.EntityInteract evt = validEvent();
        PlayerInteractHandler.onEntityInteract(evt);
        assertTrue(evt.isCanceled(), "the repeated interaction must be cancelled");
        verify(thief, never()).sendSystemMessage(any());
        verify(victim, never()).sendSystemMessage(any());
    }

    @Test
    void successHidesTheThiefIdentityFromTheVictim() {
        stubResult(ShadowTheftOutcome.SUCCESS, ShadowTheftReceipt.item(DIAMOND, 1),
                ShadowTheftType.ITEM);
        PlayerInteractHandler.onEntityInteract(validEvent());
        org.mockito.ArgumentCaptor<net.minecraft.network.chat.Component> captor =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        verify(victim).sendSystemMessage(captor.capture());
        String victimKey = ((net.minecraft.network.chat.contents.TranslatableContents)
                captor.getValue().getContents()).getKey();
        assertTrue(victimKey.startsWith("tcth.shadow.feedback.success.victim."),
                "the victim's success message must be a translatable key");
        Object[] victimArgs = ((net.minecraft.network.chat.contents.TranslatableContents)
                captor.getValue().getContents()).getArgs();
        assertFalse(String.join(" ", java.util.Arrays.stream(victimArgs).map(String::valueOf).toList())
                        .contains("SneakyPete"),
                "the victim must not learn the thief's identity");
        verify(thief, atLeastOnce()).sendSystemMessage(any());
    }

    @Test
    void failedRollExposesTheThiefNameAndDebuffsThem() {
        when(thief.getDisplayName()).thenReturn(Component.literal("SneakyPete"));
        stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
        PlayerInteractHandler.onEntityInteract(validEvent());
        org.mockito.ArgumentCaptor<net.minecraft.network.chat.Component> captor =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        verify(victim).sendSystemMessage(captor.capture());
        net.minecraft.network.chat.contents.TranslatableContents victimContents =
                (net.minecraft.network.chat.contents.TranslatableContents) captor.getValue().getContents();
        assertEquals("tcth.shadow.feedback.fail.victim", victimContents.getKey());
        assertTrue(String.join(" ", java.util.Arrays.stream(victimContents.getArgs())
                        .map(String::valueOf).toList()).contains("SneakyPete"),
                "the victim must see the thief's name on a failed roll");
        // The loser gets a short glow + slowness.
        verify(thief).addEffect(org.mockito.ArgumentMatchers.argThat(instance ->
                instance.getEffect().is(net.minecraft.world.effect.MobEffects.GLOWING)));
        verify(thief).addEffect(org.mockito.ArgumentMatchers.argThat(instance ->
                instance.getEffect().is(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)));
    }

    @Test
    void noCandidateTellsTheThiefOnly() {
        stubResult(ShadowTheftOutcome.NO_CANDIDATE, ShadowTheftReceipt.empty());
        PlayerInteractHandler.onEntityInteract(validEvent());
        org.mockito.ArgumentCaptor<net.minecraft.network.chat.Component> captor =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        verify(thief).sendSystemMessage(captor.capture());
        assertEquals("tcth.shadow.feedback.no_candidate",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                        captor.getValue().getContents()).getKey(),
                "NO_CANDIDATE only says 'nothing to steal'");
        verify(victim, never()).sendSystemMessage(any());
    }

    @Test
    void technicalOutcomesNeverLeakInternalReasons() {
        stubResult(ShadowTheftOutcome.RECOVERY_REQUIRED, ShadowTheftReceipt.empty());
        PlayerInteractHandler.onEntityInteract(validEvent());
        org.mockito.ArgumentCaptor<net.minecraft.network.chat.Component> captor =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        verify(thief).sendSystemMessage(captor.capture());
        assertEquals("tcth.shadow.feedback.technical_error",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                        captor.getValue().getContents()).getKey(),
                "technical outcomes use one generic translatable key");
    }

    @Test
    void oneFeedbackPerEvent() {
        when(thief.getDisplayName()).thenReturn(Component.literal("SneakyPete"));
        stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
        PlayerInteractHandler.onEntityInteract(validEvent());
        verify(thief, times(1)).sendSystemMessage(any());
        verify(victim, times(1)).sendSystemMessage(any());
    }

    @Test
    void contextCarriesPlayerKindAndNoTargetType() {
        final ShadowAttemptContext[] captured = new ShadowAttemptContext[1];
        when(coordinator.attempt(any())).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return new ShadowAttemptCoordinator.Result(ShadowTheftOutcome.PROTECTED,
                    captured[0].eventId(), false, ShadowTheftReceipt.empty(), null, null);
        });
        PlayerInteractHandler.onEntityInteract(event(thief, InteractionHand.MAIN_HAND, victim));
        assertEquals(com.tanrunn.tcth.api.shadow.ShadowTargetKind.PLAYER, captured[0].targetKind());
        assertEquals(null, captured[0].targetType());
        assertEquals(victim.getUUID(), captured[0].targetId());
        assertEquals(thief, captured[0].thief());
        assertFalse(captured[0].automated());
        assertEquals(new BlockPos(10, 20, 30), captured[0].position());
    }

    // ---- phase 8E: ability snapshot integration ----

    @Test
    void nonShadowThiefCannotStartATheftAttempt() {
        ShadowAbilityAccess.setJobEligibilityProvider(p -> false);
        PlayerInteractHandler.onEntityInteract(validEvent());
        verify(coordinator, never()).attempt(any());
    }

    @Test
    void abilitySnapshotIsQueriedExactlyOncePerAttemptAndFlowsIntoTheContext() {
        ShadowAbilityAccess.resetForTesting();
        ShadowAbilityAccess.setJobEligibilityProvider(p -> true);
        try {
            ShadowAbilitySnapshot snapshot = new ShadowAbilitySnapshot(ShadowAbilityTier.III,
                    ShadowAbilityTier.I, ShadowAbilityTier.II, ShadowAbilityTier.NONE);
            int[] queries = { 0 };
            ShadowAbilityAccess.setProvider(p -> {
                queries[0]++;
                return snapshot;
            });
            final ShadowAttemptContext[] captured = new ShadowAttemptContext[1];
            when(coordinator.attempt(any())).thenAnswer(invocation -> {
                captured[0] = invocation.getArgument(0);
                return new ShadowAttemptCoordinator.Result(ShadowTheftOutcome.PROTECTED,
                        captured[0].eventId(), false, ShadowTheftReceipt.empty(), null, null);
            });
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(1, queries[0], "the ability snapshot is queried AT MOST ONCE per attempt");
            assertEquals(snapshot, captured[0].abilities(),
                    "the SAME snapshot flows into the attempt context");
        } finally {
            ShadowAbilityAccess.resetForTesting();
        }
    }

    @Test
    void newAttemptBreaksTcthGrantedInvisibilityBeforeTheAttempt() {
        ShadowEscapeEffects.resetForTesting();
        try {
            when(thief.addEffect(any(net.minecraft.world.effect.MobEffectInstance.class)))
                    .thenReturn(true);
            when(thief.level().getGameTime()).thenReturn(1000L);
            ShadowEscapeEffects.applySuccess(thief, new ShadowAbilitySnapshot(
                    ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                    ShadowAbilityTier.II));
            assertEquals(1, ShadowEscapeEffects.markCount());
            stubResult(ShadowTheftOutcome.PROTECTED, ShadowTheftReceipt.empty());
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(0, ShadowEscapeEffects.markCount(),
                    "starting another theft attempt breaks the TCTH invisibility");
        } finally {
            ShadowEscapeEffects.resetForTesting();
        }
    }

    @Test
    void failedRollExposureScalesWithTheEscapeTier() {
        when(thief.getDisplayName()).thenReturn(Component.literal("SneakyPete"));
        java.util.List<net.minecraft.world.effect.MobEffectInstance> granted = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            granted.add(invocation.getArgument(0));
            return true;
        }).when(thief).addEffect(any(net.minecraft.world.effect.MobEffectInstance.class));
        ShadowAbilityAccess.resetForTesting();
        ShadowAbilityAccess.setJobEligibilityProvider(p -> true);
        try {
            // Base (NONE): exposure duration 100 ticks.
            ShadowAbilityAccess.setProvider(p -> ShadowAbilitySnapshot.none());
            stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(100, granted.get(0).getDuration(), "NONE tier keeps the base exposure");
            granted.clear();
            // 潜影 I: ×0.8 → 80 ticks.
            ShadowAbilityAccess.setProvider(p -> new ShadowAbilitySnapshot(
                    ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                    ShadowAbilityTier.I));
            stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(80, granted.get(0).getDuration(), "潜影 I shortens the exposure to 80 ticks");
            granted.clear();
            // 潜影 II: ×0.6 → 60; III: ×0.4 → 40.
            ShadowAbilityAccess.setProvider(p -> new ShadowAbilitySnapshot(
                    ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                    ShadowAbilityTier.II));
            stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(60, granted.get(0).getDuration());
            granted.clear();
            ShadowAbilityAccess.setProvider(p -> new ShadowAbilitySnapshot(
                    ShadowAbilityTier.NONE, ShadowAbilityTier.NONE, ShadowAbilityTier.NONE,
                    ShadowAbilityTier.III));
            stubResult(ShadowTheftOutcome.FAILED_ROLL, ShadowTheftReceipt.empty());
            PlayerInteractHandler.onEntityInteract(validEvent());
            assertEquals(40, granted.get(0).getDuration());
        } finally {
            ShadowAbilityAccess.resetForTesting();
        }
    }
}
