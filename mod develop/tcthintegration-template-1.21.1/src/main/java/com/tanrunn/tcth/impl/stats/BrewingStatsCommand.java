package com.tanrunn.tcth.impl.stats;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.tanrunn.tcth.api.brewing.BeverageDevice;
import com.tanrunn.tcth.api.brewing.BeverageTier;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tcth brewer stats [player]} — shows a player's brewing statistics
 * (phase 7D).
 *
 * <p>Players may query themselves; viewing others requires permission level
 * &ge; 3. Read-only: no reset command is provided (avoid accidental data loss).
 */
public final class BrewingStatsCommand {

    public static final int PERMISSION_LEVEL = 3;

    private BrewingStatsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("brewer")
                        .then(Commands.literal("stats")
                                .executes(BrewingStatsCommand::querySelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                                        .executes(BrewingStatsCommand::queryOther)))));
    }

    private static int querySelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("[TCTH] This command must be run by a player"));
            return 0;
        }
        BrewingStatsData data = BrewingStatsData.current(src.getLevel());
        PlayerBrewingStats stats = data.get(player.getUUID());
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 还没有任何调饮记录"), false);
            return 1;
        }
        src.sendSuccess(() -> format(stats), false);
        return 1;
    }

    private static int queryOther(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            src.sendFailure(Component.literal("[TCTH] 请指定一个玩家"));
            return 0;
        }
        UUID uuid = profiles.iterator().next().getId();
        BrewingStatsData data = BrewingStatsData.current(src.getLevel());
        PlayerBrewingStats stats = data.get(uuid);
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 该玩家还没有任何调饮记录"), false);
            return 1;
        }
        src.sendSuccess(() -> format(stats), false);
        return 1;
    }

    static Component format(PlayerBrewingStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TCTH] 魔酿师档案\n");
        sb.append("  调饮次数: ").append(stats.getTotalBrewingEvents())
                .append(" | 饮品份数: ").append(stats.getTotalBeveragesPrepared())
                .append(" | 不同饮品: ").append(stats.getUniqueBeverageCount()).append('\n');

        Map.Entry<BeverageDevice, Integer> topDevice = stats.getDeviceCounts().entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        sb.append("  最常用设备: ").append(topDevice != null ? topDevice.getKey().name() : "-")
                .append(" (").append(topDevice != null ? topDevice.getValue() : 0).append(" 次)\n");

        sb.append("  档次分布: ");
        for (BeverageTier tier : BeverageTier.values()) {
            Integer c = stats.getTierCounts().get(tier);
            if (c != null && c > 0) {
                sb.append(tier.name()).append('=').append(c).append(' ');
            }
        }
        sb.append('\n');

        sb.append("  最常调制: ").append(stats.getMostPreparedBeverage().isEmpty() ? "-" : stats.getMostPreparedBeverage())
                .append(" (").append(stats.getMostPreparedBeverageCount()).append(" 份)\n");

        sb.append("  最近调制: ").append(stats.getLastBeverage().isEmpty() ? "-" : stats.getLastBeverage())
                .append(" (").append(stats.getLastDevice())
                .append(", ").append(stats.getLastTier().isEmpty() ? "未分级" : stats.getLastTier()).append(')');
        return Component.literal(sb.toString());
    }
}
