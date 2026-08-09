package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.BrewerDrinkCooldownCondition;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.mockito.Mockito;

/**
 * Phase 7E: brewer tasting-route cooldown boundary tests.
 *
 * <p>Verifies the shared 20 s (400 tick) cooldown at the 399/400 boundary, the
 * success-driven commit semantics and the logout/stop cleanup.
 */
class BrewerDrinkCooldownTest {

    private final UUID uuid = UUID.randomUUID();
    private final ServerPlayer player;

    BrewerDrinkCooldownTest() {
        player = Mockito.mock(ServerPlayer.class);
        Mockito.when(player.getUUID()).thenReturn(uuid);
    }

    @BeforeEach
    void setUp() {
        BrewerDrinkCooldown.resetForTesting();
        BrewerDrinkCooldown.setTickSourceForTesting(() -> 1000L);
        BrewerDrinkCooldown.setCooldownTicksForTesting(() -> 400);
    }

    @AfterEach
    void tearDown() {
        BrewerDrinkCooldown.resetForTesting();
    }

    @Test
    void cooldownBoundaryAt399And400() {
        // tickSource drives nowTick directly (no server access).
        long[] now = {1000};
        BrewerDrinkCooldown.setTickSourceForTesting(() -> now[0]);
        BrewerDrinkCooldown.setCooldownTicksForTesting(() -> 400);

        // Commit at tick 1000.
        BrewerDrinkCooldown.instance().commit(uuid, player);

        // 399 ticks later (tick 1399): still on cooldown (now - last = 399 < 400).
        now[0] = 1399;
        assertTrue(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player),
                "at 399 ticks after commit the cooldown is still active");

        // 400 ticks later (tick 1400): cooldown expired (now - last = 400, not < 400).
        now[0] = 1400;
        assertFalse(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player),
                "at 400 ticks after commit the cooldown has expired");
    }

    @Test
    void noCooldownBeforeFirstCommit() {
        BrewerDrinkCooldown.setTickSourceForTesting(() -> 1000L);
        assertFalse(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player));
    }

    @Test
    void clearPlayerRemovesEntry() {
        BrewerDrinkCooldown.setTickSourceForTesting(() -> 1000L);
        BrewerDrinkCooldown.instance().commit(uuid, player);
        assertTrue(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player));

        BrewerDrinkCooldown.instance().clearPlayer(uuid);
        assertFalse(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player));
    }

    @Test
    void clearAllRemovesAllEntries() {
        BrewerDrinkCooldown.setTickSourceForTesting(() -> 1000L);
        BrewerDrinkCooldown.instance().commit(uuid, player);
        BrewerDrinkCooldown.instance().clearAll();
        assertFalse(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player));
    }

    @Test
    void conditionPassesWhenNotOnCooldownAndBlocksWhenOnCooldown() {
        long[] now = {1000};
        BrewerDrinkCooldown.setTickSourceForTesting(() -> now[0]);
        BrewerDrinkCooldown.setCooldownTicksForTesting(() -> 400);

        BrewerDrinkCooldownCondition condition = new BrewerDrinkCooldownCondition(false);
        ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.when(player.serverLevel()).thenReturn(level);

        com.daqem.arc.api.action.data.ActionData data = Mockito.mock(com.daqem.arc.api.action.data.ActionData.class);
        com.daqem.arc.api.player.ArcServerPlayer arcPlayer = Mockito.mock(com.daqem.arc.api.player.ArcServerPlayer.class);
        Mockito.when(arcPlayer.arc$getPlayer()).thenReturn(player);
        Mockito.when(data.getPlayer()).thenReturn(arcPlayer);

        // No commit yet: passes.
        assertTrue(condition.isMet(data));

        // After commit: blocked.
        BrewerDrinkCooldown.instance().commit(uuid, player);
        assertFalse(condition.isMet(data));

        // After expiry: passes again.
        now[0] = 1400;
        assertTrue(condition.isMet(data));
    }

    @Test
    void brewerCooldownIsIndependentOfChefTastingCooldown() {
        // Committing to the brewer cooldown must NOT affect the chef tasting
        // cooldown and vice versa: the two routes use separate instances.
        com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.resetForTesting();
        com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.setTickSourceForTesting(() -> 1000L);
        com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.setCooldownTicksForTesting(() -> 400);

        BrewerDrinkCooldown.setTickSourceForTesting(() -> 1000L);
        BrewerDrinkCooldown.setCooldownTicksForTesting(() -> 400);

        // Brewer cooldown committed; chef cooldown untouched.
        BrewerDrinkCooldown.instance().commit(uuid, player);
        assertTrue(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player));
        assertFalse(com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.instance()
                .isOnCooldown(uuid, player), "chef cooldown must be independent");

        // Chef cooldown committed; brewer cooldown untouched.
        com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.instance().commit(uuid, player);
        assertTrue(com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.instance()
                .isOnCooldown(uuid, player));
        assertTrue(BrewerDrinkCooldown.instance().isOnCooldown(uuid, player),
                "brewer cooldown state must not be cleared by chef commits");

        com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefTastingCooldown.resetForTesting();
    }
}
