package com.tanrunn.tcth.impl.compat.jobsplus.powerup;

/**
 * Mutually-exclusive durability-skip routing for the tilling / knife routes
 * (4C.1 contract, extracted 8C.3.1).
 *
 * <p>A stack is classified <em>exactly once</em>: a hoe takes the farmer
 * tilling route and the decision ends there (never falls through to the chef
 * route); otherwise a knife takes the chef route. An item in BOTH
 * {@code #minecraft:hoes} and {@code #c:tools/knife} can never roll both
 * probabilities. Each route reads its combined gate once and rolls its random
 * once.
 *
 * <p>Plain Java — the only place that can run in a bare unit test. The
 * {@code ItemStackDurabilityMixin} private static helper delegates here;
 * Sponge Mixin rejects non-private static methods in mixin classes, so the
 * routing logic must never live in the mixin itself.
 */
public final class DurabilityAbilityRouter {

    private DurabilityAbilityRouter() {
    }

    /** @return whether the durability loss should be skipped for the stack
     *  (hoe first and immediately, otherwise knife, otherwise never) */
    public static boolean shouldSkipDurability(net.minecraft.server.level.ServerPlayer player,
                                               net.minecraft.world.item.ItemStack stack) {
        if (stack.is(FarmerAbilityModule.HOES_TAG)) {
            return FarmerAbilityModule.shouldSkipHoeDurability(player, stack);
        }
        if (stack.is(ChefAbilityModule.KNIVES_TAG)) {
            return ChefAbilityModule.shouldSkipKnifeDurability(player, stack);
        }
        return false;
    }
}
