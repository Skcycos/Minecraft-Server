package com.tanrunn.tcth.impl.stats;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tcth chef stats [player]} — shows a player's cooking statistics.
 *
 * <p>Players may query themselves; viewing others requires permission level
 * &ge; 3. No reset command is provided (avoid accidental data loss).
 */
public final class CookingStatsCommand {

    public static final int PERMISSION_LEVEL = 3;

    private CookingStatsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("chef")
                        .then(Commands.literal("stats")
                                .executes(CookingStatsCommand::querySelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                                        .executes(CookingStatsCommand::queryOther)))));
    }

    private static int querySelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("[TCTH] This command must be run by a player"));
            return 0;
        }
        CookingStatsData data = CookingStatsData.current(src.getLevel());
        PlayerCookingStats stats = data.get(player.getUUID());
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 还没有任何厨艺记录"), false);
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
        CookingStatsData data = CookingStatsData.current(src.getLevel());
        PlayerCookingStats stats = data.get(uuid);
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 该玩家还没有任何厨艺记录"), false);
            return 1;
        }
        src.sendSuccess(() -> format(stats), false);
        return 1;
    }

    static Component format(PlayerCookingStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TCTH] 厨艺档案\n");
        sb.append("  出锅次数: ").append(stats.getTotalCookingEvents())
                .append(" | 料理份数: ").append(stats.getTotalDishesCooked())
                .append(" | 不同料理: ").append(stats.getUniqueDishCount()).append('\n');

        Map.Entry<CookingDevice, Integer> topDevice = stats.getDeviceCounts().entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        sb.append("  最常用设备: ").append(topDevice != null ? topDevice.getKey().name() : "-")
                .append(" (").append(topDevice != null ? topDevice.getValue() : 0).append(" 次)\n");

        sb.append("  等级: ");
        for (DishTier tier : DishTier.values()) {
            Integer c = stats.getTierCounts().get(tier);
            if (c != null && c > 0) {
                sb.append(tier.name()).append('=').append(c).append(' ');
            }
        }
        sb.append('\n');

        sb.append("  品质: ");
        for (DishQuality quality : DishQuality.values()) {
            Integer c = stats.getQualityCounts().get(quality);
            if (c != null && c > 0) {
                sb.append(quality.name()).append('=').append(c).append(' ');
            }
        }
        sb.append('\n');

        sb.append("  最近制作: ").append(stats.getLastDish().isEmpty() ? "-" : stats.getLastDish())
                .append(" (").append(stats.getLastDevice())
                .append(", ").append(stats.getLastTier().isEmpty() ? "未分级" : stats.getLastTier())
                .append(", ").append(stats.getLastQuality()).append(')');
        return Component.literal(sb.toString());
    }
}
