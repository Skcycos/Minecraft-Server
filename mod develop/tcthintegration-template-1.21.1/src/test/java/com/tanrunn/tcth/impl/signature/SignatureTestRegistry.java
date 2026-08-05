package com.tanrunn.tcth.impl.signature;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Provides the {@code tcth:cooking_signature} component type for unit tests.
 *
 * <p>The bare JUnit environment freezes {@link BuiltInRegistries} during
 * bootstrap, so tests never register the component; instead they construct the
 * {@link DataComponentType} directly and install it via the package-private
 * override hook ({@link ItemStack} component storage does not require the
 * registry).
 */
public final class SignatureTestRegistry {

    static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TCTHIntegration.MODID, CookingSignatureComponents.COMPONENT_ID);

    private static boolean done = false;

    private SignatureTestRegistry() {
    }

    public static void ensureRegistered() {
        if (done) {
            return;
        }
        MinecraftTestBootstrap.bootStrap();
        if (CookingSignatureComponents.type() == null) {
            DataComponentType<CookingSignature> type = DataComponentType.<CookingSignature>builder()
                    .persistent(CookingSignature.CODEC)
                    .networkSynchronized(CookingSignature.STREAM_CODEC)
                    .build();
            CookingSignatureComponents.setTypeOverrideForTesting(type);
        }
        done = true;
    }
}
