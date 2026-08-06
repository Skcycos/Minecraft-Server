package com.tanrunn.tcth.impl.stats;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tcth gunner stats [player]} — shows a player's gunner statistics.
 *
 * <p>Players may query themselves; viewing others requires permission level
 * &ge; 3. No reset command is provided (avoid accidental data loss). Read-only.
 */
public final class GunnerStatsCommand {

    public static final int PERMISSION_LEVEL = 3;

    private GunnerStatsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("gunner")
                        .then(Commands.literal("stats")
                                .executes(GunnerStatsCommand::querySelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                                        .executes(GunnerStatsCommand::queryOther)))));
    }

    private static int querySelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("[TCTH] This command must be run by a player"));
            return 0;
        }
        GunnerStatsData data = GunnerStatsData.current(src.getLevel());
        PlayerGunnerStats stats = data.get(player.getUUID());
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 还没有任何枪客记录"), false);
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
        GunnerStatsData data = GunnerStatsData.current(src.getLevel());
        PlayerGunnerStats stats = data.get(uuid);
        if (stats == null) {
            src.sendSuccess(() -> Component.literal("[TCTH] 该玩家还没有任何枪客记录"), false);
            return 1;
        }
        src.sendSuccess(() -> format(stats), false);
        return 1;
    }

    static Component format(PlayerGunnerStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TCTH] 枪客档案");
        sb.append('\n');
        sb.append("  总枪械击杀: ").append(stats.getTotalGunKills());
        sb.append('\n');
        sb.append("  分级: COMMON=").append(stats.getCommonKills())
                .append(" ELITE=").append(stats.getEliteKills())
                .append(" HEAVY=").append(stats.getHeavyKills())
                .append(" BOSS=").append(stats.getBossKills());
        sb.append('\n');
        sb.append("  最常用枪械: ").append(stats.getMostUsedWeapon().isEmpty() ? "-" : stats.getMostUsedWeapon())
                .append(" | 不同枪械: ").append(stats.getUniqueWeapons());
        sb.append('\n');
        sb.append("  最大击杀距离: ").append(String.format(Locale.ROOT, "%.1f", stats.getMaxDistance())).append(" 格");
        sb.append('\n');
        sb.append("  最近击杀: ").append(stats.getLastTarget().isEmpty() ? "-" : stats.getLastTarget())
                .append(" (").append(stats.getLastTier().isEmpty() ? "未分级" : stats.getLastTier())
                .append(", ").append(stats.getLastWeapon().isEmpty() ? "-" : stats.getLastWeapon()).append(')');
        return Component.literal(sb.toString());
    }
}
