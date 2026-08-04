package com.tanrunn.tcth.impl.detector;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.classifier.DishClassifier;
import com.tanrunn.tcth.impl.event.DishCookedEventDispatcher;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Vanilla cooking detector.
 *
 * <p>Listens to the NeoForge public events fired when a player takes a crafted
 * or smelted result:
 * <ul>
 *   <li>{@link PlayerEvent.ItemCraftedEvent} — crafting table;</li>
 *   <li>{@link PlayerEvent.ItemSmeltedEvent} — furnace / smoker.</li>
 * </ul>
 *
 * <p>These events only fire when a <em>player</em> takes the result, so the
 * result is never automated. Bulk results are expressed through
 * {@code ItemStack.getCount()} — one event per take, never one event per item.
 */
@EventBusSubscriber(modid = TCTHIntegration.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VanillaCookingDetector {

    private VanillaCookingDetector() {
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack result = event.getCrafting();
        if (!DishClassifier.isDish(result)) {
            return;
        }
        publishDish(player, result, CookingDevice.CRAFTING);
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack result = event.getSmelting();
        if (!DishClassifier.isDish(result)) {
            return;
        }
        publishDish(player, result, deviceForSmelting(player));
    }

    private static void publishDish(ServerPlayer player, ItemStack result, CookingDevice device) {
        ServerLevel level = player.serverLevel();
        DishCookedEventDispatcher.publish(player, null, result, device, DishQuality.UNKNOWN, false, level, null);
    }

    /**
     * Distinguishes furnace vs smoker from the player's currently open menu.
     *
     * <p>If the menu cannot be matched (or the player is not viewing one of
     * these menus), {@link CookingDevice#FURNACE} is used as the fallback. This
     * fallback is deliberate and documented: automated smelting never fires
     * this event (there is no player), and when a player takes a smelted
     * result they are almost always viewing the furnace/smoker screen.
     */
    private static CookingDevice deviceForSmelting(@Nullable Player player) {
        if (player != null && player.containerMenu instanceof SmokerMenu) {
            return CookingDevice.SMOKER;
        }
        if (player != null && player.containerMenu instanceof FurnaceMenu) {
            return CookingDevice.FURNACE;
        }
        return CookingDevice.FURNACE;
    }
}
