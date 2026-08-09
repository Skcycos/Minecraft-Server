package com.tanrunn.tcth.impl.compat.fieldguide;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.evandev.fieldguide.server.ServerFieldGuideManager;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;
import com.tanrunn.tcth.api.brewing.BeveragePreparedEvent;
import com.tanrunn.tcth.api.brewing.BeverageTier;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Conditional compat module: unlocks Field Guide entries when a player cooks a
 * dish (chef cookbook) or prepares a graded beverage (brewer catalogue).
 *
 * <p>Registered lazily via {@code CompatLoader.register("fieldguide", ...)}:
 * this class is only loaded and instantiated when the Field Guide mod is
 * installed, and every Field Guide class reference lives inside this package
 * (see {@link FieldGuideApiAdapter}).
 *
 * <p>Chef unlock gate (all must hold):
 * <ol>
 *   <li>framework master switch enabled;</li>
 *   <li>{@code Config.FIELD_GUIDE_COOKBOOK_ENABLED} (default true);</li>
 *   <li>player present and automated production excluded;</li>
 *   <li>result count &gt; 0 and {@link DishClassifier#isDish};</li>
 *   <li>item is in {@code tcth:chef_catalog}.</li>
 * </ol>
 *
 * <p>Brewer unlock gate (all must hold, phase 7D):
 * <ol>
 *   <li>framework master switch enabled;</li>
 *   <li>{@code Config.FIELD_GUIDE_BREWER_ENABLED} (default true);</li>
 *   <li>player present and automated production excluded;</li>
 *   <li>result count &gt; 0 and a graded runtime tier (COMMON or T2);</li>
 *   <li>the Field Guide data defines an {@code item:*} entry for the beverage.</li>
 * </ol>
 *
 * <p>Only real {@code BeveragePreparedEvent}s unlock the brewer catalogue —
 * picking up, drinking or being given a beverage never unlocks (the generated
 * category entries pin a never-satisfied prerequisite so Field Guide's
 * implicit OBTAIN trigger cannot fire). Repeated preparation of an
 * already-unlocked entry does not re-notify.
 *
 * <p>Idempotency: a per-session {@link CookedEventIdCache} (max 4096, ~40
 * tick TTL, cleared on server stopping) records event ids only after a
 * successful unlock or a confirmed already-unlocked entry. A failed unlock is
 * logged (player, item, event id) and <em>not</em> committed, so the event can
 * be retried; one failing dish/beverage never affects statistics, experience
 * settlement or the server tick.
 *
 * <p>Field Guide itself keeps discovery state and persistence
 * ({@code FieldGuideProgressManager.tick} → {@code flushDirty}); TCTH only
 * triggers unlocks through the public API and keeps its own dynamic cooking /
 * brewing statistics in {@code tcth_cooking_stats.dat} / {@code tcth_brewing_stats.dat}.
 */
public final class FieldGuideCompatModule implements CompatModule {

    /** The {@code tcth:chef_catalog} item tag (union of the three tier tags). */
    public static final TagKey<Item> CHEF_CATALOG_TAG = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("tcth", "chef_catalog"));

    private static final ResourceLocation CATEGORY_COMMON =
            ResourceLocation.fromNamespaceAndPath("tcth", "chef_common");
    private static final ResourceLocation CATEGORY_T2 =
            ResourceLocation.fromNamespaceAndPath("tcth", "chef_t2");
    private static final ResourceLocation CATEGORY_T3 =
            ResourceLocation.fromNamespaceAndPath("tcth", "chef_t3");

    /** Brewer categories (phase 7D): COMMON 18 / T2 46 explicit entries. */
    private static final ResourceLocation BREW_CATEGORY_COMMON =
            ResourceLocation.fromNamespaceAndPath("tcth", "brew_common");
    private static final ResourceLocation BREW_CATEGORY_T2 =
            ResourceLocation.fromNamespaceAndPath("tcth", "brew_t2");

    private FieldGuideApi api = new FieldGuideApiAdapter();
    private final CookedEventIdCache processedEventIds = new CookedEventIdCache();

    /** Test-injectable game bus; {@code null} means {@link NeoForge#EVENT_BUS}. */
    private IEventBus gameBus;

    /** Field Guide integration master switch; production reads {@link Config#FIELD_GUIDE_ENABLED}. */
    private BooleanSupplier fieldGuideEnabledSupplier = FieldGuideCompatModule::fieldGuideEnabled;

    /** Cookbook switch; production reads {@link Config#FIELD_GUIDE_COOKBOOK_ENABLED}. */
    private BooleanSupplier cookbookEnabledSupplier = FieldGuideCompatModule::cookbookEnabled;

    /** Brewer catalogue switch; production reads {@link Config#FIELD_GUIDE_BREWER_ENABLED}. */
    private BooleanSupplier brewerEnabledSupplier = FieldGuideCompatModule::brewerEnabled;

    /** Framework master switch; production delegates to {@link CompatLoader}. */
    private BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;

    /** Catalog membership; production checks the {@code tcth:chef_catalog} tag. */
    private Predicate<Holder<Item>> catalogPredicate = FieldGuideCompatModule::isInChefCatalog;

    private boolean categoryReported = false;

    /** Unlock-failure log throttling (60 s), mirrors other TCTH modules. */
    private static final long ERROR_THROTTLE_MS = 60_000L;
    private long lastUnlockErrorAt = 0L;

    public FieldGuideCompatModule() {
    }

    @Override
    public String modId() {
        return "fieldguide";
    }

    @Override
    public void onModConstruction(IEventBus modEventBus) {
        IEventBus bus = gameBus != null ? gameBus : NeoForge.EVENT_BUS;
        bus.addListener(this::onDishCooked);
        bus.addListener(this::onBeveragePrepared);
        bus.addListener(this::onServerTick);
        bus.addListener(this::onServerStopping);
        TCTHIntegration.LOGGER.info("[TCTH] Field Guide cookbook module active (unlock on dish take-out / beverage prepared)");
    }

    // ---- game bus listeners ----

    private void onDishCooked(DishCookedEvent event) {
        handleDishCooked(event);
    }

    private void onBeveragePrepared(BeveragePreparedEvent event) {
        handleBeveragePrepared(event);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        try {
            processedEventIds.tick();
            reportCategoryLoadOnce();
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Field Guide tick handler failed: {}", e.toString());
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        try {
            processedEventIds.clear();
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Field Guide stop handler failed: {}", e.toString());
        }
    }

    // ---- business logic (package-private for tests) ----

    /**
     * Evaluates the unlock gate and unlocks the Field Guide entry for the
     * cooked dish. Never throws: a failure is logged (player, item, event id)
     * and the event id is NOT committed, so a redelivered event can be
     * retried, and one failing dish never affects cooking stats, experience
     * settlement or other listeners.
     */
    void handleDishCooked(DishCookedEvent event) {
        try {
            handleDishCookedUnchecked(event);
        } catch (RuntimeException | LinkageError e) {
            unlockErrorThrottled("[TCTH] Field Guide unlock handler failed for player '{}' item '{}' event {}: {}",
                    event.getPlayer() != null ? event.getPlayer().getName().getString() : "?",
                    event.getResult().getItem(), event.getEventId(), e.toString());
        }
    }

    private void handleDishCookedUnchecked(DishCookedEvent event) {
        if (!frameworkEnabledSupplier.getAsBoolean()) {
            return;
        }
        if (!fieldGuideEnabledSupplier.getAsBoolean()) {
            return;
        }
        if (!cookbookEnabledSupplier.getAsBoolean()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (event.isAutomated()) {
            return;
        }
        ItemStack result = event.getResult();
        if (result.isEmpty()) {
            return;
        }
        if (!DishClassifier.isDish(result)) {
            return;
        }
        if (!catalogPredicate.test(result.getItemHolder())) {
            return;
        }
        if (processedEventIds.isProcessed(event.getEventId())) {
            return;
        }
        if (!api.isProgressAvailable(player)) {
            // Field Guide progress system not live for this player yet; do not
            // commit — a later delivery may still unlock.
            return;
        }
        ResourceLocation itemId = result.getItem().builtInRegistryHolder().key().location();
        // Field Guide keys auto-populated item entries as "<kind>:<namespace>/<path>"
        // (e.g. "item:minecraft/cooked_cod"). The raw item id ("minecraft:cooked_cod")
        // does NOT match any entry, so the prefixed form is required.
        ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath("item",
                itemId.getNamespace() + "/" + itemId.getPath());
        if (!api.hasEntry(entryId)) {
            // The Field Guide data has no entry for this item (e.g. server
            // data reload pending); treat as nothing to unlock. Do not commit:
            // once the category data arrives the same event may unlock.
            return;
        }
        boolean unlockedNow = api.unlock(player, entryId);
        processedEventIds.commit(event.getEventId());
        if (unlockedNow) {
            TCTHIntegration.LOGGER.debug("[TCTH] Field Guide entry '{}' unlocked for player '{}' (dish take-out)",
                    entryId, player.getName().getString());
        }
    }

    // ---- brewer catalogue (phase 7D) ----

    /**
     * Evaluates the unlock gate and unlocks the Field Guide entry for the
     * prepared beverage. Never throws: a failure is logged (player, item,
     * event id) and the event id is NOT committed, so a redelivered event can
     * be retried, and one failing beverage never affects brewing stats,
     * experience settlement or other listeners.
     */
    void handleBeveragePrepared(BeveragePreparedEvent event) {
        try {
            handleBeveragePreparedUnchecked(event);
        } catch (RuntimeException | LinkageError e) {
            unlockErrorThrottled("[TCTH] Field Guide brewer unlock handler failed for player '{}' item '{}' event {}: {}",
                    event.getPlayer() != null ? event.getPlayer().getName().getString() : "?",
                    event.getResult().getItem(), event.getEventId(), e.toString());
        }
    }

    private void handleBeveragePreparedUnchecked(BeveragePreparedEvent event) {
        if (!frameworkEnabledSupplier.getAsBoolean()) {
            return;
        }
        if (!fieldGuideEnabledSupplier.getAsBoolean()) {
            return;
        }
        if (!brewerEnabledSupplier.getAsBoolean()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (event.isAutomated()) {
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        BeverageTier tier = event.getTier();
        if (tier == null || tier == BeverageTier.UNKNOWN || tier == BeverageTier.T3) {
            return; // only graded COMMON/T2 beverages are catalogued
        }
        if (processedEventIds.isProcessed(event.getEventId())) {
            return;
        }
        if (!api.isProgressAvailable(player)) {
            // Field Guide progress system not live for this player yet; do not
            // commit — a later delivery may still unlock.
            return;
        }
        ResourceLocation itemId = result.getItem().builtInRegistryHolder().key().location();
        // Field Guide keys auto-populated item entries as "<kind>:<namespace>/<path>"
        // (e.g. "item:minecraft/honey_bottle").
        ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath("item",
                itemId.getNamespace() + "/" + itemId.getPath());
        if (!api.hasEntry(entryId)) {
            // The Field Guide data has no entry for this item (e.g. server
            // data reload pending); treat as nothing to unlock. Do not commit.
            return;
        }
        boolean unlockedNow = api.unlock(player, entryId);
        processedEventIds.commit(event.getEventId());
        if (unlockedNow) {
            TCTHIntegration.LOGGER.debug("[TCTH] Field Guide brewer entry '{}' unlocked for player '{}' (beverage prepared)",
                    entryId, player.getName().getString());
        }
    }

    /**
     * Once per server run, reports how many entries each chef / brewer category
     * loaded (explicit entries generated from the chef tags and the beverage
     * tier mapping). Used by smoke tests to verify the Field Guide data loaded
     * with zero errors.
     */
    void reportCategoryLoadOnce() {
        if (categoryReported) {
            return;
        }
        categoryReported = true;
        try {
            ServerFieldGuideManager manager = ServerFieldGuideManager.getInstance();
            TCTHIntegration.LOGGER.info("[TCTH] Field Guide chef categories: {}={}, {}={}, {}={}",
                    CATEGORY_COMMON, manager.getEntryIdsForCategory(CATEGORY_COMMON).size(),
                    CATEGORY_T2, manager.getEntryIdsForCategory(CATEGORY_T2).size(),
                    CATEGORY_T3, manager.getEntryIdsForCategory(CATEGORY_T3).size());
            TCTHIntegration.LOGGER.info("[TCTH] Field Guide brewer categories: {}={}, {}={}",
                    BREW_CATEGORY_COMMON, manager.getEntryIdsForCategory(BREW_CATEGORY_COMMON).size(),
                    BREW_CATEGORY_T2, manager.getEntryIdsForCategory(BREW_CATEGORY_T2).size());
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.warn("[TCTH] Field Guide category report unavailable: {}", e.toString());
        }
    }

    /**
     * Default catalog check: the item must be in the {@code tcth:chef_catalog}
     * item tag (union of the three tier tags).
     */
    private static boolean isInChefCatalog(Holder<Item> holder) {
        return holder.is(CHEF_CATALOG_TAG);
    }

    // ---- fail-closed config reads ----
    //
    // A config read that throws (e.g. a malformed/incompatible config after a
    // hot reload) must fail CLOSED: treat the switch as false rather than
    // propagate an exception into the game tick or an unlock handler.

    /** Field Guide master switch; config-exception → false. */
    private static boolean fieldGuideEnabled() {
        try {
            return Config.FIELD_GUIDE_ENABLED.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Chef cookbook switch; config-exception → false. */
    private static boolean cookbookEnabled() {
        try {
            return Config.FIELD_GUIDE_COOKBOOK_ENABLED.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Brewer catalogue switch; config-exception → false. */
    private static boolean brewerEnabled() {
        try {
            return Config.FIELD_GUIDE_BREWER_ENABLED.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Throttled unlock-failure log (60 s window): a repeated failing event
     * must not spam the server log while still surfacing the first occurrence.
     */
    private void unlockErrorThrottled(String message, Object... args) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastUnlockErrorAt < ERROR_THROTTLE_MS) {
                return;
            }
            lastUnlockErrorAt = now;
        }
        TCTHIntegration.LOGGER.error(message, args);
    }

    // ---- test hooks (package-private, not part of the public API) ----

    void setApiForTesting(FieldGuideApi api) {
        this.api = api;
    }

    void setGameBusForTesting(IEventBus bus) {
        this.gameBus = bus;
    }

    void setFieldGuideEnabledSupplierForTesting(BooleanSupplier supplier) {
        this.fieldGuideEnabledSupplier = supplier;
    }

    void setCookbookEnabledSupplierForTesting(BooleanSupplier supplier) {
        this.cookbookEnabledSupplier = supplier;
    }

    void setBrewerEnabledSupplierForTesting(BooleanSupplier supplier) {
        this.brewerEnabledSupplier = supplier;
    }

    void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        this.frameworkEnabledSupplier = supplier;
    }

    void setCatalogPredicateForTesting(Predicate<Holder<Item>> predicate) {
        this.catalogPredicate = predicate;
    }

    void tickForTesting() {
        processedEventIds.tick();
    }

    void stopForTesting() {
        processedEventIds.clear();
    }

    int processedSizeForTesting() {
        return processedEventIds.size();
    }

    void resetForTesting() {
        processedEventIds.clear();
        categoryReported = false;
        lastUnlockErrorAt = 0L;
        api = new FieldGuideApiAdapter();
        gameBus = null;
        fieldGuideEnabledSupplier = FieldGuideCompatModule::fieldGuideEnabled;
        cookbookEnabledSupplier = FieldGuideCompatModule::cookbookEnabled;
        brewerEnabledSupplier = FieldGuideCompatModule::brewerEnabled;
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        catalogPredicate = FieldGuideCompatModule::isInChefCatalog;
    }
}
