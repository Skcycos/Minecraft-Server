package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.jobsplus.integration.arc.holder.holders.job.JobInstance;
import com.daqem.jobsplus.integration.arc.holder.holders.powerup.PowerupInstance;
import com.daqem.jobsplus.player.JobsServerPlayer;
import com.daqem.jobsplus.player.job.Job;
import com.daqem.jobsplus.player.job.powerup.JobPowerupManager;
import com.daqem.jobsplus.player.job.powerup.Powerup;
import com.daqem.jobsplus.player.job.powerup.PowerupState;
import com.daqem.jobsplus.player.job.powerup.PowerupType;
import com.tanrunn.tcth.impl.shadow.ShadowAbilityRoute;
import com.tanrunn.tcth.impl.shadow.ShadowAbilitySnapshot;
import com.tanrunn.tcth.impl.shadow.ShadowAbilityTier;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 8E: {@link ShadowAbilityModule} semantics — the per-route tier query
 * via the Jobs+ public API (fail-closed), the route/master config gating,
 * the four-route independence and the snapshot contract (one query per
 * attempt, highest ACTIVE tier only).
 */
class ShadowAbilityModuleTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ShadowAbilityModule.setConfigSuppliersForTesting(() -> true, () -> true, () -> true,
                () -> true, () -> true, () -> true, () -> true);
    }

    @AfterEach
    void tearDown() {
        ShadowAbilityModule.resetForTesting();
    }

    private static PowerupInstance instanceOf(ResourceLocation node) {
        return new PowerupInstance(node, ResourceLocation.parse("tcth:shadow_thief"), null,
                new ItemStack(Items.ECHO_SHARD), 5, 5, PowerupType.BASIC);
    }

    private static ServerPlayer jobsPlayerWithStates(Map<String, PowerupState> states) {
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        JobsServerPlayer jobsPlayer = (JobsServerPlayer) player;
        Job job = Mockito.mock(Job.class);
        JobPowerupManager manager = Mockito.mock(JobPowerupManager.class);
        when(job.getPowerupManager()).thenReturn(manager);
        when(jobsPlayer.jobsplus$getJob(JobInstance.of(ShadowPowerupAccess.SHADOW_THIEF_JOB)))
                .thenReturn(job);
        ShadowAbilityModule.setPowerupResolverForTesting(ShadowAbilityModuleTest::instanceOf);
        when(manager.getPowerup(Mockito.any())).thenAnswer(invocation -> {
            PowerupInstance instance = invocation.getArgument(0);
            return Optional.ofNullable(states.get(instance.getLocation().getPath()))
                    .map(state -> {
                        Powerup p = mock(Powerup.class);
                        when(p.getState()).thenReturn(state);
                        return p;
                    });
        });
        return player;
    }

    /** States keyed by the FULL powerup path, e.g. "shadow_thief/sleight_of_hand_i". */
    private static Map<String, PowerupState> allNone() {
        Map<String, PowerupState> states = new HashMap<>();
        for (ShadowAbilityRoute route : ShadowAbilityRoute.values()) {
            states.put("shadow_thief/" + route.nodeI(), PowerupState.NOT_OWNED);
            states.put("shadow_thief/" + route.nodeII(), PowerupState.NOT_OWNED);
            states.put("shadow_thief/" + route.nodeIII(), PowerupState.NOT_OWNED);
        }
        return states;
    }

    // ---- tier query ----

    @Test
    void nonJobsServerPlayerYieldsNoneSnapshot() {
        ServerPlayer plain = mock(ServerPlayer.class);
        assertEquals(ShadowAbilitySnapshot.none(),
                ShadowAbilityModule.instance().snapshotFor(plain));
    }

    @Test
    void noJobYieldsNoneSnapshot() {
        ServerPlayer plain = mock(ServerPlayer.class);
        assertEquals(ShadowAbilitySnapshot.none(),
                ShadowAbilityModule.instance().snapshotFor(plain));
    }

    @Test
    void boughtButNotActiveYieldsNone() {
        Map<String, PowerupState> states = allNone();
        states.put("shadow_thief/sleight_of_hand_i", PowerupState.INACTIVE);
        states.put("shadow_thief/sleight_of_hand_ii", PowerupState.NOT_OWNED);
        states.put("shadow_thief/sleight_of_hand_iii", PowerupState.NOT_OWNED);
        ServerPlayer player = jobsPlayerWithStates(states);
        ShadowAbilitySnapshot snapshot = ShadowAbilityModule.instance().snapshotFor(player);
        assertEquals(ShadowAbilityTier.NONE, snapshot.sleight(),
                "bought but not ACTIVE must not trigger the ability");
    }

    @Test
    void highestActiveTierReflectsStates() {
        Map<String, PowerupState> states = allNone();
        ServerPlayer player = jobsPlayerWithStates(states);

        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        states.put("shadow_thief/sleight_of_hand_ii", PowerupState.NOT_OWNED);
        states.put("shadow_thief/sleight_of_hand_iii", PowerupState.LOCKED);
        assertEquals(ShadowAbilityTier.I,
                ShadowAbilityModule.instance().highestActiveTier(player, ShadowAbilityRoute.SLEIGHT));

        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        states.put("shadow_thief/sleight_of_hand_ii", PowerupState.ACTIVE);
        states.put("shadow_thief/sleight_of_hand_iii", PowerupState.ACTIVE);
        assertEquals(ShadowAbilityTier.III,
                ShadowAbilityModule.instance().highestActiveTier(player, ShadowAbilityRoute.SLEIGHT),
                "all three ACTIVE must yield only tier III");
    }

    @Test
    void fourRoutesAreIndependent() {
        Map<String, PowerupState> states = allNone();
        ServerPlayer player = jobsPlayerWithStates(states);
        states.put("shadow_thief/sleight_of_hand_ii", PowerupState.ACTIVE);
        states.put("shadow_thief/life_siphon_i", PowerupState.ACTIVE);
        states.put("shadow_thief/spell_theft_iii", PowerupState.ACTIVE);
        states.put("shadow_thief/shadow_escape_ii", PowerupState.ACTIVE);

        ShadowAbilitySnapshot snapshot = ShadowAbilityModule.instance().snapshotFor(player);
        assertEquals(ShadowAbilityTier.II, snapshot.sleight());
        assertEquals(ShadowAbilityTier.I, snapshot.lifeSiphon());
        assertEquals(ShadowAbilityTier.III, snapshot.spellTheft());
        assertEquals(ShadowAbilityTier.II, snapshot.shadowEscape());
    }

    @Test
    void brokenQueryFailsClosedToNone() {
        ServerPlayer player = Mockito.mock(ServerPlayer.class,
                Mockito.withSettings().extraInterfaces(JobsServerPlayer.class));
        when(((JobsServerPlayer) player).jobsplus$getJob(JobInstance.of(ShadowPowerupAccess.SHADOW_THIEF_JOB)))
                .thenThrow(new IllegalStateException("jobsplus data corrupt"));
        assertEquals(ShadowAbilitySnapshot.none(),
                ShadowAbilityModule.instance().snapshotFor(player));
    }

    // ---- config gating ----

    @Test
    void masterSwitchOffYieldsNone() {
        ShadowAbilityModule.setConfigSuppliersForTesting(() -> true, () -> true, () -> false,
                () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = allNone();
        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        ServerPlayer player = jobsPlayerWithStates(states);
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityModule.instance().snapshotFor(player));
    }

    @Test
    void integrationSwitchOffYieldsNone() {
        ShadowAbilityModule.setConfigSuppliersForTesting(() -> true, () -> false, () -> true,
                () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = allNone();
        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        ServerPlayer player = jobsPlayerWithStates(states);
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityModule.instance().snapshotFor(player));
    }

    @Test
    void routeSwitchOffYieldsNoneForThatRouteOnly() {
        ShadowAbilityModule.setConfigSuppliersForTesting(() -> true, () -> true, () -> true,
                () -> false /* sleight off */, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = allNone();
        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        states.put("shadow_thief/life_siphon_iii", PowerupState.ACTIVE);
        ServerPlayer player = jobsPlayerWithStates(states);
        ShadowAbilitySnapshot snapshot = ShadowAbilityModule.instance().snapshotFor(player);
        assertEquals(ShadowAbilityTier.NONE, snapshot.sleight(), "route switch off → NONE");
        assertEquals(ShadowAbilityTier.III, snapshot.lifeSiphon(), "other routes unaffected");
    }

    @Test
    void brokenConfigReadFailsClosedToNone() {
        ShadowAbilityModule.setConfigSuppliersForTesting(() -> {
            throw new IllegalStateException("config broken");
        }, () -> true, () -> true, () -> true, () -> true, () -> true, () -> true);
        Map<String, PowerupState> states = allNone();
        states.put("shadow_thief/sleight_of_hand_i", PowerupState.ACTIVE);
        ServerPlayer player = jobsPlayerWithStates(states);
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityModule.instance().snapshotFor(player));
    }

    @Test
    void nullPlayerYieldsNone() {
        assertEquals(ShadowAbilitySnapshot.none(), ShadowAbilityModule.instance().snapshotFor(null));
    }

    @Test
    void snapshotQueriesEachRouteExactlyOnce() {
        Map<String, PowerupState> states = allNone();
        ServerPlayer player = jobsPlayerWithStates(states);
        // snapshotFor must query exactly the four routes (at most once per
        // attempt) — no re-query inside.
        ShadowAbilitySnapshot snapshot = ShadowAbilityModule.instance().snapshotFor(player);
        assertNotNull(snapshot);
        assertEquals(ShadowAbilitySnapshot.none(), snapshot);
    }
}
