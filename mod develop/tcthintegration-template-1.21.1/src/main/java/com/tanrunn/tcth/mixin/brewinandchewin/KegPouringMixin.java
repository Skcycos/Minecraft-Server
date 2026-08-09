package com.tanrunn.tcth.mixin.brewinandchewin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.brewinandchewin.KegPouringAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import umpaz.brewinandchewin.common.block.KegBlock;

/**
 * Publishes a {@code BeveragePreparedEvent} each time a player pours one real
 * beverage out of a Brewin' and Chewin' Keg (phase 7B / 7B.1).
 *
 * <p>JAR evidence (BrewinAndChewin 4.5.0, {@code KegBlock.useItemOn}):
 * <pre>
 *   if (stack.isEmpty()) → List.of()                // 空手：打开菜单，非取餐
 *   else → extractInWorld(stack, 1, instabuild)     // 玩家持容器灌装
 *   if (!list.isEmpty()) → list.forEach(lambda$useItemOn$0)
 *   lambda$useItemOn$0(result, player, hand, held):
 *       0: isSameItemSameComponents(result, held) → return        // 同物：不交付
 *       8: result.isEmpty() → setItemInHand(hand, result) → ret   // 替换手中
 *      24: Inventory.add(result) → true → return                  // 背包成功
 *      35: Player.drop(result, false) → return                    // 背包满掉落
 * </pre>
 *
 * <p>7B.1 correction: the event is published only <em>after</em> the actual
 * delivery completes — at {@code setItemInHand} AFTER (replacement),
 * {@code Inventory.add} returning {@code true} (inventory), or
 * {@code Player.drop} AFTER (dropped on the ground). The same beverage is
 * published at most once because the lambda executes at most one delivery
 * branch per poured stack; the original container / empty bottle is never
 * published (the result is the pouring output, not the carrier).
 *
 * <p>recipeId is always {@code null} (7A.1: {@code KegPouringRecipe} has no
 * id). Only runtime beverages (tier != UNKNOWN) publish; same-item no-op,
 * failed pours and non-runtime items are 0 events.
 *
 * <p>Exception note: the Mixin runtime (sponge-mixin 0.8.7) has no
 * {@code THROW} injection point; if {@code extractInWorld} or a delivery call
 * throws, no event is published and no state is leaked (stateless handler).
 * Known limitation kept on purpose.
 *
 * <p>Gated by {@code requiredMods=["brewinandchewin"]}.
 */
@Mixin(KegBlock.class)
public abstract class KegPouringMixin {

    @Inject(method = "lambda$useItemOn$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;setItemInHand(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V",
                    shift = At.Shift.AFTER))
    private static void tcth$onReplacedInHand(ItemStack originalHeldStack, Player player, InteractionHand hand, ItemStack deliveredStack,
                                       CallbackInfo ci) {
        // 7C.2: @Inject binds handler params positionally, so `originalHeldStack`
        // = lambda param0 (the original held stack, now empty after shrink) and
        // `deliveredStack` = lambda param3 (itm = the actually delivered beverage).
        // Publish `deliveredStack`, never `originalHeldStack` (air after shrink).
        publishAfterDelivery(player, deliveredStack);
    }

    @Redirect(method = "lambda$useItemOn$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean tcth$onInventoryAdd(Inventory inventory, ItemStack result) {
        // Snapshot the delivered stack BEFORE add: Inventory.add may shrink the
        // original to zero, so publishing the post-add result would be empty.
        int beforeCount = result.getCount();
        ItemStack snapshot = result.copy();
        boolean added = inventory.add(result);
        if (added) {
            int moved = beforeCount - result.getCount();
            if (moved > 0) {
                // Publish the actually-added amount built from the pre-add
                // snapshot (never the possibly-empty post-add result).
                publishAfterDelivery(inventory.player, snapshot.copyWithCount(moved));
            }
        }
        return added;
    }

    @Inject(method = "lambda$useItemOn$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
                    shift = At.Shift.AFTER))
    private static void tcth$onDropped(ItemStack originalHeldStack, Player player, InteractionHand hand, ItemStack deliveredStack,
                                CallbackInfo ci) {
        // 7C.2: drop branch published the remaining containers
        // (originalHeldStack = param0 = stack) which are UNKNOWN-tier. The
        // actually dropped beverage is `deliveredStack` (param3 = itm).
        // Publish `deliveredStack`.
        publishAfterDelivery(player, deliveredStack);
    }

    private static void publishAfterDelivery(Player player, ItemStack delivered) {
        try {
            if (delivered == null || delivered.isEmpty()) {
                return;
            }
            if (!(player.level() instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            // Keg block position is not reliably available from the static
            // lambda; publish with a null position rather than faking one with
            // the player's coordinates (7B.1.1).
            KegPouringAdapter.onPouringDelivered(serverPlayer, delivered, serverLevel, null);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Keg pouring publish failed: {}", e.toString());
        }
    }
}
