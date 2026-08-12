package com.tanrunn.tcth.impl.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.tanrunn.tcth.impl.debug.ShadowDebug;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Unit tests for the {@code /tcth debug shadow on|off|status} command
 * (phase 8C.0).
 *
 * <p>Asserts the debug switch flips and the command dispatches without
 * throwing; the switch itself only gates the coordinator's INFO lines.
 */
class TcthShadowDebugCommandTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        ShadowDebug.setEnabled(false);
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        HolderLookup.Provider provider = HolderLookup.Provider.create(Stream.empty());
        TcthCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher,
                Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(provider, FeatureFlagSet.of())));
        return dispatcher;
    }

    private CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(3)).thenReturn(true);
        return source;
    }

    @Test
    void shadowDebugTogglesViaCommand() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        assertFalse(ShadowDebug.isEnabled(), "the switch must default to off");

        dispatcher.execute("tcth debug shadow on", source);
        assertTrue(ShadowDebug.isEnabled());

        dispatcher.execute("tcth debug shadow status", source);
        assertTrue(ShadowDebug.isEnabled());

        dispatcher.execute("tcth debug shadow off", source);
        assertFalse(ShadowDebug.isEnabled());
    }

    @Test
    void shadowDebugRequiresPermission() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack noPerm = mock(CommandSourceStack.class);
        when(noPerm.hasPermission(3)).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("tcth debug shadow status", noPerm));
        assertFalse(ShadowDebug.isEnabled(), "a player without permission must not flip the switch");
    }
}
