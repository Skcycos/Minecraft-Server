package com.tanrunn.tcth.mixin.mynethersdelight;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.compat.mynethersdelight.MyNethersDelightPortioningAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.soytutta.mynethersdelight.common.block.feasts.StuffedHoglinBlock;

/**
 * Publishes a TCTH dish event each time a player takes one real serving out
 * of a placed MyNethersDelight Stuffed Hoglin (Feast pattern), signing the
 * REAL stack handed to the inventory / drop call (phase 6D.1).
 *
 * <p>JAR evidence (MyNethersDelight 1.10.4,
 * {@code StuffedHoglinBlock.takeServing}):
 * <pre>
 *   ... PASS_TO_DEFAULT_BLOCK_INTERACTION when isValidPair fails or servings &lt;= 0 ...
 *   Inventory.add(new ItemStack(item))   // add=false → Player.drop(new ItemStack(item), false)
 *   return SUCCESS
 * </pre>
 * Two distinct {@code new ItemStack(item)} are constructed — one for
 * {@code Inventory.add}, one for {@code Player.drop}. This mixin:
 * <ul>
 *   <li>{@code @Inject} BEFORE {@code Inventory.add} — publishes exactly one
 *       PORTIONING event (add path only);</li>
 *   <li>{@code @ModifyArg} on {@code Inventory.add} arg0 — signs the REAL
 *       stack (using the player captured at HEAD) before it enters the
 *       inventory, so the added stack carries the signature when the add
 *       succeeds;</li>
 *   <li>{@code @ModifyArg} on {@code Player.drop} arg0 — signs the REAL drop
 *       stack (same HEAD-captured player) so the dropped entity carries the
 *       signature when the inventory was full; NO second publish.</li>
 *   <li>{@code @Inject} HEAD — captures the acting player into
 *       {@code tcth$servingPlayer} (both @ModifyArg handlers read it, so they
 *       do not depend on the execution order of @Inject/@ModifyArg at the
 *       {@code Inventory.add} INVOKE);</li>
 *   <li>{@code @Inject} RETURN — unconditionally clears the recorded player
 *       on the normal return path (after the drop-path {@code @ModifyArg} has
 *       signed in the {@code add=false} case, since RETURN runs after every
 *       instruction of {@code takeServing}).</li>
 * </ul>
 * PASS / no-servings paths never reach the add/drop calls → 0 events, and the
 * HEAD-captured player is cleared by the RETURN injection on those paths too.
 * The instance field is safe because a server player drives one block
 * interaction at a time on the server thread.
 *
 * <p>Known limitation (6D.2): this Mixin runtime has no {@code THROW}
 * injection point (see 6B.2.3), so if {@code takeServing} throws, the RETURN
 * cleanup does not run and the field stays set until the next HEAD resets it.
 * This does not affect publish/sign correctness (the field is only read on the
 * add/drop paths which precede the throw), it only defers the cleanup.
 *
 * <p>Gated by {@code requiredMods=["mynethersdelight"]}.
 */
@Mixin(StuffedHoglinBlock.class)
public abstract class StuffedHoglinTakeServingMixin {

    @Unique
    private Player tcth$servingPlayer;

    @Inject(method = "takeServing", at = @At("HEAD"))
    private void tcth$capturePlayerAtHead(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                          Player player, net.minecraft.world.InteractionHand hand, Item servingItem,
                                          CallbackInfoReturnable<net.minecraft.world.ItemInteractionResult> cir) {
        // Capture the acting player at HEAD. Both @ModifyArg handlers below
        // read this field, so they do NOT depend on the execution order of
        // @Inject/@ModifyArg at the Inventory.add INVOKE (6D.2 single-point
        // hardening).
        this.tcth$servingPlayer = player;
    }

    @Inject(method = "takeServing",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z",
                    shift = At.Shift.BEFORE))
    private void tcth$publishOnAdd(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                   Player player, net.minecraft.world.InteractionHand hand, Item servingItem,
                                   CallbackInfoReturnable<net.minecraft.world.ItemInteractionResult> cir) {
        // Publish only; the player for signing is the one captured at HEAD.
        try {
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            MyNethersDelightPortioningAdapter.onServingDelivered(serverPlayer, servingItem, serverLevel, pos);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Stuffed hoglin serving publish failed: {}", e.toString());
        }
    }

    @ModifyArg(method = "takeServing",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),
            index = 0)
    private ItemStack tcth$signInventoryArg(ItemStack stack) {
        try {
            return MyNethersDelightPortioningAdapter.signServingStack(stack, this.tcth$servingPlayer);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Stuffed hoglin serving sign failed: {}", e.toString());
            return stack;
        }
    }

    @ModifyArg(method = "takeServing",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"),
            index = 0)
    private ItemStack tcth$signDropArg(ItemStack stack) {
        try {
            // Sign only — no publish (the add path already published exactly once).
            return MyNethersDelightPortioningAdapter.signServingStack(stack, this.tcth$servingPlayer);
        } catch (RuntimeException | LinkageError e) {
            TCTHIntegration.LOGGER.error("[TCTH] Stuffed hoglin drop sign failed: {}", e.toString());
            return stack;
        }
    }

    @Inject(method = "takeServing", at = @At("RETURN"))
    private void tcth$clearPlayerAtReturn(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                                          Player player, net.minecraft.world.InteractionHand hand, Item servingItem,
                                          CallbackInfoReturnable<net.minecraft.world.ItemInteractionResult> cir) {
        // Unconditional cleanup on the normal return path. The RETURN injection
        // runs after every instruction of takeServing, so in the add=false case
        // the drop-path @ModifyArg has already signed the drop stack before we
        // clear the player. Known limitation: if takeServing throws, this RETURN
        // injection does not run and the field stays set until the next HEAD
        // (the Mixin runtime has no THROW injection point — see 6B.2.3).
        this.tcth$servingPlayer = null;
    }
}
