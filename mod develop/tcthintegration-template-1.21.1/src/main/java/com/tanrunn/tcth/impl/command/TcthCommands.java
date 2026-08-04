package com.tanrunn.tcth.impl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.debug.CookingDebug;
import com.tanrunn.tcth.impl.stats.CookingStatsCommand;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * TCTH admin commands.
 *
 * <p>Currently exposes the cooking debug toggle:
 * {@code /tcth debug cooking on|off|status} (permission level &ge; 3).
 * All state is in-memory only.
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
