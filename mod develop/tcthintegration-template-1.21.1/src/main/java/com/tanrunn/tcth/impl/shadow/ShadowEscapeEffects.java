package com.tanrunn.tcth.impl.shadow;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.tanrunn.tcth.TCTHIntegration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 潜影路线 (shadow escape) effects (phase 8E, ownership lifecycle 8E.2.2).
 *
 * <p>Grants the SUCCESS speed/invisibility package and shortens the
 * FAILED_ROLL exposure duration (the exposure scaling itself lives in the
 * interaction handler, which reads the same snapshot). Applies to PLAYER and
 * ENTITY successes alike and NEVER influences whether a transaction
 * succeeds: every entry point is best-effort and exception-isolated.
 *
 * <p><strong>Invisibility ownership (8E.2.2)</strong> — the marker is not
 * proof of ownership by itself. The {@link MobEffectEvent} chain (Added,
 * Remove, Expired) only marks players for <em>deferred reconciliation</em>
 * on the next server tick. The actual effect-slot state is authoritative:
 * <ul>
 *   <li>Events ({@link MobEffectEvent.Added}, {@link MobEffectEvent.Remove},
 *       {@link MobEffectEvent.Expired}) fire <em>before</em> the slot is
 *       updated. Added and Remove are cancelable; Expired also implements
 *       {@code ICancellableEvent} in NeoForge 21.1.247. They only add the
 *       player's UUID to the bounded {@link #PENDING_RECONCILE} set;</li>
 *   <li>On {@link ServerTickEvent.Post} the pending set is drained and
 *       each player's <em>real</em> effect slot is read:
 *       <ul>
 *         <li>effect gone or no longer matching the TCTH signature →
 *             marker cleared (external effect never deleted);</li>
 *         <li>effect still matching → marker kept.</li>
 *       </ul></li>
 *   <li>TCTH's OWN grant runs under a short-lived internal
 *       {@link #GRANT_GUARDS} guard so the Added handler cannot mark our
 *       own re-grant for reconciliation.</li>
 * </ul>
 *
 * <p>Attack / new-attempt breaks delete the current invisibility ONLY when
 * it still matches the TCTH signature: same amplifier, same visibility
 * flags, and a remaining duration inside the natural-decay window
 * ({@code granted - elapsed - tolerance ≤ duration ≤ granted - elapsed}).
 * An external invisibility — longer, shorter, same duration, different
 * amplifier, ambient or flags — is never deleted.
 *
 * <p><strong>Known limitation:</strong> where the vanilla merge semantics
 * make two effects byte-identical (a force-added instance identical to our
 * grant), the conservative choice documented in the phase report is: such
 * an effect is treated as owned (a sub-tick boundary that cannot be
 * resolved by any event in this MC version).
 *
 * <p>The marker, guard and pending sets are bounded, never persisted to
 * any SavedData/playerdata, and cleaned on logout and server stop.
 */
public final class ShadowEscapeEffects {

    /** Hard cap of the in-memory marker map. */
    public static final int MAX_MARKS = 512;

    /**
     * Natural-decay tolerance in ticks for the ownership signature: the
     * remaining duration of OUR effect is {@code granted - elapsed} (effects
     * tick down once per tick); a 2-tick window absorbs sub-tick timing
     * differences between the grant tick and the check tick. Anything
     * shorter than this window is NOT our effect.
     */
    static final long DECAY_TOLERANCE_TICKS = 2L;

    /** Hard cap of the pending reconciliation set. */
    private static final int MAX_PENDING = 1024;

    /**
     * Marker of one TCTH-granted invisibility.
     *
     * @param amplifier        the granted amplifier (always 0 today)
     * @param grantedDuration  the granted duration in ticks
     * @param grantedAt        the level game time at grant
     * @param expiry           the level game time when the granted effect ends
     */
    record Mark(int amplifier, int grantedDuration, long grantedAt, long expiry) {
    }

    private static final Map<UUID, Mark> MARKS = new LinkedHashMap<>(64, 0.75f);
    /** Short-lived guard: players whose invisibility grant is IN FLIGHT
     *  (inside {@link #applySuccess}) — the Added handler must not mark
     *  our own add for reconciliation. Bounded like the markers. */
    private static final Set<UUID> GRANT_GUARDS = new LinkedHashSet<>();
    /** 8E.2.2: deferred reconciliation set. Events only enqueue UUIDs;
     *  the actual effect-slot check happens on the next server tick. */
    private static final Set<UUID> PENDING_RECONCILE = new LinkedHashSet<>();
    private static boolean initialized = false;
    /** Cached server reference for player lookup during reconciliation. */
    private static volatile MinecraftServer cachedServer;

    private ShadowEscapeEffects() {
    }

    /** Idempotent registration of the attack / effect-event / tick /
     *  logout / stop lifecycle. */
    public static void init(IEventBus bus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] ShadowEscapeEffects.init called more than once; ignoring");
            return;
        }
        initialized = true;
        bus.addListener(ShadowEscapeEffects::onAttack);
        bus.addListener(ShadowEscapeEffects::onEffectAdded);
        bus.addListener(ShadowEscapeEffects::onEffectRemoved);
        bus.addListener(ShadowEscapeEffects::onEffectExpired);
        bus.addListener(ShadowEscapeEffects::onServerTick);
        bus.addListener(ShadowEscapeEffects::onPlayerLogout);
        bus.addListener(ShadowEscapeEffects::onServerStopping);
        TCTHIntegration.LOGGER.debug("[TCTH] ShadowEscapeEffects initialized");
    }

    /** Server-authoritative attack break: an attacking invisible thief is
     *  revealed (client side is never trusted). */
    static void onAttack(AttackEntityEvent event) {
        try {
            if (event == null || event.getEntity() == null
                    || event.getEntity().level().isClientSide()) {
                return;
            }
            if (event.getEntity() instanceof ServerPlayer player) {
                breakInvisibility(player);
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape attack-break failed: {}", e.toString());
        }
    }

    /**
     * 8E.2.2: an invisibility-related {@code Added} event fired. Instead
     * of immediately modifying the marker, the player is added to the
     * pending reconciliation set. The real effect-slot check happens on
     * the next server tick.
     *
     * <p>Our own grant runs under the {@link #GRANT_GUARDS} guard and
     * is skipped here.
     */
    static void onEffectAdded(MobEffectEvent.Added event) {
        try {
            if (event == null || event.getEntity() == null
                    || event.getEntity().level().isClientSide()) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            MobEffectInstance incoming = event.getEffectInstance();
            if (incoming == null || !incoming.getEffect().is(MobEffects.INVISIBILITY)) {
                return;
            }
            UUID uuid = player.getUUID();
            if (GRANT_GUARDS.contains(uuid)) {
                return; // our own grant in flight
            }
            if (MARKS.containsKey(uuid)) {
                markPending(uuid);
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape effect-added handler failed: {}", e.toString());
        }
    }

    /**
     * 8E.2.2: an invisibility-related {@code Remove} event fired
     * (cancelable). The player is marked for deferred reconciliation
     * rather than immediately clearing the marker.
     */
    static void onEffectRemoved(MobEffectEvent.Remove event) {
        try {
            if (event == null || event.getEntity() == null
                    || event.getEntity().level().isClientSide()) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (event.getEffect() == null || !event.getEffect().is(MobEffects.INVISIBILITY)) {
                return;
            }
            UUID uuid = player.getUUID();
            if (MARKS.containsKey(uuid)) {
                markPending(uuid);
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape effect-removed handler failed: {}", e.toString());
        }
    }

    /**
     * 8E.2.2: an invisibility-related {@code Expired} event fired. The
     * player is marked for deferred reconciliation. {@code Expired}
     * implements {@code ICancellableEvent} in NeoForge 21.1.247 (javap
     * verified) — another handler may cancel it and keep the effect in the
     * slot. Deferred reconciliation reads the <em>real</em> effect slot on
     * the next tick, so a cancelled expiry is handled correctly: if the
     * effect is still present and matches the TCTH signature, the marker
     * is preserved.
     */
    static void onEffectExpired(MobEffectEvent.Expired event) {
        try {
            if (event == null || event.getEntity() == null
                    || event.getEntity().level().isClientSide()) {
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            MobEffectInstance expired = event.getEffectInstance();
            if (expired != null && expired.getEffect().is(MobEffects.INVISIBILITY)) {
                UUID uuid = player.getUUID();
                if (MARKS.containsKey(uuid)) {
                    markPending(uuid);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape effect-expired handler failed: {}", e.toString());
        }
    }

    /**
     * 8E.2.2: tick-based reconciliation. On each server tick, every
     * pending player's <em>real</em> effect slot is read and compared
     * against the marker. This avoids acting on pre-update event state
     * or cancelled events.
     */
    static void onServerTick(ServerTickEvent.Post event) {
        try {
            cachedServer = event.getServer();
            reconcilePending();
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape tick reconciliation failed: {}", e.toString());
        }
    }

    /** Drains the pending set and reconciles each player. */
    private static void reconcilePending() {
        Iterator<UUID> it = PENDING_RECONCILE.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            it.remove();
            reconcile(uuid);
        }
    }

    /**
     * Reads the player's real effect slot and compares with the marker.
     * If the effect is gone, expired, or replaced by an external source,
     * the marker is cleared (the external effect is never deleted).
     */
    private static void reconcile(UUID uuid) {
        Mark mark = MARKS.get(uuid);
        if (mark == null) {
            return;
        }
        ServerPlayer player = resolvePlayer(uuid);
        if (player == null) {
            // Player offline — marker will be cleaned on logout.
            return;
        }
        long now = player.level().getGameTime();
        MobEffectInstance current = player.getEffect(MobEffects.INVISIBILITY);
        if (!matchesOwnedSignature(current, mark, now)) {
            // Effect is gone, expired, or replaced by an external source.
            // Clear the marker; never delete an external effect.
            MARKS.remove(uuid);
        }
        // If matches → marker stays (effect is still ours).
    }

    /** Resolves a player UUID to a ServerPlayer. Uses the test lookup
     *  first, then falls back to the cached server reference. */
    private static ServerPlayer resolvePlayer(UUID uuid) {
        Function<UUID, ServerPlayer> lookup = playerLookupForTesting;
        if (lookup != null) {
            ServerPlayer p = lookup.apply(uuid);
            if (p != null) {
                return p;
            }
        }
        MinecraftServer server = cachedServer;
        if (server != null) {
            return server.getPlayerList().getPlayer(uuid);
        }
        return null;
    }

    /** Adds a UUID to the bounded pending reconciliation set. */
    private static void markPending(UUID uuid) {
        PENDING_RECONCILE.remove(uuid); // refresh insertion order
        PENDING_RECONCILE.add(uuid);
        while (PENDING_RECONCILE.size() > MAX_PENDING) {
            Iterator<UUID> it = PENDING_RECONCILE.iterator();
            it.next();
            it.remove();
        }
    }

    static void onPlayerLogout(PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            UUID uuid = event.getEntity().getUUID();
            MARKS.remove(uuid);
            GRANT_GUARDS.remove(uuid);
            PENDING_RECONCILE.remove(uuid);
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        MARKS.clear();
        GRANT_GUARDS.clear();
        PENDING_RECONCILE.clear();
        cachedServer = null; // 8E.2.3: prevent stale server reference
    }

    /**
     * Called BEFORE a new theft attempt starts (before any random roll or
     * asset transaction): a TCTH-granted invisibility is removed if it still
     * matches the recorded signature.
     */
    public static void breakOnNewAttempt(ServerPlayer thief) {
        if (thief == null) {
            return;
        }
        breakInvisibility(thief);
    }

    /**
     * Grants the escape SUCCESS effect package for the snapshot's tier.
     * Best-effort only: a failure is logged (throttled) and never affects the
     * already-completed transaction. Only the highest tier applies.
     */
    public static void applySuccess(ServerPlayer thief, ShadowAbilitySnapshot abilities) {
        if (thief == null || abilities == null) {
            return;
        }
        try {
            ShadowAbilityTier tier = abilities.shadowEscape();
            if (tier == ShadowAbilityTier.NONE) {
                return;
            }
            int speedTicks = ShadowAbilityValues.escapeSpeedTicks(tier);
            if (speedTicks > 0) {
                thief.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speedTicks,
                        ShadowAbilityValues.escapeSpeedAmplifier(tier), false, true, true));
            }
            int invisTicks = ShadowAbilityValues.escapeInvisibilityTicks(tier);
            if (invisTicks > 0) {
                UUID uuid = thief.getUUID();
                // Short-lived internal guard: the synchronous Added event of
                // OUR OWN add must not be treated as an external replacement.
                GRANT_GUARDS.add(uuid);
                boolean applied;
                try {
                    applied = thief.addEffect(new MobEffectInstance(
                            MobEffects.INVISIBILITY, invisTicks, 0, false, true, true));
                } finally {
                    GRANT_GUARDS.remove(uuid);
                }
                if (applied) {
                    // The grant took effect (or extended an equal/weaker one):
                    // record OUR signature. If the player already had a
                    // stronger/longer invisibility, addEffect returned false
                    // and no marker is recorded — the external effect stays
                    // untouched.
                    long now = thief.level().getGameTime();
                    recordMark(uuid, 0, invisTicks, now, now + invisTicks);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // Cosmetic only: never break the tick, never roll back assets.
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape success effects failed for {}: {}",
                    safeName(thief), e.toString());
        }
    }

    /** Null-safe player name for logging (a broken GameProfile must never
     *  turn a cosmetic failure into a thrown exception). */
    private static String safeName(ServerPlayer thief) {
        try {
            com.mojang.authlib.GameProfile profile = thief.getGameProfile();
            return profile == null ? String.valueOf(thief.getUUID()) : profile.getName();
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }

    // ---- internals ----

    private static void recordMark(UUID uuid, int amplifier, int duration,
                                   long grantedAt, long expiry) {
        MARKS.remove(uuid); // refresh insertion order
        MARKS.put(uuid, new Mark(amplifier, duration, grantedAt, expiry));
        while (MARKS.size() > MAX_MARKS) {
            Iterator<UUID> it = MARKS.keySet().iterator();
            UUID eldest = it.next();
            it.remove();
            GRANT_GUARDS.remove(eldest);
            PENDING_RECONCILE.remove(eldest);
        }
    }

    private static boolean removeEffectFailsForTesting = false;

    /**
     * Attempts to break TCTH-granted invisibility for a theft attempt or
     * attack. The signature is checked against the <em>real</em> effect
     * slot.
     *
     * <p>After a successful {@code removeEffect}, the marker is cleared
     * unconditionally (8E.2.2): if another source immediately re-applied
     * an invisibility, the cleared marker prevents the attack path from
     * deleting the external effect.
     *
     * <p>If {@code removeEffect} returns false or throws, the marker
     * stays so the reconciliation tick or a later break can still reach
     * it.
     */
    private static void breakInvisibility(ServerPlayer player) {
        Mark mark = MARKS.get(player.getUUID());
        if (mark == null) {
            return;
        }
        MobEffectInstance current = player.getEffect(MobEffects.INVISIBILITY);
        long now = player.level().getGameTime();
        if (!matchesOwnedSignature(current, mark, now)) {
            // Not provably ours (replaced by another source, expired,
            // or gone): clear the marker only — never delete an effect
            // we cannot prove we own.
            MARKS.remove(player.getUUID());
            return;
        }
        boolean removed;
        try {
            removed = removeEffectFailsForTesting
                    ? false
                    : player.removeEffect(MobEffects.INVISIBILITY);
        } catch (RuntimeException | LinkageError e) {
            ShadowLogThrottle.warnOncePerMinute(TCTHIntegration.LOGGER,
                    "[TCTH] Shadow escape invisibility removal failed for {}: {}",
                    safeName(player), e.toString());
            removed = false;
        }
        if (removed) {
            // Effect confirmed removed — safe to clear the marker.
            // If another source re-applied an effect in the same tick,
            // the cleared marker prevents the attack path from deleting
            // the external effect (8E.2.2).
            MARKS.remove(player.getUUID());
        }
        // If removeEffect returned false or threw, the marker stays so
        // the reconciliation tick or a later break can still reach it.
    }

    /**
     * Ownership signature (8E.1 §3): same amplifier, TCTH visibility flags
     * (non-ambient, visible, icon shown) and a remaining duration INSIDE the
     * natural-decay window
     * ({@code granted - elapsed - tolerance ≤ duration ≤ granted - elapsed}).
     *
     * <p>The lower bound is what protects external effects: a shorter
     * external invisibility (same amplifier/flags) decays faster than our
     * window allows and is NOT deleted. An external effect longer than the
     * window is also excluded. When the effect is absent ({@code current ==
     * null}), returns {@code false} — the marker should be cleared.
     */
    private static boolean matchesOwnedSignature(MobEffectInstance current, Mark mark, long now) {
        if (current == null) {
            return false;
        }
        long elapsed = now < mark.grantedAt() ? 0L : now - mark.grantedAt();
        long naturalRemaining = Math.max(0L, mark.grantedDuration() - elapsed);
        long lowerBound = Math.max(0L, naturalRemaining - DECAY_TOLERANCE_TICKS);
        return current.getAmplifier() == mark.amplifier()
                && !current.isAmbient()
                && current.isVisible()
                && current.showIcon()
                && current.getDuration() <= naturalRemaining
                && current.getDuration() >= lowerBound;
    }

    // ---- test hooks (not part of the public API) ----

    /** Test-only player lookup override. When non-null, takes precedence
     *  over the cached server for reconciliation. */
    private static Function<UUID, ServerPlayer> playerLookupForTesting = null;

    /** @return the number of tracked invisibility markers (tests) */
    public static int markCount() {
        return MARKS.size();
    }

    /** @return whether a marker exists for the player (tests) */
    public static boolean hasMark(UUID playerId) {
        return MARKS.containsKey(playerId);
    }

    /** Simulates the grant guard being active (tests only). */
    static void setGrantGuardForTesting(UUID playerId, boolean active) {
        if (active) {
            GRANT_GUARDS.add(playerId);
        } else {
            GRANT_GUARDS.remove(playerId);
        }
    }

    /** @return whether the grant guard is active for the player (tests) */
    static boolean isGrantGuardActive(UUID playerId) {
        return GRANT_GUARDS.contains(playerId);
    }

    /** Sets a test-only player lookup for reconciliation (tests only). */
    static void setPlayerLookupForTesting(Function<UUID, ServerPlayer> lookup) {
        playerLookupForTesting = lookup;
    }

    /** Drains the pending reconciliation set using the test lookup
     *  (tests only). */
    static void flushPendingForTesting() {
        reconcilePending();
    }

    /** @return the number of pending reconciliation entries (tests) */
    static int pendingCountForTesting() {
        return PENDING_RECONCILE.size();
    }

    /** @return whether a UUID is in the pending reconciliation set (tests) */
    static boolean isPendingForTesting(UUID uuid) {
        return PENDING_RECONCILE.contains(uuid);
    }

    static void resetForTesting() {
        MARKS.clear();
        GRANT_GUARDS.clear();
        PENDING_RECONCILE.clear();
        removeEffectFailsForTesting = false;
        playerLookupForTesting = null;
        cachedServer = null;
        initialized = false;
    }

    static Mark markForTesting(UUID playerId) {
        return MARKS.get(playerId);
    }

    /** Simulates removeEffect returning false (tests only). */
    static void setRemoveEffectFailsForTesting(boolean fails) {
        removeEffectFailsForTesting = fails;
    }
}
