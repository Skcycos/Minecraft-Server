package com.tanrunn.tcth.impl.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.impl.debug.BrewingDebug;
import com.tanrunn.tcth.impl.debug.CookingDebug;
import com.tanrunn.tcth.impl.debug.FarmingDebug;
import com.tanrunn.tcth.impl.debug.GunDebug;
import com.tanrunn.tcth.impl.debug.ShadowDebug;
import com.tanrunn.tcth.impl.shadow.ShadowAuditRecord;
import com.tanrunn.tcth.impl.shadow.ShadowAuditStore;
import com.tanrunn.tcth.impl.signature.CookingSignature;
import com.tanrunn.tcth.impl.signature.CookingSignatureComponents;
import com.tanrunn.tcth.impl.stats.BrewingStatsCommand;
import com.tanrunn.tcth.impl.stats.CookingStatsCommand;
import com.tanrunn.tcth.impl.stats.GunnerStatsCommand;

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
        GunnerStatsCommand.register(dispatcher);
        BrewingStatsCommand.register(dispatcher);

        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cooking = Commands.literal("cooking")
                .then(Commands.literal("on").executes(TcthCommands::enableCookingDebug))
                .then(Commands.literal("off").executes(TcthCommands::disableCookingDebug))
                .then(Commands.literal("status").executes(TcthCommands::cookingDebugStatus));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> farming = Commands.literal("farming")
                .then(Commands.literal("on").executes(TcthCommands::enableFarmingDebug))
                .then(Commands.literal("off").executes(TcthCommands::disableFarmingDebug))
                .then(Commands.literal("status").executes(TcthCommands::farmingDebugStatus));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gunner = Commands.literal("gunner")
                .then(Commands.literal("on").executes(TcthCommands::enableGunnerDebug))
                .then(Commands.literal("off").executes(TcthCommands::disableGunnerDebug))
                .then(Commands.literal("status").executes(TcthCommands::gunnerDebugStatus));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> brewing = Commands.literal("brewing")
                .then(Commands.literal("on").executes(TcthCommands::enableBrewingDebug))
                .then(Commands.literal("off").executes(TcthCommands::disableBrewingDebug))
                .then(Commands.literal("status").executes(TcthCommands::brewingDebugStatus));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> shadowDebug = Commands.literal("shadow")
                .then(Commands.literal("on").executes(TcthCommands::enableShadowDebug))
                .then(Commands.literal("off").executes(TcthCommands::disableShadowDebug))
                .then(Commands.literal("status").executes(TcthCommands::shadowDebugStatus));

        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> auditRecent =
                Commands.literal("recent")
                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                        .executes(ctx -> auditRecent(ctx, 20))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> auditRecent(ctx,
                                        IntegerArgumentType.getInteger(ctx, "limit"))));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> auditPlayer =
                Commands.literal("player")
                        .then(Commands.argument("player",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> auditPlayer(ctx, 20))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> auditPlayer(ctx,
                                                IntegerArgumentType.getInteger(ctx, "limit")))));
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> shadowAudit =
                Commands.literal("audit").then(auditRecent).then(auditPlayer);

        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("chef")
                        .then(Commands.literal("inspect").executes(TcthCommands::inspectChefSignature)))
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                        .then(cooking)
                        .then(farming)
                        .then(gunner)
                        .then(brewing)
                        .then(shadowDebug))
                .then(Commands.literal("shadow").then(shadowAudit)));
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

    private static int enableFarmingDebug(CommandContext<CommandSourceStack> ctx) {
        FarmingDebug.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] farming debug enabled"), false);
        return 1;
    }

    private static int disableFarmingDebug(CommandContext<CommandSourceStack> ctx) {
        FarmingDebug.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] farming debug disabled"), false);
        return 1;
    }

    private static int farmingDebugStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = FarmingDebug.isEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] farming debug is " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }

    private static int enableGunnerDebug(CommandContext<CommandSourceStack> ctx) {
        GunDebug.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] gunner debug enabled"), false);
        return 1;
    }

    private static int disableGunnerDebug(CommandContext<CommandSourceStack> ctx) {
        GunDebug.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] gunner debug disabled"), false);
        return 1;
    }

    private static int gunnerDebugStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = GunDebug.isEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] gunner debug is " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }

    /**
     * {@code /tcth shadow audit recent [limit]} — permission &ge; 3.
     * Prints the newest records (strictly limited). Read-only.
     */
    private static int auditRecent(CommandContext<CommandSourceStack> ctx, int limit)
            throws CommandSyntaxException {
        ShadowAuditStore store = auditStore(ctx);
        var all = store.all();
        int from = Math.max(0, all.size() - limit);
        for (int i = from; i < all.size(); i++) {
            final ShadowAuditRecord record = all.get(i);
            ctx.getSource().sendSuccess(() -> formatAuditLine(ctx.getSource(), record), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] shown " + Math.min(limit, all.size())
                + " of " + all.size() + " records"), false);
        return 1;
    }

    /**
     * {@code /tcth shadow audit player <player> [limit]} — permission &ge; 3
     * for other players; an ordinary player may only query their own records.
     * Read-only.
     */
    private static int auditPlayer(CommandContext<CommandSourceStack> ctx, int limit)
            throws CommandSyntaxException {
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        if (!source.hasPermission(PERMISSION_LEVEL)) {
            if (!(source.getEntity() instanceof ServerPlayer self)
                    || !self.getGameProfile().getName().equalsIgnoreCase(name)) {
                source.sendFailure(Component.literal("[TCTH] permission required to query other players"));
                return 0;
            }
        }
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            source.sendFailure(Component.literal("[TCTH] player '" + name + "' is not online"));
            return 0;
        }
        ShadowAuditStore store = auditStore(ctx);
        java.util.UUID id = target.getUUID();
        var records = new java.util.ArrayList<ShadowAuditRecord>();
        for (ShadowAuditRecord r : store.byThief(id)) {
            records.add(r);
        }
        for (ShadowAuditRecord r : store.byTarget(id)) {
            if (!records.contains(r)) {
                records.add(r);
            }
        }
        records.sort((a, b) -> Long.compare(b.timestampEpochMillis(), a.timestampEpochMillis()));
        int shown = Math.min(limit, records.size());
        for (int i = 0; i < shown; i++) {
            final ShadowAuditRecord record = records.get(i);
            ctx.getSource().sendSuccess(() -> formatAuditLine(ctx.getSource(), record), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] shown " + shown + " of "
                + records.size() + " records for " + name), false);
        return 1;
    }

    private static ShadowAuditStore auditStore(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return ShadowAuditStore.current(ctx.getSource().getServer().overworld());
    }

    /** Formats one read-only audit line: eventId, time, both sides, type,
     *  outcome, item/amount, dimension and position. */
    private static Component formatAuditLine(CommandSourceStack source, ShadowAuditRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("[event ").append(shortId(r.eventId())).append("] ");
        sb.append("t=").append(r.timestampEpochMillis()).append(" ");
        sb.append("thief=").append(nameOf(source, r.thiefId())).append("/").append(shortId(r.thiefId())).append(" ");
        sb.append("target=").append(nameOf(source, r.targetId())).append("/").append(shortId(r.targetId())).append(" ");
        sb.append("kind=").append(r.targetKind()).append(" ");
        if (r.theftType() != null) {
            sb.append("type=").append(r.theftType()).append(" ");
        }
        sb.append("outcome=").append(r.outcome() != null ? r.outcome() : "PENDING").append(" ");
        if (r.itemId() != null) {
            sb.append("item=").append(r.itemId()).append("x").append(r.itemCount()).append(" ");
        }
        if (r.numericAmount() > 0.0d) {
            sb.append("amount=").append(r.numericAmount()).append(" ");
        }
        if (r.effectId() != null) {
            sb.append("effect=").append(r.effectId()).append("x").append(r.effectDurationTicks()).append(" ");
        }
        sb.append("dim=").append(r.dimension()).append(" ");
        if (r.position() != null) {
            sb.append("pos=").append(r.position().getX()).append(",").append(r.position().getY())
                    .append(",").append(r.position().getZ());
        }
        return Component.literal(sb.toString());
    }

    private static String nameOf(CommandSourceStack source, java.util.UUID id) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(id);
        return online != null ? online.getGameProfile().getName() : "?";
    }

    private static String shortId(java.util.UUID id) {
        String s = id.toString();
        return s.substring(0, 8);
    }

    private static int enableShadowDebug(CommandContext<CommandSourceStack> ctx) {
        ShadowDebug.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] shadow debug enabled"), false);
        return 1;
    }

    private static int disableShadowDebug(CommandContext<CommandSourceStack> ctx) {
        ShadowDebug.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] shadow debug disabled"), false);
        return 1;
    }

    private static int shadowDebugStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = ShadowDebug.isEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] shadow debug is " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }

    private static int enableBrewingDebug(CommandContext<CommandSourceStack> ctx) {
        BrewingDebug.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] brewing debug enabled"), false);
        return 1;
    }

    private static int disableBrewingDebug(CommandContext<CommandSourceStack> ctx) {
        BrewingDebug.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] brewing debug disabled"), false);
        return 1;
    }

    private static int brewingDebugStatus(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BrewingDebug.isEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("[TCTH] brewing debug is " + (enabled ? "enabled" : "disabled")), false);
        return 1;
    }
}
