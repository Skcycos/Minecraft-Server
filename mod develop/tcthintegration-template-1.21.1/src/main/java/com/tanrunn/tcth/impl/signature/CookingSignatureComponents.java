package com.tanrunn.tcth.impl.signature;

import com.tanrunn.tcth.TCTHIntegration;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration of the {@code tcth:cooking_signature} data component.
 *
 * <p>The component is <em>persistent</em> (survives saving/loading item
 * stacks) and <em>network synchronized</em> (syncs to the client so the
 * tooltip can render on dedicated servers too). Registered against
 * {@link Registries#DATA_COMPONENT_TYPE}.
 *
 * <p>Stack semantics: the component participates in stack merging, so two
 * identical chef dishes by the same player (same UUID + same name) stack
 * together, while dishes signed by different players do not. Because the name
 * is a historical snapshot, a renamed player's new dishes stack separately
 * from their old-name signatures — expected behaviour, documented in README.
 */
public final class CookingSignatureComponents {

    public static final String COMPONENT_ID = "cooking_signature";

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(TCTHIntegration.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CookingSignature>> COOKING_SIGNATURE =
            DATA_COMPONENTS.registerComponentType(COMPONENT_ID,
                    builder -> builder
                            .persistent(CookingSignature.CODEC)
                            .networkSynchronized(CookingSignature.STREAM_CODEC));

    /** Test override; null in production. */
    private static volatile DataComponentType<CookingSignature> typeOverride = null;

    private CookingSignatureComponents() {
    }

    /** Internal helper: the registered component type (registry lookup). */
    @SuppressWarnings("unchecked")
    public static DataComponentType<CookingSignature> type() {
        DataComponentType<CookingSignature> override = typeOverride;
        if (override != null) {
            return override;
        }
        return (DataComponentType<CookingSignature>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .get(ResourceLocation.fromNamespaceAndPath(TCTHIntegration.MODID, COMPONENT_ID));
    }

    /**
     * Resolves the component type without requiring the Minecraft registry:
     * returns the test override when set, otherwise the registered type, or
     * {@code null} when neither is available. Prefer this in logic that must
     * behave identically under unit tests and in-game.
     */
    @Nullable
    public static DataComponentType<CookingSignature> tryType() {
        DataComponentType<CookingSignature> override = typeOverride;
        if (override != null) {
            return override;
        }
        return isRegistered() ? type() : null;
    }

    /** @return {@code true} once the registry is loaded (server or client). */
    public static boolean isRegistered() {
        return BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath(TCTHIntegration.MODID, COMPONENT_ID)) != null;
    }

    // ---- test hook (not part of the public API) ----

    static void setTypeOverrideForTesting(DataComponentType<CookingSignature> type) {
        typeOverride = type;
    }

    static void clearTypeOverrideForTesting() {
        typeOverride = null;
    }
}
