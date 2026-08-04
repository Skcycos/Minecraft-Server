package com.tanrunn.tcth.impl.compat;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.compat.CompatModule;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Loader for conditional compat modules.
 *
 * <p>Modules are registered as <em>lazy descriptors</em> ({@code modId} +
 * implementation class name) and are only touched when the target mod is
 * actually present. This keeps the implementation class off the JVM classpath
 * resolution path when the optional dependency is missing:
 *
 * <ol>
 *   <li>{@link #register(String, String)} only records the descriptor —
 *       <strong>all descriptors must be registered before {@link #init}</strong>;
 *       after init the loader is frozen and registration is rejected;</li>
 *   <li>during {@link #init(IEventBus)}, for each descriptor: check
 *       {@code ModList.get().isLoaded(modId)} — if absent, log at DEBUG and
 *       skip;</li>
 *   <li>only then is the class resolved, validated as a {@link CompatModule},
 *       instantiated via its no-arg constructor, checked for {@code modId}
 *       consistency, and its {@link CompatModule#onModConstruction} hook
 *       invoked — the module enters the loaded list only if the whole chain
 *       succeeds;</li>
 *   <li>a failure at any stage is logged clearly but never prevents other
 *       modules from loading.</li>
 * </ol>
 *
 * <p>This loader does <strong>not</strong> enforce the master config switch.
 * Whether {@code enabled=false} suppresses business logic is a per-module
 * convention (see {@link #isFrameworkEnabled()}); a centralized guard is
 * planned at the unified settlement entry point (phase 1).
 */
public final class CompatLoader {
    private static final List<ModuleDescriptor> DESCRIPTORS = new ArrayList<>();
    private static final List<CompatModule> MODULES = new ArrayList<>();

    private static boolean initialized = false;
    private static IEventBus modBus;

    /**
     * Presence check for optional mods. Injected for unit tests; production
     * uses {@link ModList}.
     */
    private static Predicate<String> modPresence = CompatLoader::defaultModPresence;

    /**
     * Class resolution strategy. Injected for unit tests to prove that
     * implementation classes are never resolved when the target mod is
     * missing; production uses {@code Class::forName}.
     */
    private static ClassResolver classResolver = Class::forName;

    private CompatLoader() {
    }

    /**
     * Registers a lazy compat module descriptor.
     *
     * <p>Does <strong>not</strong> load or even touch the implementation class.
     * Loading happens during {@link #init(IEventBus)} once the target mod has
     * been confirmed present. Must be called <strong>before</strong>
     * {@link #init(IEventBus)}; after init the loader is frozen and this
     * method returns {@code false}.
     *
     * @param modId                  the target mod id
     * @param implementationClassName fully-qualified class name implementing
     *                                {@link CompatModule}
     * @return {@code true} if the descriptor was accepted, {@code false} on
     *         invalid arguments, duplicate mod id, or a post-init call
     */
    public static boolean register(String modId, String implementationClassName) {
        if (initialized) {
            TCTHIntegration.LOGGER.warn("[TCTH] Compat module registration for '{}' rejected: loader already initialized (all descriptors must be registered before init)", modId);
            return false;
        }
        if (modId == null || modId.isBlank()) {
            TCTHIntegration.LOGGER.error("[TCTH] Compat module registration rejected: modId must not be blank");
            return false;
        }
        if (implementationClassName == null || implementationClassName.isBlank()) {
            TCTHIntegration.LOGGER.error("[TCTH] Compat module registration for '{}' rejected: implementation class must not be blank", modId);
            return false;
        }
        boolean duplicate = DESCRIPTORS.stream().anyMatch(d -> d.modId().equals(modId));
        if (duplicate) {
            TCTHIntegration.LOGGER.warn("[TCTH] Duplicate compat module registration for '{}' ignored", modId);
            return false;
        }
        DESCRIPTORS.add(new ModuleDescriptor(modId, implementationClassName));
        return true;
    }

    /**
     * Bootstraps the loader. Called exactly once from the mod constructor.
     *
     * <p>Registers the common-setup listener and loads every registered
     * descriptor whose target mod is present. Calling this more than once is
     * safe and is a no-op.
     */
    public static void init(IEventBus modEventBus) {
        if (initialized) {
            TCTHIntegration.LOGGER.debug("[TCTH] CompatLoader.init called more than once; ignoring");
            return;
        }
        initialized = true;
        modBus = modEventBus;
        modEventBus.addListener(CompatLoader::onCommonSetup);
        TCTHIntegration.LOGGER.debug("[TCTH] Compat loader initialized ({} module(s) registered)", DESCRIPTORS.size());
        for (ModuleDescriptor descriptor : List.copyOf(DESCRIPTORS)) {
            loadModule(descriptor);
        }
    }

    /**
     * Forwards {@link FMLCommonSetupEvent} to every successfully loaded module.
     *
     * <p>Each module is invoked in isolation: a {@link RuntimeException} or
     * {@link LinkageError} thrown by one module is logged (with mod id, class
     * and lifecycle stage) and does not prevent later modules from running.
     * Severe JVM errors ({@link ThreadDeath}, {@link VirtualMachineError}, …)
     * are deliberately not caught.
     */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        for (CompatModule module : List.copyOf(MODULES)) {
            try {
                module.onCommonSetup(event);
            } catch (RuntimeException | LinkageError e) {
                TCTHIntegration.LOGGER.error("[TCTH] Compat module for mod '{}' (class {}) threw during common setup: {}",
                        module.modId(), module.getClass().getName(), e.toString());
            }
        }
    }

    /**
     * Whether the given mod id is present.
     */
    public static boolean isModLoaded(String modId) {
        return modPresence.test(modId);
    }

    /**
     * Master switch: whether the framework is enabled in the current config.
     *
     * <p>This is a <em>convention</em>, not a mechanical guarantee: compat
     * modules are expected to check it (or their own config toggle) before
     * performing business logic. The loader itself does not filter lifecycle
     * calls on it. A centralized guard is planned at the unified settlement
     * entry point in phase 1.
     */
    public static boolean isFrameworkEnabled() {
        return Config.ENABLED.get();
    }

    private static void loadModule(ModuleDescriptor descriptor) {
        if (!modPresence.test(descriptor.modId())) {
            TCTHIntegration.LOGGER.debug("[TCTH] Compat module skipped: mod '{}' not installed", descriptor.modId());
            return;
        }
        try {
            Class<?> clazz = classResolver.resolve(descriptor.implementationClassName());
            if (!CompatModule.class.isAssignableFrom(clazz)) {
                TCTHIntegration.LOGGER.error("[TCTH] Class '{}' for mod '{}' does not implement CompatModule; module disabled",
                        descriptor.implementationClassName(), descriptor.modId());
                return;
            }
            CompatModule module = (CompatModule) clazz.getDeclaredConstructor().newInstance();
            if (!descriptor.modId().equals(module.modId())) {
                TCTHIntegration.LOGGER.error("[TCTH] Compat module class '{}' reports modId '{}' but was registered for '{}'; module disabled",
                        descriptor.implementationClassName(), module.modId(), descriptor.modId());
                return;
            }
            // The construction hook runs before the module is exposed; a
            // failure here must leave the module out of the loaded list.
            module.onModConstruction(modBus);
            MODULES.add(module);
            TCTHIntegration.LOGGER.debug("[TCTH] Compat module loaded for mod '{}' (class {})", descriptor.modId(), descriptor.implementationClassName());
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            TCTHIntegration.LOGGER.error("[TCTH] Failed to construct compat module for mod '{}' (class '{}') during construction: {}",
                    descriptor.modId(), descriptor.implementationClassName(), e.toString());
        } catch (LinkageError e) {
            // NoClassDefFoundError etc.: the module references classes from an
            // incompatible dependency. Log and continue with other modules.
            TCTHIntegration.LOGGER.error("[TCTH] Failed to load compat module for mod '{}' (class '{}'): linkage error {}",
                    descriptor.modId(), descriptor.implementationClassName(), e.toString());
        } catch (RuntimeException e) {
            // Covers exceptions thrown from onModConstruction (and from static
            // initializers surfaced through it). Keep other modules alive.
            TCTHIntegration.LOGGER.error("[TCTH] Compat module for mod '{}' (class '{}') threw during construction: {}",
                    descriptor.modId(), descriptor.implementationClassName(), e.toString());
        }
    }

    private static boolean defaultModPresence(String modId) {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }

    // ---- test hooks (package-private, never part of the public API) ----

    static void setModPresenceForTesting(Predicate<String> predicate) {
        modPresence = predicate;
    }

    static void setClassResolverForTesting(ClassResolver resolver) {
        classResolver = resolver;
    }

    static List<CompatModule> loadedModulesForTesting() {
        return List.copyOf(MODULES);
    }

    static int descriptorCountForTesting() {
        return DESCRIPTORS.size();
    }

    static void resetForTesting() {
        DESCRIPTORS.clear();
        MODULES.clear();
        initialized = false;
        modBus = null;
        modPresence = CompatLoader::defaultModPresence;
        classResolver = Class::forName;
    }

    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String className) throws ClassNotFoundException;
    }

    private record ModuleDescriptor(String modId, String implementationClassName) {
    }
}
