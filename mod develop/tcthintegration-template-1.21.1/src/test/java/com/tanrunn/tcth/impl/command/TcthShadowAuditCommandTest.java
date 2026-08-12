package com.tanrunn.tcth.impl.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tanrunn.tcth.api.shadow.ShadowTargetKind;
import com.tanrunn.tcth.api.shadow.ShadowTheftOutcome;
import com.tanrunn.tcth.api.shadow.ShadowTheftType;
import com.tanrunn.tcth.impl.shadow.ShadowAuditRecord;
import com.tanrunn.tcth.impl.shadow.ShadowAuditState;
import com.tanrunn.tcth.impl.shadow.ShadowAuditStore;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Unit tests for the read-only shadow audit commands (8C.2 §6).
 *
 * <p>Covers permission gating, self-only queries for ordinary players, the
 * strict limit (never the full 10 000) and the translatable-free read-only
 * output.
 */
class TcthShadowAuditCommandTest {

    private static final ResourceLocation DIM =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        HolderLookup.Provider provider = HolderLookup.Provider.create(Stream.empty());
        TcthCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher,
                Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(provider, FeatureFlagSet.of())));
        return dispatcher;
    }

    private CommandSourceStack source(boolean permission, ServerPlayer executor) {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getServer()).thenReturn(server);
        when(overworld.getDataStorage()).thenReturn(storage);
        ShadowAuditStore store = new ShadowAuditStore();
        UUID thief = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        store.append(new ShadowAuditRecord(UUID.randomUUID(), thief, target,
                ShadowTargetKind.PLAYER, null, ShadowTheftType.ITEM, ShadowTheftOutcome.SUCCESS,
                ShadowAuditState.FINAL,
                ResourceLocation.fromNamespaceAndPath("minecraft", "diamond"), 1, 0.0d, null, 0,
                1_000_000L, 5_000L, DIM, new BlockPos(10, 20, 30), null));
        when(storage.computeIfAbsent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(store);
        when(server.getPlayerList()).thenReturn(mock(net.minecraft.server.players.PlayerList.class));

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(3)).thenReturn(permission);
        when(source.getServer()).thenReturn(server);
        if (executor != null) {
            when(source.getEntity()).thenReturn(executor);
        }
        return source;
    }

    @Test
    void recentRequiresPermission() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack noPerm = source(false, null);
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("tcth shadow audit recent 5", noPerm));
        CommandSourceStack withPerm = source(true, null);
        dispatcher.execute("tcth shadow audit recent 5", withPerm); // no throw
    }

    @Test
    void playerQueryWithoutPermissionOnlyAllowsSelf() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        ServerPlayer self = mock(ServerPlayer.class);
        when(self.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(
                UUID.randomUUID(), "SneakyPete"));
        CommandSourceStack noPermSelf = source(false, self);
        // Querying someone else without permission is refused with a failure
        // message (not an exception — the executor-level check).
        dispatcher.execute("tcth shadow audit player SomeoneElse 5", noPermSelf);
        org.mockito.Mockito.verify(noPermSelf).sendFailure(org.mockito.ArgumentMatchers.any());
        // Querying SELF without permission succeeds (own records only).
        dispatcher.execute("tcth shadow audit player SneakyPete 5", noPermSelf);
    }

    @Test
    void limitIsStrictlyBounded() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack withPerm = source(true, null);
        dispatcher.execute("tcth shadow audit recent 100", withPerm); // cap accepted
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("tcth shadow audit recent 10000", withPerm),
                "limits above the hard cap must be rejected");
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("tcth shadow audit recent 0", withPerm),
                "a zero limit must be rejected");
    }

    @Test
    void noResetOrDeleteCommandsExist() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        var auditNode = dispatcher.getRoot().getChild("tcth").getChild("shadow").getChild("audit");
        assertTrue(auditNode.getChildren().size() == 2
                        && auditNode.getChild("recent") != null
                        && auditNode.getChild("player") != null,
                "only the read-only recent/player subcommands may exist");
    }
}
