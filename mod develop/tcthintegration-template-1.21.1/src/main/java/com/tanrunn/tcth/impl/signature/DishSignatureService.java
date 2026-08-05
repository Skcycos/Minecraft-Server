package com.tanrunn.tcth.impl.signature;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.Config;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.compat.CompatLoader;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Signs finished dishes with the chef's {@code tcth:cooking_signature}
 * component.
 *
 * <p>The service receives the <em>actual</em> result stack that will be (or
 * has been) handed to the player and mutates it in place. Signature rules:
 * <ol>
 *   <li>framework master switch enabled and {@code dishSignaturesEnabled};</li>
 *   <li>player present, not automated;</li>
 *   <li>stack non-null, {@code count > 0};</li>
 *   <li>{@link DishClassifier#isDish} and not in {@code tcth:not_dishes}
 *       (handled by the classifier).</li>
 * </ol>
 *
 * <p>Behaviour:
 * <ul>
 *   <li>the player who takes the dish out becomes the new chef — any previous
 *       signature is overwritten (ingredient signatures do not carry over);</li>
 *   <li>count, quality components and all other mods' components are left
 *       untouched;</li>
 *   <li>containers, bowls, shovels and tools are never signed (the caller
 *       only passes the real dish stack);</li>
 *   <li>failures are logged and never block the take-out or the
 *       {@code DishCookedEvent}.</li>
 * </ul>
 *
 * <p>Security boundary: the signature is provenance/display data, not a
 * trusted economic credential. No reward logic may trust the component alone.
 */
public final class DishSignatureService {

    /** Framework switch; production delegates to {@link CompatLoader}. */
    private static BooleanSupplier frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;

    /** Signature switch; production reads {@link Config#DISH_SIGNATURES_ENABLED}. */
    private static BooleanSupplier signaturesEnabledSupplier = () -> Config.DISH_SIGNATURES_ENABLED.get();

    private DishSignatureService() {
    }

    /**
     * Signs the actual result stack in place.
     *
     * @param player the taking player (must be a server player)
     * @param result the real result stack handed to the player; mutated in
     *               place when signing succeeds
     * @return {@code true} if the stack now carries a signature
     */
    public static boolean sign(@Nullable ServerPlayer player, ItemStack result) {
        try {
            if (!frameworkEnabledSupplier.getAsBoolean()) {
                return false;
            }
            if (!signaturesEnabledSupplier.getAsBoolean()) {
                return false;
            }
            if (player == null) {
                return false;
            }
            if (result == null || result.isEmpty() || result.getCount() <= 0) {
                return false;
            }
            if (!DishClassifier.isDish(result)) {
                return false;
            }
            UUID chefId = player.getUUID();
            String chefName = player.getGameProfile() == null
                    ? null
                    : player.getGameProfile().getName();
            if (chefName == null || chefName.isBlank()) {
                return false;
            }
            // Overwrite any previous signature: the player finishing this dish
            // is the new chef (ingredient signatures never carry over).
            result.set(CookingSignatureComponents.type(), new CookingSignature(chefId, chefName));
            return true;
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Failed to sign dish for player '{}': {}",
                    safeName(player), e.toString());
            return false;
        }
    }

    private static String safeName(@Nullable ServerPlayer player) {
        if (player == null) {
            return "?";
        }
        try {
            if (player.getGameProfile() != null) {
                return player.getGameProfile().getName();
            }
            return String.valueOf(player.getUUID());
        } catch (RuntimeException | LinkageError ignored) {
            return "?";
        }
    }

    // ---- test hooks (not part of the public API) ----

    static void setFrameworkEnabledSupplierForTesting(BooleanSupplier supplier) {
        frameworkEnabledSupplier = supplier;
    }

    static void setSignaturesEnabledSupplierForTesting(BooleanSupplier supplier) {
        signaturesEnabledSupplier = supplier;
    }

    static void resetForTesting() {
        frameworkEnabledSupplier = CompatLoader::isFrameworkEnabled;
        signaturesEnabledSupplier = () -> Config.DISH_SIGNATURES_ENABLED.get();
    }
}
