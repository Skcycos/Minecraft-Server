package com.tanrunn.tcth.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tanrunn.tcth.impl.compat.jobsplus.powerup.ChefAbilityModule;
import com.tanrunn.tcth.impl.compat.jobsplus.powerup.FarmerAbilityModule;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Skips durability loss for {@code #minecraft:hoes} tools (farmer tilling
 * route) and {@code #c:tools/knife} items (chef knife route) (phase 4B/4C).
 *
 * <p><strong>Why a TCTH mixin instead of Arc data:</strong> the 4C audit
 * (javap + live test) proved that {@code arc:on_hurt_item} never fires on this
 * server: NeoForge 21.1.247 moved the real durability logic into
 * {@code ItemStack.hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)}
 * (a NeoForge-added overload that the {@code LivingEntity + EquipmentSlot}
 * entry point delegates to), while Arc 9.0.0 injects the thin
 * {@code ServerPlayer} wrapper which hoes, mining and knives never call.
 * The chef knife route (same Arc mechanism) was silently affected too.
 *
 * <p>This mixin injects the actual durability overload at {@code HEAD} and
 * cancels it with the audited tilling (10% / 20% / 35%) and knife
 * (10% / 20% / 35%) probabilities by tier, replicating the intended
 * {@code arc:cancel_action} semantics. Loaded only when Jobs+ is installed
 * ({@code tcth_farmer_abilities.mixins.json}).
 *
 * <p><strong>Mutual exclusion (4C.1):</strong> {@link #shouldSkipDurability}
 * classifies the item once — a hoe routes exclusively through the farmer
 * tilling route and returns immediately, so an item that is somehow in BOTH
 * {@code #minecraft:hoes} and {@code #c:tools/knife} can never roll both
 * probabilities. Each route reads its combined gate once and rolls its
 * random once.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/LivingEntity;"
            + "Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void tcth$maybeSkipHoeDurability(int amount, ServerLevel level, LivingEntity entity,
                                             Consumer<Item> onBreak, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player
                && shouldSkipDurability(player, (ItemStack) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * One-pass classification + mutually exclusive routing. A hoe takes the
     * farmer route and the decision ends there (never falls through to the
     * chef route); otherwise a knife takes the chef route. Neither route
     * re-reads the other's gate.
     */
    public static boolean shouldSkipDurability(ServerPlayer player, ItemStack stack) {
        if (stack.is(FarmerAbilityModule.HOES_TAG)) {
            return FarmerAbilityModule.shouldSkipHoeDurability(player, stack);
        }
        if (stack.is(ChefAbilityModule.KNIVES_TAG)) {
            return ChefAbilityModule.shouldSkipKnifeDurability(player, stack);
        }
        return false;
    }
}
