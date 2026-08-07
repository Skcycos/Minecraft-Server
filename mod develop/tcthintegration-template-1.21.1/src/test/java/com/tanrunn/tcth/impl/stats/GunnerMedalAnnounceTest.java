package com.tanrunn.tcth.impl.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 5C.1: medal unlock announcements — translation keys, no language
 * sniffing, single merged message.
 */
class GunnerMedalAnnounceTest {

    private static final ResourceLocation WEAPON =
            ResourceLocation.fromNamespaceAndPath("scguns", "defender_pistol");
    private static final ResourceLocation TARGET =
            ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");

    private GunnerStatsData data;
    private ServerPlayer player;
    private UUID playerId;
    private ServerLevel level;
    private final List<Component> messages = new ArrayList<>();

    @BeforeAll
    static void boot() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        GunnerStatsTracker.resetForTesting();
        GunnerStatsTracker.setEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setFrameworkEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setIntegrationEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> true);
        data = new GunnerStatsData();
        GunnerStatsTracker.setDataProviderForTesting(lvl -> data);
        messages.clear();
        GunnerStatsTracker.setAnnounceSinkForTesting((p, msg) -> messages.add(msg));
        playerId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        level = mock(ServerLevel.class);
    }

    @AfterEach
    void tearDown() {
        GunnerStatsTracker.resetForTesting();
    }

    private GunKillEvent event(float distance, GunTargetTier tier) {
        return new GunKillEvent(
                UUID.randomUUID(),
                player,
                WEAPON,
                new ItemStack(Items.STICK),
                TARGET,
                UUID.randomUUID(),
                tier,
                distance,
                false,
                level,
                BlockPos.ZERO);
    }

    @Test
    void singleNewMedalAnnouncesOnceWithTranslationKeys() {
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertEquals(1, messages.size());
        Set<String> keys = collectKeys(messages.getFirst());
        assertTrue(keys.contains("tcth.gunner.medal.unlocked"));
        assertTrue(keys.contains("tcth.gunner.medal.first_blood"));
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertEquals(1, messages.size(), "already unlocked medals must not re-announce");
    }

    @Test
    void threeMedalsInOneEventMergeIntoOneMessage() {
        GunnerStatsTracker.onGunKill(event(50.0f, GunTargetTier.BOSS));
        assertEquals(1, messages.size());
        Set<String> keys = collectKeys(messages.getFirst());
        assertTrue(keys.contains("tcth.gunner.medal.unlocked"));
        assertTrue(keys.contains("tcth.gunner.medal.first_blood"));
        assertTrue(keys.contains("tcth.gunner.medal.long_shot"));
        assertTrue(keys.contains("tcth.gunner.medal.boss_finisher"));
        assertTrue(keys.contains("tcth.gunner.medal.list_separator"));
    }

    @Test
    void configOffUnlocksButDoesNotAnnounce() {
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> false);
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertTrue(messages.isEmpty());
        assertTrue(data.get(playerId).hasMedal(GunnerMedal.FIRST_BLOOD));
    }

    @Test
    void reEnableDoesNotReplayHistoricalUnlocks() {
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> false);
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertTrue(messages.isEmpty());
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> true);
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertTrue(messages.isEmpty(), "re-enable must not re-announce already unlocked medals");
    }

    @Test
    void configExceptionFailsClosedForAnnounceButUnlocks() {
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> {
            throw new IllegalStateException("config boom");
        });
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertTrue(messages.isEmpty());
        assertTrue(data.get(playerId).hasMedal(GunnerMedal.FIRST_BLOOD));
    }

    @Test
    void restartSimulatedByReloadDoesNotReAnnounce() {
        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertEquals(1, messages.size());
        CompoundTag saved = data.get(playerId).save();

        GunnerStatsTracker.resetForTesting();
        GunnerStatsTracker.setEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setFrameworkEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setIntegrationEnabledSupplierForTesting(() -> true);
        GunnerStatsTracker.setMedalAnnounceSupplierForTesting(() -> true);
        messages.clear();
        GunnerStatsTracker.setAnnounceSinkForTesting((p, msg) -> messages.add(msg));

        CompoundTag players = new CompoundTag();
        players.put(playerId.toString(), saved);
        CompoundTag root = new CompoundTag();
        root.putInt("dataVersion", 2);
        root.put("players", players);
        data = GunnerStatsData.load(root, HolderLookup.Provider.create(java.util.stream.Stream.empty()));
        GunnerStatsTracker.setDataProviderForTesting(lvl -> data);
        when(player.getUUID()).thenReturn(playerId);

        GunnerStatsTracker.onGunKill(event(1.0f, GunTargetTier.COMMON));
        assertTrue(messages.isEmpty(), "after reload, first-blood already held — no announce");
        assertTrue(data.get(playerId).hasMedal(GunnerMedal.FIRST_BLOOD));
    }

    @Test
    void formatAnnouncementDoesNotSniffPlayerLanguage() {
        Component msg = GunnerStatsTracker.formatMedalAnnouncement(
                List.of(GunnerMedal.FIRST_BLOOD, GunnerMedal.LONG_SHOT));
        Set<String> keys = collectKeys(msg);
        assertTrue(keys.contains("tcth.gunner.medal.unlocked"));
        assertTrue(keys.contains("tcth.gunner.medal.first_blood"));
        assertTrue(keys.contains("tcth.gunner.medal.long_shot"));
        assertTrue(keys.contains("tcth.gunner.medal.list_separator"));
    }

    private static Set<String> collectKeys(Component component) {
        Set<String> keys = new HashSet<>();
        walk(component, keys);
        return keys;
    }

    private static void walk(Component component, Set<String> keys) {
        if (component == null) {
            return;
        }
        if (component.getContents() instanceof TranslatableContents tc) {
            keys.add(tc.getKey());
            for (Object arg : tc.getArgs()) {
                if (arg instanceof Component c) {
                    walk(c, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            walk(sibling, keys);
        }
    }
}
