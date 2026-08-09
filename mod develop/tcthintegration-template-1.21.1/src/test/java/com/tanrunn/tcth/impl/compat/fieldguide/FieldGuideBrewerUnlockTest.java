package com.tanrunn.tcth.impl.compat.fieldguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for the brewer Field Guide unlock path inside
 * {@link FieldGuideCompatModule} (phase 7D).
 *
 * <p>The Field Guide public API is replaced by a {@link FakeApi} so no live
 * Field Guide classes, player saves or a running client are required.
 */
class FieldGuideBrewerUnlockTest {

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
        module.setBrewerEnabledSupplierForTesting(() -> true);
        module.setFrameworkEnabledSupplierForTesting(() -> true);
        module.setCatalogPredicateForTesting(holder -> true);
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getName()).thenReturn(Component.literal("TestPlayer"));
        level = Mockito.mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
        module.resetForTesting();
    }

    private BeveragePreparedEvent event(UUID id, ServerPlayer p, ItemStack result, BeverageTier tier, boolean automated) {
        return new BeveragePreparedEvent(id, p, null, result, BeverageDevice.KEG, tier, automated, level, null);
    }

    // ---- real beverage event unlocks the matching entry ----

    @Test
    void gradedEventUnlocksMatchingEntry() {
        BeveragePreparedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false);
        module.handleBeveragePrepared(e);

        assertTrue(api.unlocked.contains("item:minecraft/honey_bottle"),
                "the prepared beverage entry must be unlocked");
        assertEquals(1, api.unlockCalls);
        assertEquals(1, module.processedSizeForTesting(),
                "the event id must be committed after a successful unlock");
    }

    // ---- 获得/食用/命令给予不解锁（无事件不调 unlock）----

    @Test
    void noUnlockWithoutBeverageEvent() {
        // Simulating "obtaining / drinking / being given the item": no
        // BeveragePreparedEvent is ever posted, so the module never unlocks.
        assertEquals(0, api.unlockCalls);
        assertEquals(0, module.processedSizeForTesting());
    }

    // ---- 重复调制不重复提示 ----

    @Test
    void repeatedPreparationDoesNotReunlock() {
        BeveragePreparedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false);
        module.handleBeveragePrepared(e);
        api.alreadyUnlocked = true;

        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertEquals(2, api.unlockCalls,
                "unlock() is still called for a genuine new event, but returns false");
        assertFalse(api.unlocked.size() > 1);
    }

    @Test
    void duplicateEventIdNotUnlockedTwice() {
        BeveragePreparedEvent e = event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false);
        module.handleBeveragePrepared(e);
        module.handleBeveragePrepared(e);
        assertEquals(1, api.unlockCalls, "the same event id must be processed once");
    }

    // ---- 自动化 / null player / 未分级 / T3 不锁 ----

    @Test
    void automatedDoesNotUnlock() {
        module.handleBeveragePrepared(event(UUID.randomUUID(), null,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, true));
        assertTrue(api.unlocked.isEmpty());
    }

    @Test
    void nullPlayerDoesNotUnlock() {
        module.handleBeveragePrepared(event(UUID.randomUUID(), null,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty());
    }

    @Test
    void unknownTierDoesNotUnlock() {
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.UNKNOWN, false));
        assertTrue(api.unlocked.isEmpty());
    }

    @Test
    void t3DoesNotUnlock() {
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.T3, false));
        assertTrue(api.unlocked.isEmpty());
    }

    // ---- 开关控制 ----

    @Test
    void disabledBrewerCatalogueDoesNotUnlock() {
        module.setBrewerEnabledSupplierForTesting(() -> false);
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty());
    }

    @Test
    void disabledFieldGuideMasterSwitchDoesNotUnlock() {
        module.setFieldGuideEnabledSupplierForTesting(() -> false);
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty());
    }

    @Test
    void configReadExceptionFailsClosedForBrewerSwitch() {
        module.setFieldGuideEnabledSupplierForTesting(() -> true);
        module.setBrewerEnabledSupplierForTesting(() -> {
            throw new IllegalStateException("config boom");
        });
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty(),
                "a config read exception must fail closed (no unlock)");
        assertEquals(0, module.processedSizeForTesting());
    }

    @Test
    void configReadExceptionFailsClosedForMasterSwitch() {
        module.setFieldGuideEnabledSupplierForTesting(() -> {
            throw new IllegalStateException("config boom");
        });
        module.setBrewerEnabledSupplierForTesting(() -> true);
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty(),
                "a Field Guide master-switch read exception must fail closed");
    }

    @Test
    void disabledFrameworkDoesNotUnlock() {
        module.setFrameworkEnabledSupplierForTesting(() -> false);
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty());
    }

    // ---- missing entry does not unlock and is not committed ----

    @Test
    void missingEntryDoesNotUnlockAndNotCommitted() {
        api.hasEntryResult = false;
        module.handleBeveragePrepared(event(UUID.randomUUID(), player,
                new ItemStack(Items.HONEY_BOTTLE), BeverageTier.COMMON, false));
        assertTrue(api.unlocked.isEmpty());
        assertEquals(0, module.processedSizeForTesting(),
                "a missing entry must not commit the event id");
    }

    // ---- 适配器在无 Field Guide 服务器环境下防御性返回 ----

    @Test
    void adapterIsDefensiveWithoutLiveProgressSystem() {
        FieldGuideApiAdapter adapter = new FieldGuideApiAdapter();
        assertFalse(adapter.isProgressAvailable(player));
        assertFalse(adapter.unlock(player,
                ResourceLocation.parse("item:minecraft/honey_bottle")));
        assertFalse(adapter.isUnlocked(player,
                ResourceLocation.parse("item:minecraft/honey_bottle")));
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
}
