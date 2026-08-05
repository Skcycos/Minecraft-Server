package com.tanrunn.tcth.client;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Client-side tooltip for signed dishes.
 *
 * <p>Adds a single low-key line when the held/stacked item carries a valid
 * {@code tcth:cooking_signature}:
 * <pre>主厨：Tanrunn / Chef: Tanrunn</pre>
 * Nothing is shown for unsigned items, the item's original name is untouched,
 * and the UUID is never displayed. This class is client-only
 * ({@code Dist.CLIENT}); dedicated servers never load it.
 */
@EventBusSubscriber(modid = TCTHIntegration.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class TooltipEvents {

    private TooltipEvents() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CookingSignature signature = stack.get(CookingSignatureComponents.type());
        if (!shouldRender(signature)) {
            return;
        }
        event.getToolTip().add(Component.translatable("tooltip.tcth.cooking_signature", signature.chefName())
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Whether a signature should be rendered: present and with a non-blank,
     * sanitized chef name. Blank/absent signatures never render.
     */
    static boolean shouldRender(CookingSignature signature) {
        if (signature == null) {
            return false;
        }
        String name = signature.chefName();
        return name != null && !name.isBlank();
    }
}
