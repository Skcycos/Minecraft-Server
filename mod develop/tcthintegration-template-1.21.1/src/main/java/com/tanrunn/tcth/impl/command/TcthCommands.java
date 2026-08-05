package com.tanrunn.tcth.impl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.debug.CookingDebug;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.stats.CookingStatsCommand;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * TCTH admin commands.
 *
 * <p>Exposes the cooking debug toggle
 * ({@code /tcth debug cooking on|off|status}, permission level &ge; 3) and the
 * read-only chef signature inspector {@code /tcth chef inspect} (any player).
 */
@EventBusSubscriber(modid = TCTHIntegration.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TcthCommands {

    private static final int PERMISSION_LEVEL = 3;

    private TcthCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        CookingStatsCommand.register(dispatcher);
        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("chef")
                        .then(Commands.literal("inspect")
                                .executes(TcthCommands::inspectChefSignature)))
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.literal("cooking")
                                .then(Commands.literal("on")
                                        .executes(TcthCommands::enableCookingDebug))
                                .then(Commands.literal("off")
                                        .executes(TcthCommands::disableCookingDebug))
                                .then(Commands.literal("status")
                                        .executes(TcthCommands::cookingDebugStatus)))));
    }

    /**
     * {@code /tcth chef inspect} — read-only; reports the signature of the
     * dish held in the player's main hand. Never writes or forges signatures.
     */
    static int inspectChefSignature(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.playerRequired"), false);
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        CookingSignature signature = held.get(CookingSignatureComponents.type());
        if (signature != null) {
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.dish", held.getHoverName()), false);
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.chef", signature.chefName()), false);
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.valid"), false);
            return 1;
        }
        if (com.tanrunn.tcth.impl.classifier.DishClassifier.isDish(held)) {
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.unsigned"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.tcth.chef.inspect.notDish"), false);
        }
        return 1;
    }

    private static int enableCookingDebug(CommandContext<CommandSourceStack> ctx) {
        CookingDebug.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] cooking debug enabled"), false);
        return 1;
    }

    private static int disableCookingDebug(CommandContext<CommandSourceStack> ctx) {
        CookingDebug.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] cooking debug disabled"), false);
        return 1;
    }

    private static int cookingDebugStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = CookingDebug.isEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] cooking debug is " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }
}
