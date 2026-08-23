package com.tanrunn.tcth.impl.shadow;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only countdown rendered immediately above the hotbar. */
public final class ShadowCooldownHud {

    private static int remainingTicks;
    private static boolean initialized;

    private ShadowCooldownHud() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ShadowCooldownHud::onClientTick);
        NeoForge.EVENT_BUS.addListener(ShadowCooldownHud::onRenderGui);
    }

    public static void accept(ShadowCooldownPayload payload) {
        remainingTicks = payload == null ? 0 : Math.max(0, payload.remainingTicks());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (remainingTicks <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.font == null) {
            return;
        }

        String seconds = String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0d);
        Component text = Component.literal("影窃冷却：" + seconds + " 秒");
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int x = width / 2;
        int y = height - 58;
        int textWidth = minecraft.font.width(text);
        event.getGuiGraphics().fill(x - textWidth / 2 - 6, y - 3,
                x + textWidth / 2 + 6, y + minecraft.font.lineHeight + 3, 0x90000000);
        event.getGuiGraphics().drawCenteredString(minecraft.font, text, x, y, 0xFFFFD54F);
    }

}
