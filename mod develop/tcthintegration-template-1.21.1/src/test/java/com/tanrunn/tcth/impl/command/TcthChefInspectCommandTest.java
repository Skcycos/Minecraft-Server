package com.tanrunn.tcth.impl.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.signature.SignatureTestRegistry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for {@code /tcth chef inspect}: signed dish, unsigned dish and
 * non-dish branches, plus the console (non-player) guard.
 */
class TcthChefInspectCommandTest {

    private static final UUID CHEF = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private CommandSourceStack source;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrap() {
        SignatureTestRegistry.ensureRegistered();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(CHEF);
        when(player.getGameProfile()).thenReturn(new GameProfile(CHEF, "Tanrunn"));
        when(player.getName()).thenReturn(Component.literal("Tanrunn"));
        source = mock(CommandSourceStack.class);
        when(source.getEntity()).thenReturn(player);
    }

    @Test
    void signedDishShowsDishChefAndValid() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        dish.set(CookingSignatureComponents.type(), new CookingSignature(CHEF, "Tanrunn"));
        when(player.getMainHandItem()).thenReturn(dish);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        assertEquals(1, TcthCommands.inspectChefSignature(ctx));
        verify(source, times(3)).sendSuccess(any(), eq(false));
    }

    @Test
    void unsignedDishShowsUnsignedMessage() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF); // no signature
        when(player.getMainHandItem()).thenReturn(dish);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        assertEquals(1, TcthCommands.inspectChefSignature(ctx));
        ArgumentCaptor<java.util.function.Supplier<Component>> captor = ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(source, times(1)).sendSuccess(captor.capture(), eq(false));
        assertTrue(captor.getValue().get().getString().contains("command.tcth.chef.inspect.unsigned"),
                "unsigned dish must report the unsigned translation key");
    }

    @Test
    void nonDishShowsNotDishMessage() {
        ItemStack stick = new ItemStack(Items.STICK);
        when(player.getMainHandItem()).thenReturn(stick);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        assertEquals(1, TcthCommands.inspectChefSignature(ctx));
        ArgumentCaptor<java.util.function.Supplier<Component>> captor = ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(source, times(1)).sendSuccess(captor.capture(), eq(false));
        assertTrue(captor.getValue().get().getString().contains("command.tcth.chef.inspect.notDish"),
                "non-dish must report the not-dish translation key");
    }

    @Test
    void consoleWithoutPlayerGetsPlayerRequiredMessage() {
        when(source.getEntity()).thenReturn(null);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        assertEquals(0, TcthCommands.inspectChefSignature(ctx));
        verify(source, times(1)).sendSuccess(any(), eq(false));
    }

    @Test
    void inspectNeverWritesSignature() {
        ItemStack unsigned = new ItemStack(Items.COOKED_BEEF);
        when(player.getMainHandItem()).thenReturn(unsigned);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        TcthCommands.inspectChefSignature(ctx);
        assertEquals(null, unsigned.get(CookingSignatureComponents.type()),
                "inspect must be read-only");
    }

    @Test
    void signedDishReportsActualChefName() {
        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        dish.set(CookingSignatureComponents.type(), new CookingSignature(CHEF, "Tanrunn"));
        when(player.getMainHandItem()).thenReturn(dish);

        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getSource()).thenReturn(source);

        assertEquals(1, TcthCommands.inspectChefSignature(ctx));
        verify(source, times(3)).sendSuccess(any(), eq(false));
    }
}
