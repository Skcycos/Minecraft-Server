package com.tanrunn.tcth.impl.shadow;

import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Per-reload immutable shadow_loot listener (8D.3.1 §1, 8D.3.2 §2): the
 * RegistryAccess is bound AT CONSTRUCTION from
 * {@code AddReloadListenerEvent.getRegistryAccess()} — the registries frozen
 * for THIS reload — and is never read from the lifecycle current server. A
 * new listener instance is created for every reload, so two reloads can never
 * share stale registry state.
 *
 * <p>{@link #prepare} delegates to {@link ShadowLootLoader#prepare} — the
 * production listener and the priority / no-fallback tests share ONE parsing
 * implementation.
 */
public final class ShadowLootReloadListener
        extends SimplePreparableReloadListener<Map<ResourceLocation, JsonObject>> {

    private final RegistryAccess registryAccess;

    public ShadowLootReloadListener(RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    @Override
    protected Map<ResourceLocation, JsonObject> prepare(ResourceManager manager,
                                                        ProfilerFiller profiler) {
        return ShadowLootLoader.prepare(manager);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonObject> raw, ResourceManager manager,
                         ProfilerFiller profiler) {
        // RegistryAccess bound to THIS reload; null → fail-closed empty map.
        ShadowLootLoader.publish(raw, registryAccess);
    }
}
