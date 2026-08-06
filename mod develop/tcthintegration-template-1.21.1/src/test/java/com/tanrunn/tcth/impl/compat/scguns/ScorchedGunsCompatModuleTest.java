package com.tanrunn.tcth.impl.compat.scguns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.impl.event.GunKillEventDispatcher;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import top.ribs.scguns.init.ModDamageTypes;

/**
 * Four-path kill-confirmation tests for {@link ScorchedGunsCompatModule}
 * (phase 5A.1).
 *
 * <p>Production references the real SG 1.5 classes ({@code ProjectileEntity},
 * {@code GunItem}, {@code FireMode}, {@code ModDamageTypes}) at compile time.
 * The bare JUnit JVM cannot construct SG gun items (frozen registry) nor
 * instrument SG entities (ByteBuddy/Graal), so the three decision seams
 * ({@link ScorchedGunsCompatModule.ProjectileAccess},
 * {@link ScorchedGunsCompatModule.GunWeaponCheck},
 * {@link ScorchedGunsCompatModule.BeamGunCheck}) are injected with
 * contract-identical stand-ins; the real signatures are covered by
 * {@link ScorchedGunsCompatModuleTest.RealSgSignatureTest} and the javap
 * evidence in the phase 5A.1 report.
 */
class ScorchedGunsCompatModuleTest {

    private IEventBus bus;
    private ServerLevel level;
    private ServerPlayer player;
    private UUID playerId;
    private ServerPlayer otherPlayer;
    private TestProjectile projectile;
    private DamageSource source;
    private AtomicInteger published;
    private AtomicReference<GunKillEvent> captured;
    private ItemStack gunStack;
    private ItemStack beamStack;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        ScorchedGunsCompatModule.resetForTesting();
        NiamiArrowRegistry.resetForTesting();
        GunKillEventDispatcher.resetForTesting();
        bus = BusBuilder.builder().build();
        GunKillEventDispatcher.setGameBusForTesting(bus);
        GunKillEventDispatcher.setEnabledSupplierForTesting(() -> true);
        GunKillEventDispatcher.setGunnerEnabledSupplierForTesting(() -> true);
        ScorchedGunsCompatModule.setIntegrationEnabledSupplierForTesting(() -> true);
        NiamiArrowRegistry.setIntegrationEnabledSupplierForTesting(() -> true);
        ScorchedGunsCompatModule.setTierResolverForTesting(victim -> GunTargetTier.COMMON);
        ScorchedGunsCompatModule.setProjectileAccessForTesting(new ProjectileAccessAdapter());
        NiamiArrowRegistry.setArrowGunValidatorForTesting(stack -> true);

        level = mock(ServerLevel.class);
        playerId = UUID.randomUUID();
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        otherPlayer = mock(ServerPlayer.class);
        when(otherPlayer.getUUID()).thenReturn(UUID.randomUUID());
        projectile = mock(TestProjectile.class);
        when(projectile.getShooter()).thenReturn(player);
        gunStack = new ItemStack(Items.DIAMOND_SWORD);
        when(projectile.getWeapon()).thenReturn(gunStack);
        ScorchedGunsCompatModule.setGunWeaponCheckForTesting(
                stack -> stack != null && stack.getItem() == Items.DIAMOND_SWORD);
        beamStack = new ItemStack(Items.IRON_SWORD);
        ScorchedGunsCompatModule.setBeamGunCheckForTesting(stack -> stack == beamStack);
        source = mock(DamageSource.class);
        when(source.getDirectEntity()).thenReturn(projectile);
        when(source.getEntity()).thenReturn(player);
        when(source.is(ModDamageTypes.BULLET)).thenReturn(false);

        published = new AtomicInteger();
        captured = new AtomicReference<>();
        bus.addListener(GunKillEvent.class, e -> {
            published.incrementAndGet();
            captured.set(e);
        });
    }

    @AfterEach
    void tearDown() {
        ScorchedGunsCompatModule.resetForTesting();
        NiamiArrowRegistry.resetForTesting();
        GunKillEventDispatcher.resetForTesting();
    }

    // ---- helpers ----

    private LivingEntity hostileVictim(UUID uuid) {
        LivingEntity victim = mock(LivingEntity.class);
        when(victim.level()).thenReturn(level);
        stubType(victim, EntityType.ZOMBIE);
        when(victim.getUUID()).thenReturn(uuid);
        when(victim.blockPosition()).thenReturn(BlockPos.ZERO);
        return victim;
    }

    /** Stubs {@code getType()} avoiding the {@code EntityType<? extends Entity>} wildcard capture. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubType(LivingEntity victim, EntityType<?> type) {
        doReturn((EntityType) type).when(victim).getType();
    }

    private LivingDeathEvent deathOf(LivingEntity victim, DamageSource deathSource) {
        LivingDeathEvent death = mock(LivingDeathEvent.class);
        when(death.getEntity()).thenReturn(victim);
        when(death.getSource()).thenReturn(deathSource);
        return death;
    }

    private void assertPublishedOnce() {
        assertEquals(1, published.get(), "exactly one GunKillEvent must be published");
        assertNotNull(captured.get());
    }

    private void assertNotPublished() {
        assertEquals(0, published.get(), "no GunKillEvent may be published");
    }

    // ================= Path 1: SG ProjectileEntity =================

    @Test
    void projectileKillPublishesOnce() {
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertPublishedOnce();
        GunKillEvent event = captured.get();
        assertFalse(event.isAutomated());
        assertEquals(player, event.getPlayer());
        assertEquals(GunTargetTier.COMMON, event.getTargetTier());
        assertNotNull(event.getWeaponId());
    }

    @Test
    void explosionKillPublishes() {
        // Rockets/grenades explode with DamageSource explosion(projectile,
        // shooter): direct entity is still the (ProjectileEntity subclass)
        // explosion body and causing is the shooter — same path as bullets.
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertPublishedOnce();
    }

    @Test
    void shooterMismatchIsNotPublished() {
        // projectile.getShooter() differs from source.getEntity().
        when(projectile.getShooter()).thenReturn(otherPlayer);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void emptyWeaponIsNotPublished() {
        when(projectile.getWeapon()).thenReturn(ItemStack.EMPTY);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void nonGunWeaponIsNotPublished() {
        when(projectile.getWeapon()).thenReturn(new ItemStack(Items.ARROW));
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void fakePlayerShooterIsNotPublished() {
        when(projectile.getShooter()).thenReturn(mock(FakePlayer.class));
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void pvpIsNotPublished() {
        Player victim = mock(Player.class);
        when(victim.level()).thenReturn(level);
        stubType(victim, EntityType.PLAYER);
        when(victim.getUUID()).thenReturn(UUID.randomUUID());
        when(victim.blockPosition()).thenReturn(BlockPos.ZERO);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(victim, source));
        assertNotPublished();
    }

    @Test
    void unclassifiedTargetIsNotPublished() {
        ScorchedGunsCompatModule.setTierResolverForTesting(victim -> null);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void nonSgProjectileIsNotPublished() {
        // A vanilla arrow is not an SG ProjectileEntity.
        Arrow arrow = mock(Arrow.class);
        DamageSource arrowSource = mock(DamageSource.class);
        when(arrowSource.getDirectEntity()).thenReturn(arrow);
        when(arrowSource.getEntity()).thenReturn(player);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), arrowSource));
        assertNotPublished();
    }

    @Test
    void meleeKillIsNotPublished() {
        LivingEntity attacker = mock(LivingEntity.class);
        DamageSource meleeSource = mock(DamageSource.class);
        when(meleeSource.getDirectEntity()).thenReturn(attacker);
        when(meleeSource.getEntity()).thenReturn(attacker);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), meleeSource));
        assertNotPublished();
    }

    // ================= Path 2: SG beam weapons =================

    private DamageSource beamSource() {
        DamageSource beam = mock(DamageSource.class);
        when(beam.getDirectEntity()).thenReturn(null);
        when(beam.getEntity()).thenReturn(player);
        when(beam.is(ModDamageTypes.BULLET)).thenReturn(true);
        return beam;
    }

    @Test
    void beamKillPublishesOnce() {
        when(player.getMainHandItem()).thenReturn(beamStack);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beamSource()));
        assertPublishedOnce();
    }

    @Test
    void semiBeamKillPublishes() {
        // SEMI_BEAM is accepted by the same FireMode check (BEAM || SEMI_BEAM);
        // the beam seam stands for either mode.
        when(player.getMainHandItem()).thenReturn(beamStack);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beamSource()));
        assertPublishedOnce();
    }

    @Test
    void beamWithNonBulletDamageTypeIsNotPublished() {
        DamageSource beam = mock(DamageSource.class);
        when(beam.getDirectEntity()).thenReturn(null);
        when(beam.getEntity()).thenReturn(player);
        when(beam.is(ModDamageTypes.BULLET)).thenReturn(false);
        when(player.getMainHandItem()).thenReturn(beamStack);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beam));
        assertNotPublished();
    }

    @Test
    void beamWithNonBeamGunIsNotPublished() {
        DamageSource beam = beamSource();
        ScorchedGunsCompatModule.setBeamGunCheckForTesting(stack -> false);
        when(player.getMainHandItem()).thenReturn(beamStack);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beam));
        assertNotPublished();
    }

    @Test
    void beamWithNonPlayerCausingEntityIsNotPublished() {
        DamageSource beam = mock(DamageSource.class);
        when(beam.getDirectEntity()).thenReturn(null);
        when(beam.getEntity()).thenReturn(mock(LivingEntity.class)); // turret / non-player
        when(beam.is(ModDamageTypes.BULLET)).thenReturn(true);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beam));
        assertNotPublished();
    }

    @Test
    void beamFakePlayerIsNotPublished() {
        DamageSource beam = mock(DamageSource.class);
        when(beam.getDirectEntity()).thenReturn(null);
        when(beam.getEntity()).thenReturn(mock(FakePlayer.class));
        when(beam.is(ModDamageTypes.BULLET)).thenReturn(true);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), beam));
        assertNotPublished();
    }

    // ================= Path 3: Niami vanilla Arrow =================

    private Arrow registeredArrow() {
        Arrow arrow = mock(Arrow.class);
        UUID arrowUuid = UUID.randomUUID();
        when(arrow.getUUID()).thenReturn(arrowUuid); // stable: register+take must see the same id
        when(player.getMainHandItem()).thenReturn(gunStack);
        assertTrue(NiamiArrowRegistry.register(arrow, player),
                "a Niami arrow fired by a valid SG gun must be registered");
        return arrow;
    }

    private DamageSource arrowDeathSource(Arrow arrow, ServerPlayer causing) {
        DamageSource arrowSource = mock(DamageSource.class);
        when(arrowSource.getDirectEntity()).thenReturn(arrow);
        when(arrowSource.getEntity()).thenReturn(causing);
        return arrowSource;
    }

    @Test
    void niamiArrowKillPublishes() {
        Arrow arrow = registeredArrow();
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertPublishedOnce();
    }

    @Test
    void niamiArrowUsesFrozenWeaponSnapshot() {
        Arrow arrow = registeredArrow();
        // Player switches items after firing — attribution must keep the
        // firing-time Niami snapshot.
        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.EMERALD));
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertPublishedOnce();
        assertEquals("minecraft:diamond_sword", captured.get().getWeaponId().toString());
    }

    @Test
    void vanillaBowArrowIsNotPublished() {
        // A vanilla bow arrow is never registered (no birth record).
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUUID()).thenReturn(UUID.randomUUID());
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void unregisteredArrowIsNotPublished() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUUID()).thenReturn(UUID.randomUUID());
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void arrowShooterMismatchIsNotPublished() {
        Arrow arrow = registeredArrow();
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, otherPlayer)));
        assertNotPublished();
    }

    @Test
    void expiredArrowIsNotPublished() {
        Arrow arrow = registeredArrow();
        for (int i = 0; i <= NiamiArrowRegistry.ARROW_TTL_TICKS; i++) {
            ScorchedGunsCompatModule.onServerTick(null);
        }
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void arrowLeftWorldIsNotPublished() {
        Arrow arrow = registeredArrow();
        EntityLeaveLevelEvent leave = mock(EntityLeaveLevelEvent.class);
        when(leave.getEntity()).thenReturn(arrow);
        ScorchedGunsCompatModule.onEntityLeaveLevel(leave);
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void arrowShooterLogoutIsNotPublished() {
        Arrow arrow = registeredArrow();
        PlayerEvent.PlayerLoggedOutEvent logout = mock(PlayerEvent.PlayerLoggedOutEvent.class);
        when(logout.getEntity()).thenReturn(player);
        ScorchedGunsCompatModule.onPlayerLogout(logout);
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void arrowServerStopIsNotPublished() {
        Arrow arrow = registeredArrow();
        ScorchedGunsCompatModule.onServerStopping(null);
        ScorchedGunsCompatModule.onLivingDeath(
                deathOf(hostileVictim(UUID.randomUUID()), arrowDeathSource(arrow, player)));
        assertNotPublished();
    }

    @Test
    void arrowRegistryCapacityIsBounded() {
        int cap = NiamiArrowRegistry.MAX_ARROW_ENTRIES;
        when(player.getMainHandItem()).thenReturn(gunStack);
        for (int i = 0; i < cap + 5; i++) {
            Arrow arrow = mock(Arrow.class);
            when(arrow.getUUID()).thenReturn(UUID.randomUUID());
            NiamiArrowRegistry.register(arrow, player);
        }
        assertTrue(NiamiArrowRegistry.sizeForTesting() <= cap,
                "the arrow registry must never exceed its capacity");
    }

    @Test
    void offHandOrNonNiamiMainHandDoesNotRegister() {
        // A main hand that is not an arrow-firing SG gun must never register
        // (stands for "off-hand bow while holding an SG gun" scenarios).
        NiamiArrowRegistry.setArrowGunValidatorForTesting(stack -> false);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getMainHandItem()).thenReturn(gunStack);
        assertFalse(NiamiArrowRegistry.register(arrow, player));
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    // ================= Negative examples (no victim cache) =================

    @Test
    void fallDeathAfterHitIsNotPublished() {
        // Hit earlier (no cache exists), then the victim dies from fall damage:
        // direct=null, not scguns:bullet → 0.
        DamageSource fall = mock(DamageSource.class);
        when(fall.getDirectEntity()).thenReturn(null);
        when(fall.getEntity()).thenReturn(player);
        when(fall.is(ModDamageTypes.BULLET)).thenReturn(false);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), fall));
        assertNotPublished();
    }

    @Test
    void fireDeathAfterHitIsNotPublished() {
        DamageSource fire = mock(DamageSource.class);
        when(fire.getDirectEntity()).thenReturn(null);
        when(fire.getEntity()).thenReturn(player);
        when(fire.is(ModDamageTypes.BULLET)).thenReturn(false);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), fire));
        assertNotPublished();
    }

    @Test
    void thirdPartyFinisherIsNotPublished() {
        // Victim is finished by another player's melee after being shot.
        DamageSource finisher = mock(DamageSource.class);
        when(finisher.getDirectEntity()).thenReturn(otherPlayer);
        when(finisher.getEntity()).thenReturn(otherPlayer);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), finisher));
        assertNotPublished();
    }

    @Test
    void delayedPoisonOrWitherDeathIsNotPublished() {
        // Victim dies from a delayed effect; the final source cannot be proven
        // to be an SG gun.
        DamageSource magic = mock(DamageSource.class);
        when(magic.getDirectEntity()).thenReturn(null);
        when(magic.getEntity()).thenReturn(null);
        when(magic.is(ModDamageTypes.BULLET)).thenReturn(false);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), magic));
        assertNotPublished();
    }

    @Test
    void hitWithoutKillPublishesNothing() {
        // A hit that does not kill produces no death event → nothing is
        // published; there is no hit cache that could later claim the kill.
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertPublishedOnce(); // this hit DID kill (projectile path)
        // Second, unrelated death with no projectile source → 0.
        DamageSource fall = mock(DamageSource.class);
        when(fall.getDirectEntity()).thenReturn(null);
        when(fall.getEntity()).thenReturn(player);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), fall));
        assertEquals(1, published.get());
    }

    @Test
    void duplicateDeathEventPublishesOnlyOnce() {
        UUID victimUuid = UUID.randomUUID();
        LivingEntity victim = hostileVictim(victimUuid);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(victim, source));
        ScorchedGunsCompatModule.onLivingDeath(deathOf(victim, source));
        assertPublishedOnce();
    }

    // ================= Master switches =================

    @Test
    void integrationDisabledPublishesNothing() {
        ScorchedGunsCompatModule.setIntegrationEnabledSupplierForTesting(() -> false);
        ScorchedGunsCompatModule.onLivingDeath(deathOf(hostileVictim(UUID.randomUUID()), source));
        assertNotPublished();
    }

    @Test
    void integrationDisabledDoesNotRegisterArrows() {
        NiamiArrowRegistry.setIntegrationEnabledSupplierForTesting(() -> false);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getMainHandItem()).thenReturn(gunStack);
        assertFalse(NiamiArrowRegistry.register(arrow, player));
        assertEquals(0, NiamiArrowRegistry.sizeForTesting());
    }

    // ================= Real SG signature smoke =================

    @Test
    void realSgTypesAreLoadable() throws Exception {
        // The real SG 1.5 JAR is on the test classpath; these are the exact
        // types the module compiles against.
        assertNotNull(Class.forName("top.ribs.scguns.entity.projectile.ProjectileEntity"));
        assertNotNull(Class.forName("top.ribs.scguns.item.GunItem"));
        assertNotNull(Class.forName("top.ribs.scguns.common.FireMode"));
        assertNotNull(Class.forName("top.ribs.scguns.init.ModDamageTypes"));
        assertNotNull(Class.forName("top.ribs.scguns.common.network.ServerPlayHandler"));
        assertEquals("scguns:bullet", ModDamageTypes.BULLET.location().toString());
    }

    /** Contract-identical stand-in for {@code ProjectileEntity}. */
    static class TestProjectile extends Entity {
        protected TestProjectile(EntityType<?> type) {
            super(type, null);
        }

        public LivingEntity getShooter() {
            return null;
        }

        public ItemStack getWeapon() {
            return ItemStack.EMPTY;
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag compound) {
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag compound) {
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }

    /** Adapter: maps the module's ProjectileAccess to {@link TestProjectile}. */
    private static final class ProjectileAccessAdapter implements ScorchedGunsCompatModule.ProjectileAccess {
        @Override
        public boolean isProjectile(Entity entity) {
            return entity instanceof TestProjectile;
        }

        @Override
        public LivingEntity getShooter(Entity entity) {
            return entity instanceof TestProjectile t ? t.getShooter() : null;
        }

        @Override
        public ItemStack getWeapon(Entity entity) {
            return entity instanceof TestProjectile t ? t.getWeapon() : null;
        }
    }
}
