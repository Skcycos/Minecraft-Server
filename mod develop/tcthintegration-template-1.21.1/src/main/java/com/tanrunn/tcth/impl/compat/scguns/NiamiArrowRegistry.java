package com.tanrunn.tcth.impl.compat.scguns;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;

import top.ribs.scguns.common.Gun;
import top.ribs.scguns.item.GunItem;

/**
 * Bounded, birth-attribution registry for Scorched Guns "Niami" arrows
 * (phase 5A.1).
 *
 * <p>SG's {@code niami} gun config ({@code "item": "minecraft:arrow",
 * "firesArrows": true}) fires <em>vanilla</em> {@link Arrow}s instead of SG
 * {@code ProjectileEntity}s, so a normal death-event check cannot prove the
 * arrow came from an SG gun. This registry is populated at <em>arrow birth</em>
 * (from the {@code NiamiArrowMixin} on {@code ServerPlayHandler.getArrow}):
 * the weapon snapshot is taken from the shooter's main hand at firing time and
 * frozen, so later item switches cannot change attribution.
 *
 * <p>Deliberately <strong>not</strong> a victim-recent-hit cache: entries are
 * keyed by the <em>arrow</em> UUID and only consumed when the final death event
 * names that exact arrow as the direct entity. No fall damage, fire or
 * third-party finishers can ever be attributed.
 *
 * <p>Bounds: capacity 4096, TTL 1200 ticks, cleanup on arrow removal / player
 * logout / server stop, and the entry is deleted immediately after a successful
 * settlement.
 */
public final class NiamiArrowRegistry {

    /** TTL for arrow entries (ticks). */
    public static final int ARROW_TTL_TICKS = 1200;
    /** Hard cap for the arrow registry. */
    public static final int MAX_ARROW_ENTRIES = 4096;

    /**
     * Birth record for a registered Niami arrow.
     *
     * @param shooterUuid UUID of the player who fired the arrow
     * @param weaponId    item id of the SG gun at firing time
     * @param weaponCopy  frozen copy of the gun stack at firing time
     * @param spawnTick   server tick at firing time
     */
    public record ArrowRecord(UUID shooterUuid, ResourceLocation weaponId,
                              ItemStack weaponCopy, long spawnTick) {
    }

    private static final Map<UUID, ArrowRecord> ARROWS = new LinkedHashMap<>(64, 0.75f, true);

    private static long currentTick = 0;

    /**
     * Gun-item + firesArrows validator. Production reads the real SG classes;
     * tests inject a predicate because a real SG gun cannot be constructed
     * after the item registry freezes.
     */
    interface ArrowGunValidator {
        boolean isArrowGun(ItemStack held);
    }

    static ArrowGunValidator arrowGunValidator = NiamiArrowRegistry::realArrowGunCheck;

    private static boolean realArrowGunCheck(ItemStack held) {
        if (held == null || held.isEmpty() || !(held.getItem() instanceof GunItem gunItem)) {
            return false;
        }
        Gun gun = safeGetModifiedGun(gunItem, held);
        if (gun == null) {
            return false;
        }
        Gun.Projectile projectile = gun.getProjectile();
        return projectile != null && projectile.firesArrows();
    }

    private NiamiArrowRegistry() {
    }

    /**
     * Registers a freshly spawned Niami arrow. Called from the conditional
     * mixin on {@code ServerPlayHandler.getArrow} RETURN. The gun check is
     * re-verified here (main-hand GunItem + modified Gun + {@code firesArrows})
     * so that vanilla bows or off-hand arrows are never registered.
     *
     * <p>Fails closed and returns {@code false} when the integration switches
     * are off or the main-hand weapon does not prove to be an arrow-firing SG
     * gun — no record is created.
     *
     * @return {@code true} if the arrow was registered
     */
    public static boolean register(Arrow arrow, ServerPlayer shooter) {
        if (arrow == null || shooter == null) {
            return false;
        }
        if (!integrationEnabled()) {
            return false;
        }
        ItemStack held = shooter.getMainHandItem();
        if (held == null || held.isEmpty() || !arrowGunValidator.isArrowGun(held)) {
            return false;
        }
        ResourceLocation weaponId = BuiltInRegistries.ITEM.getKey(held.getItem());
        synchronized (ARROWS) {
            ARROWS.put(arrow.getUUID(),
                    new ArrowRecord(shooter.getUUID(), weaponId, held.copy(), currentTick));
            pruneLocked();
        }
        return true;
    }

    /**
     * Consumes (and removes) the record for an arrow that was confirmed as the
     * killing projectile. Returns the record or {@code null} if unknown /
     * expired.
     */
    @Nullable
    public static ArrowRecord take(UUID arrowUuid) {
        if (arrowUuid == null) {
            return null;
        }
        synchronized (ARROWS) {
            ArrowRecord record = ARROWS.get(arrowUuid);
            if (record == null) {
                return null;
            }
            if (currentTick - record.spawnTick() > ARROW_TTL_TICKS) {
                ARROWS.remove(arrowUuid);
                return null; // expired
            }
            ARROWS.remove(arrowUuid); // one arrow settles at most once
            return record;
        }
    }

    /** Removes an arrow's record (e.g. the arrow left the world). */
    public static void remove(UUID arrowUuid) {
        if (arrowUuid == null) {
            return;
        }
        synchronized (ARROWS) {
            ARROWS.remove(arrowUuid);
        }
    }

    /** Removes every record whose shooter logged out. */
    public static void removeForShooter(UUID shooterUuid) {
        if (shooterUuid == null) {
            return;
        }
        synchronized (ARROWS) {
            ARROWS.entrySet().removeIf(e -> e.getValue().shooterUuid().equals(shooterUuid));
        }
    }

    /** Server-tick expiry cleanup. */
    public static void onServerTick() {
        currentTick++;
        synchronized (ARROWS) {
            ARROWS.entrySet().removeIf(e -> currentTick - e.getValue().spawnTick() > ARROW_TTL_TICKS);
        }
    }

    /** Server-stop cleanup. */
    public static void onServerStopping() {
        synchronized (ARROWS) {
            ARROWS.clear();
        }
        currentTick = 0;
    }

    /** Capacity cleanup. Caller must hold the monitor. */
    private static void pruneLocked() {
        while (ARROWS.size() > MAX_ARROW_ENTRIES) {
            Iterator<UUID> it = ARROWS.keySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            it.next();
            it.remove(); // eldest (insertion order)
        }
    }

    private static boolean integrationEnabled() {
        try {
            return integrationEnabledSupplier.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static java.util.function.BooleanSupplier integrationEnabledSupplier =
            NiamiArrowRegistry::defaultIntegrationEnabled;

    static boolean defaultIntegrationEnabled() {
        try {
            return CompatLoader.isFrameworkEnabled()
                    && Config.GUNNER_INTEGRATION_ENABLED.get();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Nullable
    private static Gun safeGetModifiedGun(GunItem gunItem, ItemStack stack) {
        try {
            return gunItem.getModifiedGun(stack);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.debug("[TCTH] Failed to read modified gun for {}: {}",
                    stack, e.toString());
            return null;
        }
    }

    // ---- test hooks (package-private) ----

    static void setArrowGunValidatorForTesting(ArrowGunValidator validator) {
        arrowGunValidator = validator != null ? validator : NiamiArrowRegistry::realArrowGunCheck;
    }

    static void setIntegrationEnabledSupplierForTesting(java.util.function.BooleanSupplier supplier) {
        integrationEnabledSupplier = supplier != null
                ? supplier : NiamiArrowRegistry::defaultIntegrationEnabled;
    }

    static int sizeForTesting() {
        synchronized (ARROWS) {
            return ARROWS.size();
        }
    }

    static long currentTickForTesting() {
        return currentTick;
    }

    static void resetForTesting() {
        synchronized (ARROWS) {
            ARROWS.clear();
        }
        arrowGunValidator = NiamiArrowRegistry::realArrowGunCheck;
        integrationEnabledSupplier = NiamiArrowRegistry::defaultIntegrationEnabled;
        currentTick = 0;
    }
}
