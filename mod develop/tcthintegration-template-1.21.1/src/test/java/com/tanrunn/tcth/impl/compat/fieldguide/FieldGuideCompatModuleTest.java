package com.tanrunn.tcth.impl.compat.fieldguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@link FieldGuideCompatModule} unlock gating.
 *
 * <p>The Field Guide public API is replaced by a {@link FakeApi} so no live
 * Field Guide classes, player saves or a running client are required.
 */
class FieldGuideCompatModuleTest {

    private FieldGuideCompatModule module;
    private FakeApi api;
    private ServerPlayer player;
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        module = new FieldGuideCompatModule();
        api = new FakeApi();
        module.setApiForTesting(api);
        module.setFieldGuideEnabledSupplierForTesting(() -> true);
        module.setCookbookEnabledSupplierForTesting(() -> true);
        module.setFrameworkEnabledSupplierForTesting(() -> true);
        module.setCatalogPredicateForTesting(holder -> true);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getName()).thenReturn(Component.literal("TestPlayer"));
        level = Mockito.mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
    }

    private DishCookedEvent event(UUID id, ServerPlayer p, ItemStack result, boolean automated) {
        return new DishCookedEvent(id, p, null, result, CookingDevice.FURNACE,
                DishQuality.STANDARD, automated, level, null);
    }

    // ---- 3. 玩家出锅解锁对应物品 ----

    @Test
    void dishTakeOutUnlocksMatchingEntry() {
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertTrue(api.unlocked.contains("item:minecraft/cooked_cod"),
                "the cooked dish entry must be unlocked");
        assertEquals(1, api.unlockCalls);
        assertEquals(1, module.processedSizeForTesting(),
                "the event id must be committed after a successful unlock");
    }

    // ---- 4. player=null 不解锁 ----

    @Test
    void nullPlayerDoesNotUnlock() {
        DishCookedEvent e = event(UUID.randomUUID(), null,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
        assertTrue(api.unlocked.isEmpty());
    }

    // ---- 5. automated=true 不解锁 ----

    @Test
    void automatedProductionDoesNotUnlock() {
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), true);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
        assertTrue(api.unlocked.isEmpty());
    }

    // ---- 6. 非料理不解锁 ----

    @Test
    void nonDishDoesNotUnlock() {
        // STICK has no food component and is not in tcth:dishes -> not a dish.
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.STICK), false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
    }

    // ---- 7. 非 catalog 物品不解锁 ----

    @Test
    void nonCatalogItemDoesNotUnlock() {
        module.setCatalogPredicateForTesting(holder -> false);
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
    }

    // ---- 8. result count<=0 不解锁 ----

    @Test
    void emptyResultDoesNotUnlock() {
        DishCookedEvent e = event(UUID.randomUUID(), player, ItemStack.EMPTY, false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
    }

    // ---- 9. 重复 eventId 只处理一次 ----

    @Test
    void duplicateEventIdIsProcessedOnce() {
        UUID id = UUID.randomUUID();
        DishCookedEvent first = event(id, player, new ItemStack(Items.COOKED_COD), false);
        DishCookedEvent second = event(id, player, new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(first);
        module.handleDishCooked(second);

        assertEquals(1, api.unlockCalls, "the same event id must unlock only once");
    }

    // ---- 10. 已解锁条目不重复通知 ----

    @Test
    void alreadyUnlockedEntryIsSkippedWithoutDuplicateUnlock() {
        api.alreadyUnlocked = true;
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(1, api.unlockCalls, "unlock() is consulted once");
        assertTrue(module.processedSizeForTesting() == 1,
                "a confirmed already-unlocked entry still commits the event id");
    }

    // ---- 11. 解锁异常不占用 eventId，可重试 ----

    @Test
    void unlockFailureDoesNotCommitEventIdAndIsRetryable() {
        api.throwOnUnlock = true;
        UUID id = UUID.randomUUID();
        DishCookedEvent e = event(id, player, new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(0, module.processedSizeForTesting(),
                "a failed unlock must not consume the event id");

        api.throwOnUnlock = false;
        module.handleDishCooked(event(id, player, new ItemStack(Items.COOKED_COD), false));

        assertTrue(api.unlocked.contains("item:minecraft/cooked_cod"),
                "the event must be retryable after the failure");
        assertEquals(1, module.processedSizeForTesting());
    }

    // ---- 12. 一个解锁异常不影响后续事件 ----

    @Test
    void oneUnlockFailureDoesNotAffectLaterEvents() {
        api.throwOnUnlock = true;
        module.handleDishCooked(event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false));

        api.throwOnUnlock = false;
        module.handleDishCooked(event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_BEEF), false));

        assertTrue(api.unlocked.contains("item:minecraft/cooked_beef"),
                "a failing dish must not block later dishes");
        assertEquals(2, api.unlockCalls,
                "both dishes reach unlock(); the failing one does not stop the later one");
    }

    // ---- 13. 关闭 fieldGuideCookbookEnabled 不解锁 ----

    @Test
    void disabledCookbookToggleDoesNotUnlock() {
        module.setCookbookEnabledSupplierForTesting(() -> false);
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
    }

    // ---- 附加：framework 主开关关闭不解锁 ----

    @Test
    void frameworkDisabledDoesNotUnlock() {
        module.setFrameworkEnabledSupplierForTesting(() -> false);
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);

        assertEquals(0, api.unlockCalls);
    }

    // ---- 14. ServerStoppingEvent 清空缓存 ----

    @Test
    void serverStoppingClearsCache() {
        module.handleDishCooked(event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false));
        assertEquals(1, module.processedSizeForTesting());

        module.stopForTesting();
        assertEquals(0, module.processedSizeForTesting());
    }

    // ---- 15. 缓存最大 4096 且会过期 ----

    @Test
    void cachedIdsExpireAfterFortyTicks() {
        DishCookedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.COOKED_COD), false);
        module.handleDishCooked(e);
        assertEquals(1, module.processedSizeForTesting());

        for (int i = 0; i < CookedEventIdCache.DEFAULT_TTL_TICKS; i++) {
            module.tickForTesting();
        }
        assertEquals(0, module.processedSizeForTesting(),
                "expired ids must be dropped after the TTL");

        // A re-delivered event after expiry is processed again.
        module.handleDishCooked(event(e.getEventId(), player,
                new ItemStack(Items.COOKED_COD), false));
        assertEquals(2, api.unlockCalls,
                "an expired id no longer blocks a genuine new unlock");
    }

    // ---- 附加：适配器在无 Field Guide 服务器环境下防御性返回 ----

    @Test
    void adapterIsDefensiveWithoutLiveProgressSystem() {
        FieldGuideApiAdapter adapter = new FieldGuideApiAdapter();
        // Bare JUnit: FieldGuideProgressManager is still the pre-init NOOP, so
        // progress is unavailable and unlock() must not throw.
        assertFalse(adapter.isProgressAvailable(player));
        assertFalse(adapter.unlock(player,
                ResourceLocation.parse("item:minecraft/cooked_cod")));
        assertFalse(adapter.isUnlocked(player,
                ResourceLocation.parse("item:minecraft/cooked_cod")));
    }

    // ---- test double ----

    /** Records calls into the Field Guide API seam. */
    static final class FakeApi implements FieldGuideApi {

        final Set<String> unlocked = new HashSet<>();
        int unlockCalls = 0;
        boolean throwOnUnlock = false;
        boolean alreadyUnlocked = false;
        boolean available = true;
        boolean hasEntryResult = true;

        @Override
        public boolean isProgressAvailable(ServerPlayer player) {
            return available;
        }

        @Override
        public boolean hasEntry(ResourceLocation entryId) {
            return hasEntryResult;
        }

        @Override
        public boolean isUnlocked(ServerPlayer player, ResourceLocation entryId) {
            return unlocked.contains(entryId.toString());
        }

        @Override
        public boolean unlock(ServerPlayer player, ResourceLocation entryId) {
            unlockCalls++;
            if (throwOnUnlock) {
                throw new IllegalStateException("boom");
            }
            if (alreadyUnlocked || unlocked.contains(entryId.toString())) {
                return false;
            }
            unlocked.add(entryId.toString());
            return true;
        }
    }

    @SuppressWarnings("unused")
    private static BooleanSupplier neverTrue() {
        return () -> false;
    }
}
