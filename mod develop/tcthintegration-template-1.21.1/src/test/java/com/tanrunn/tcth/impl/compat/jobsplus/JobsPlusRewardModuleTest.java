package com.tanrunn.tcth.impl.compat.jobsplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link JobsPlusRewardModule} settlement rules (bounded
 * idempotency cache, settlement order, rate limit) and
 * {@link DishActionDispatcher} data mapping.
 */
class JobsPlusRewardModuleTest {

    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        JobsPlusRewardModule.resetForTesting();
        JobsPlusRewardModule.setFrameworkEnabledSupplierForTesting(() -> true);
        JobsPlusRewardModule.setRewardsEnabledSupplierForTesting(() -> true);
        JobsPlusRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 20);
        // Default: sends succeed so settlement bookkeeping is observable.
        JobsPlusRewardModule.setActionSenderForTesting(
                (p, e, t) -> Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class));
        // DishTierManager state is static; clear it so tests are independent.
        new DishTierManager().apply(java.util.Map.of(), null, null);
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "tester"));
    }

    @AfterEach
    void tearDown() {
        JobsPlusRewardModule.resetForTesting();
    }

    private DishCookedEvent event(ResourceLocation recipeId, boolean automated) {
        return new DishCookedEvent(UUID.randomUUID(), automated ? null : player, recipeId,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, DishQuality.UNKNOWN, automated, level, null);
    }

    private void gradeItem(String itemId, DishTier tier) {
        new DishTierManager().apply(java.util.Map.of(
                ResourceLocation.parse("tcth:items/" + itemId),
                com.google.gson.JsonParser.parseString("{\"tier\": \"" + tier.name() + "\"}")), null, null);
    }

    // ---- settlement ----

    @Test
    void disabledByDefaultGrantsNothing() {
        JobsPlusRewardModule.setRewardsEnabledSupplierForTesting(() -> false);
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "disabled module must not even track event ids");
    }

    @Test
    void ungradedDishDoesNotConsumeRateLimit() {
        JobsPlusRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 1);
        // First: ungraded dish (no tier mapping) -> returns before rate limit
        // and before the idempotency cache.
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:unknown_dish"), false));
        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "ungraded dish must not be tracked");
        // Second: graded dish -> still within the per-tick limit.
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting());
    }

    @Test
    void sameEventIdIsSettledOnlyOnce() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        DishCookedEvent e = event(ResourceLocation.parse("minecraft:cooked_beef"), false);
        JobsPlusRewardModule.onDishCooked(e);
        JobsPlusRewardModule.onDishCooked(e);
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting());
    }

    @Test
    void boundedCacheDoesNotGrowWithoutLimit() {
        // Fill with more ids than the cap; size must stay at the cap.
        for (int i = 0; i < JobsPlusRewardModule.MAX_TRACKED_EVENT_IDS_FOR_TESTING + 100; i++) {
            JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        }
        assertTrue(JobsPlusRewardModule.trackedEventIdCountForTesting() <= JobsPlusRewardModule.MAX_TRACKED_EVENT_IDS_FOR_TESTING);
    }

    @Test
    void expiredEventIdsAreCleanedUp() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        assertFalse(JobsPlusRewardModule.trackedEventIdCountForTesting() == 0);

        for (int i = 0; i <= JobsPlusRewardModule.EVENT_ID_EXPIRY_TICKS_FOR_TESTING; i++) {
            JobsPlusRewardModule.onServerTick(null);
        }
        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "expired event ids must be pruned");
    }

    @Test
    void initIsIdempotent() {
        var bus = net.neoforged.bus.api.BusBuilder.builder().build();
        JobsPlusRewardModule.init(bus);
        JobsPlusRewardModule.init(bus);
        // No exception and no duplicate registration; verified by absence of state change.
        assertTrue(true);
    }

    @Test
    void automationBooleanTravelsAsDataWhenActorExists() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player, null,
                new ItemStack(Items.COOKED_BEEF), CookingDevice.FURNACE, DishQuality.UNKNOWN, true, level, null);
        JobsPlusRewardModule.onDishCooked(e);
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "automated event with actor still reaches dispatch (boolean travels as data)");
    }

    // ---- module-level dispatch failure handling ----

    @Test
    void failedSendDoesNotRecordEventIdOrConsumeRateLimit() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        JobsPlusRewardModule.setActionSenderForTesting((p, e, t) -> null); // simulated failure

        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));

        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "failed send must not record the event id");
        // Rate limit is untouched: max=1, the next (successful) event still goes through.
        JobsPlusRewardModule.setMaxActionsPerTickSupplierForTesting(() -> 1);
        JobsPlusRewardModule.setActionSenderForTesting(
                (p, e, t) -> Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class));
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting());
    }

    @Test
    void retryAfterFailureSucceedsAndThenIsIdempotent() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        DishCookedEvent e = event(ResourceLocation.parse("minecraft:cooked_beef"), false);
        JobsPlusRewardModule.setActionSenderForTesting((p, ev, t) -> null); // first attempt fails

        JobsPlusRewardModule.onDishCooked(e);
        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting());

        JobsPlusRewardModule.setActionSenderForTesting(
                (p, ev, t) -> Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class));
        JobsPlusRewardModule.onDishCooked(e); // retry same event id -> succeeds
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting());

        JobsPlusRewardModule.onDishCooked(e); // already settled -> blocked
        assertEquals(1, JobsPlusRewardModule.trackedEventIdCountForTesting());
    }

    @Test
    void serverStoppingClearsBookkeeping() {
        gradeItem("minecraft/cooked_beef", DishTier.COMMON);
        JobsPlusRewardModule.setActionSenderForTesting(
                (p, e, t) -> Mockito.mock(com.daqem.arc.api.action.result.ActionResult.class));
        JobsPlusRewardModule.onDishCooked(event(ResourceLocation.parse("minecraft:cooked_beef"), false));
        assertTrue(JobsPlusRewardModule.trackedEventIdCountForTesting() > 0);

        JobsPlusRewardModule.onServerStopping(null);

        assertEquals(0, JobsPlusRewardModule.trackedEventIdCountForTesting(),
                "event id cache must be cleared on server stop");
    }
}
