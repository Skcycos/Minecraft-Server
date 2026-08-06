package com.tanrunn.tcth.impl.compat.scguns;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.api.guncombat.GunKillEvent;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;
import com.tanrunn.tcth.impl.compat.CompatLoader;
import com.tanrunn.tcth.impl.event.GunKillEventDispatcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import top.ribs.scguns.common.FireMode;
import top.ribs.scguns.common.Gun;
import top.ribs.scguns.entity.projectile.ProjectileEntity;
import top.ribs.scguns.init.ModDamageTypes;
import top.ribs.scguns.item.GunItem;

/**
 * Scorched Guns firearm-kill detector (phase 5A.1).
 *
 * <p>All kill confirmation happens exclusively in {@link LivingDeathEvent}
 * using strong evidence from the final {@link DamageSource}. There is no
 * victim-recent-hit cache: hits that do not lead to a provable SG death are
 * never settled (fall, fire, third-party finishers, delayed effects all yield
 * zero events).
 *
 * <p>Three confirmed paths, checked in order:
 * <ol>
 *   <li><b>SG {@link ProjectileEntity}</b> (bullets, shells, specials, plasma
 *       direct/splash, rockets, grenades, MicroJet, …): the direct entity is a
 *       SG projectile and its shooter matches the damage source's causing
 *       player;</li>
 *   <li><b>Niami vanilla {@link Arrow}</b>: the direct entity is a vanilla
 *       arrow registered at birth by {@link NiamiArrowRegistry}
 *       ({@code ServerPlayHandler.getArrow} mixin);</li>
 *   <li><b>SG beam weapons</b> ({@code scguns:bullet} damage with a null
 *       direct entity): the causing player's main hand holds a beam/semi-beam
 *       SG gun.</li>
 * </ol>
 *
 * <p>{@code GunProjectileHitEvent} is never used as kill evidence — it fires
 * before damage, is cancellable, and proves only an attempted hit.
 *
 * <p>All Scorched Guns type references live exclusively in this package. The
 * main mod class only knows this module through its string descriptor
 * ({@code CompatLoader.register("scguns", "...ScorchedGunsCompatModule")});
 * the SG JAR is a {@code compileOnly} dependency so signature drift fails at
 * build time.
 */
public final class ScorchedGunsCompatModule implements CompatModule {

    private static long currentTick = 0;
    private static boolean initialized = false;

    /**
     * Target-tier resolution strategy. Production uses the data-tag resolver;
     * tests inject a stub because a bare JUnit JVM has no datapack tags loaded.
     */
    interface TierResolver {
        @Nullable
        GunTargetTier resolve(Entity victim);
    }

    private static TierResolver tierResolver = GunTargetResolver::resolve;

    /**
     * Projectile access. Production uses {@code instanceof ProjectileEntity}
     * and the real SG methods (compile-time checked); tests inject a
     * contract-identical stand-in because ByteBuddy cannot instrument the real
     * SG class under the Graal JVM used for unit tests.
     */
    interface ProjectileAccess {
        boolean isProjectile(Entity entity);

        @Nullable
        LivingEntity getShooter(Entity entity);

        @Nullable
        ItemStack getWeapon(Entity entity);
    }

    private static final ProjectileAccess REAL_PROJECTILE_ACCESS = new ProjectileAccess() {
        @Override
        public boolean isProjectile(Entity entity) {
            return entity instanceof ProjectileEntity;
        }

        @Override
        @Nullable
        public LivingEntity getShooter(Entity entity) {
            return entity instanceof ProjectileEntity p ? p.getShooter() : null;
        }

        @Override
        @Nullable
        public ItemStack getWeapon(Entity entity) {
            return entity instanceof ProjectileEntity p ? p.getWeapon() : null;
        }
    };

    private static ProjectileAccess projectileAccess = REAL_PROJECTILE_ACCESS;

    /**
     * Gun-item check for projectile weapons. Production: {@code instanceof
     * GunItem}. Tests inject a predicate because a real SG gun item cannot be
     * constructed after the item registry freezes.
     */
    interface GunWeaponCheck {
        boolean isGun(ItemStack stack);
    }

    private static GunWeaponCheck gunWeaponCheck = stack -> stack != null && !stack.isEmpty()
            && stack.getItem() instanceof GunItem;

    /**
     * Beam-gun check for the beam path. Production reads the modified gun's
     * FireMode ({@code scguns:beam} / {@code scguns:semi_beam}) from the real
     * SG classes; tests inject a predicate.
     */
    interface BeamGunCheck {
        boolean isBeamGun(ItemStack held);
    }

    private static final BeamGunCheck REAL_BEAM_GUN_CHECK = held -> {
        if (held == null || held.isEmpty() || !(held.getItem() instanceof GunItem gunItem)) {
            return false;
        }
        Gun gun = safeGetModifiedGun(gunItem, held);
        if (gun == null) {
            return false;
        }
        FireMode fireMode = gun.getGeneral().getFireMode();
        return FireMode.BEAM.equals(fireMode) || FireMode.SEMI_BEAM.equals(fireMode);
    };

    private static BeamGunCheck beamGunCheck = REAL_BEAM_GUN_CHECK;

    public ScorchedGunsCompatModule() {
    }

    @Override
    public String modId() {
        return "scguns";
    }

    @Override
    public void onModConstruction(IEventBus modEventBus) {
        init(NeoForge.EVENT_BUS);
        TCTHIntegration.LOGGER.info("[TCTH] Scorched Guns compat module active");
    }

    /**
     * Registers the Scorched Guns listeners. Called from the compat module's
     * construction hook. Idempotent.
     */
    public static void init(IEventBus gameBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] ScorchedGunsCompatModule.init called more than once; ignoring");
            return;
        }
        initialized = true;
        // LOWEST + receiveCanceled=false so that kills already cancelled by
        // other listeners are never posted.
        gameBus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class,
                ScorchedGunsCompatModule::onLivingDeath);
        gameBus.addListener(ScorchedGunsCompatModule::onServerTick);
        gameBus.addListener(ScorchedGunsCompatModule::onServerStopping);
        gameBus.addListener(ScorchedGunsCompatModule::onPlayerLogout);
        gameBus.addListener(ScorchedGunsCompatModule::onEntityLeaveLevel);
        TCTHIntegration.LOGGER.info("[TCTH] Scorched Guns compat module registered");
    }

    static void register(IEventBus gameBus) {
        init(gameBus);
    }

    // ---- event handlers ----

    static void onLivingDeath(LivingDeathEvent event) {
        // Master switches: when the framework or the gunner integration is off,
        // every entry point returns immediately (no detection, no records).
        if (!integrationEnabled()) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim == null || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        DamageSource source = event.getSource();
        if (source == null) {
            return;
        }
        if (victim instanceof Player) {
            return; // no PvP settlement (defence in depth; also excluded via tags)
        }

        // 5A.2 acceptance debug output (log-only; DEBUG level).
        TCTHIntegration.LOGGER.debug("[TCTH][GUN] death victim={} type={} direct={} causing={} bullet={}",
                victim.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
                source.getDirectEntity() == null ? "null"
                        : source.getDirectEntity().getClass().getName(),
                source.getEntity() == null ? "null" : source.getEntity().getClass().getName(),
                source.is(ModDamageTypes.BULLET));

        // 1. SG ProjectileEntity path (bullets, shells, explosions, …).
        if (tryConfirmProjectileKill(source, victim, level)) {
            return;
        }
        // 2. Niami vanilla Arrow path (birth-registered by the mixin).
        if (tryConfirmArrowKill(source, victim, level)) {
            return;
        }
        // 3. SG beam path (scguns:bullet, null direct entity, beam gun).
        tryConfirmBeamKill(source, victim, level);
    }

    static void onServerTick(ServerTickEvent.Post event) {
        currentTick++;
        NiamiArrowRegistry.onServerTick();
    }

    static void onServerStopping(ServerStoppingEvent event) {
        NiamiArrowRegistry.onServerStopping();
        currentTick = 0;
    }

    /**
     * Drop Niami arrow records whose shooter logged out.
     */
    static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        NiamiArrowRegistry.removeForShooter(event.getEntity().getUUID());
    }

    /**
     * Drop the record of an arrow that left the world (hit, despawn, …).
     */
    static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Arrow arrow) {
            NiamiArrowRegistry.remove(arrow.getUUID());
        }
    }

    // ---- kill confirmation ----

    /**
     * Path 1: the direct entity is a SG {@link ProjectileEntity} and its
     * shooter is the damage source's causing player.
     */
    private static boolean tryConfirmProjectileKill(DamageSource source, Entity victim, ServerLevel level) {
        Entity direct = source.getDirectEntity();
        if (direct == null || !projectileAccess.isProjectile(direct)) {
            return false;
        }
        Entity causing = source.getEntity();
        if (!(causing instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return false;
        }
        // The projectile's shooter must be the SAME player that the damage
        // source attributes the kill to.
        if (projectileAccess.getShooter(direct) != player) {
            return false;
        }
        ItemStack weapon = projectileAccess.getWeapon(direct);
        if (weapon == null || weapon.isEmpty() || !gunWeaponCheck.isGun(weapon)) {
            return false;
        }
        return publishKill("projectile", player, victim, weapon, level);
    }

    /**
     * Path 2: the direct entity is a vanilla {@link Arrow} registered at birth
     * by {@link NiamiArrowRegistry}.
     */
    private static boolean tryConfirmArrowKill(DamageSource source, Entity victim, ServerLevel level) {
        Entity direct = source.getDirectEntity();
        if (!(direct instanceof Arrow arrow)) {
            return false;
        }
        NiamiArrowRegistry.ArrowRecord record = NiamiArrowRegistry.take(arrow.getUUID());
        if (record == null) {
            return false; // not a registered Niami arrow, or expired
        }
        Entity causing = source.getEntity();
        if (!(causing instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return false;
        }
        if (!record.shooterUuid().equals(player.getUUID())) {
            return false; // damage source attributes the kill to a different player
        }
        ItemStack weapon = record.weaponCopy();
        if (weapon == null || weapon.isEmpty() || !gunWeaponCheck.isGun(weapon)) {
            return false;
        }
        return publishKill("arrow", player, victim, weapon, level);
    }

    /**
     * Path 3: SG beam weapons — {@code scguns:bullet} damage with a null
     * direct entity dealt by a real player holding a beam/semi-beam SG gun.
     */
    private static boolean tryConfirmBeamKill(DamageSource source, Entity victim, ServerLevel level) {
        if (source.getDirectEntity() != null) {
            return false;
        }
        if (!source.is(ModDamageTypes.BULLET)) {
            return false;
        }
        Entity causing = source.getEntity();
        if (!(causing instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return false;
        }
        // Beam damage is applied synchronously inside SG's hitEntity.hurt()
        // call, so reading the current main-hand item is safe here; snapshot it
        // immediately.
        ItemStack held = player.getMainHandItem();
        if (held == null || held.isEmpty() || !beamGunCheck.isBeamGun(held)) {
            return false;
        }
        return publishKill("beam", player, victim, held, level);
    }

    /** Shared settlement: tier classification + event publication. */
    private static boolean publishKill(String path, ServerPlayer player, Entity victim, ItemStack weapon,
                                       ServerLevel level) {
        GunTargetTier tier = tierResolver.resolve(victim);
        if (tier == null) {
            TCTHIntegration.LOGGER.debug("[TCTH][GUN] victim={} REJECT reason=unclassified",
                    victim.getUUID());
            return false; // unclassified target: no event
        }
        float distance = (float) player.distanceTo(victim);
        ResourceLocation weaponId = BuiltInRegistries.ITEM.getKey(weapon.getItem());
        ResourceLocation targetId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType());
        BlockPos pos = victim.blockPosition();
        GunKillEvent event = new GunKillEvent(
                java.util.UUID.randomUUID(), player, weaponId, weapon.copy(),
                targetId, victim.getUUID(), tier, distance, false, level, pos);
        TCTHIntegration.LOGGER.debug("[TCTH][GUN] confirm path={} victim={} weapon={} tier={} dist={} player={}",
                path, victim.getUUID(), weaponId, tier, distance, player.getUUID());
        GunKillEventDispatcher.publish(event);
        return true;
    }

    // ---- helpers ----

    /**
     * Master integration switch: TCTH framework enabled AND gunner integration
     * enabled. Config read failures fail closed. Injectable for tests.
     */
    static java.util.function.BooleanSupplier integrationEnabledSupplier =
            ScorchedGunsCompatModule::defaultIntegrationEnabled;

    static boolean integrationEnabled() {
        try {
            return integrationEnabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static boolean defaultIntegrationEnabled() {
        try {
            return CompatLoader.isFrameworkEnabled() && Config.GUNNER_INTEGRATION_ENABLED.get();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Nullable
    static Gun safeGetModifiedGun(GunItem gunItem, ItemStack stack) {
        try {
            return gunItem.getModifiedGun(stack);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.debug("[TCTH] Failed to read modified gun for {}: {}",
                    stack, e.toString());
            return null;
        }
    }

    // ---- test hooks (package-private) ----

    static void setTierResolverForTesting(TierResolver resolver) {
        tierResolver = resolver;
    }

    static void setProjectileAccessForTesting(ProjectileAccess access) {
        projectileAccess = access != null ? access : REAL_PROJECTILE_ACCESS;
    }

    static void setGunWeaponCheckForTesting(GunWeaponCheck check) {
        gunWeaponCheck = check != null ? check : stack -> stack != null && !stack.isEmpty()
                && stack.getItem() instanceof GunItem;
    }

    static void setBeamGunCheckForTesting(BeamGunCheck check) {
        beamGunCheck = check != null ? check : REAL_BEAM_GUN_CHECK;
    }

    static void setIntegrationEnabledSupplierForTesting(java.util.function.BooleanSupplier supplier) {
        integrationEnabledSupplier = supplier != null
                ? supplier : ScorchedGunsCompatModule::defaultIntegrationEnabled;
    }

    static void resetForTesting() {
        initialized = false;
        tierResolver = GunTargetResolver::resolve;
        projectileAccess = REAL_PROJECTILE_ACCESS;
        gunWeaponCheck = stack -> stack != null && !stack.isEmpty()
                && stack.getItem() instanceof GunItem;
        beamGunCheck = REAL_BEAM_GUN_CHECK;
        NiamiArrowRegistry.resetForTesting();
        currentTick = 0;
    }

    static long currentTickForTesting() {
        return currentTick;
    }
}
