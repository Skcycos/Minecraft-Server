package com.tanrunn.tcth.impl.stats;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gunner read-only commands (phase 5A / 5C).
 *
 * <ul>
 *   <li>{@code /tcth gunner stats [player]} — compact stats (5A compatible)</li>
 *   <li>{@code /tcth gunner profile [player]} — full battlefield profile (5C)</li>
 * </ul>
 *
 * <p>Players may query themselves; viewing others requires permission level
 * &ge; 3. Console without a player argument is rejected with a clear message.
 * No reset command (avoid accidental data loss).
 */
public final class GunnerStatsCommand {

    public static final int PERMISSION_LEVEL = 3;
    private static final int PROFILE_TOP_WEAPONS = 3;

    private GunnerStatsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tcth")
                .then(Commands.literal("gunner")
                        .then(Commands.literal("stats")
                                .executes(GunnerStatsCommand::statsSelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                                        .executes(GunnerStatsCommand::statsOther)))
                        .then(Commands.literal("profile")
                                .executes(GunnerStatsCommand::profileSelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(PERMISSION_LEVEL))
                                        .executes(GunnerStatsCommand::profileOther)))));
    }

    // ---- stats (5A compact) ----

    private static int statsSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("[TCTH] This command must be run by a player"));
            return 0;
        }
        return sendStats(src, player.getUUID(), null);
    }

    private static int statsOther(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return sendForProfileArg(ctx, false);
    }

    // ---- profile (5C full) ----

    private static int profileSelf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(
                    "[TCTH] /tcth gunner profile must be run by a player, or specify a player name"));
            return 0;
        }
        return sendProfile(src, player.getUUID(), player.getGameProfile().getName());
    }

    private static int profileOther(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return sendForProfileArg(ctx, true);
    }

    private static int sendForProfileArg(CommandContext<CommandSourceStack> ctx, boolean fullProfile)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            src.sendFailure(Component.literal("[TCTH] 请指定一个玩家"));
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        UUID uuid = profile.getId();
        String name = profile.getName();
        return fullProfile ? sendProfile(src, uuid, name) : sendStats(src, uuid, name);
    }

    private static int sendStats(CommandSourceStack src, UUID uuid, String ignoredName) {
        GunnerStatsData data = GunnerStatsData.current(src.getLevel());
        PlayerGunnerStats stats = data.get(uuid);
        if (stats == null) {
            src.sendSuccess(() -> Component.literal(
                    ignoredName == null ? "[TCTH] 还没有任何枪客记录" : "[TCTH] 该玩家还没有任何枪客记录"), false);
            return 1;
        }
        src.sendSuccess(() -> formatStats(stats), false);
        return 1;
    }

    private static int sendProfile(CommandSourceStack src, UUID uuid, String displayName) {
        GunnerStatsData data = GunnerStatsData.current(src.getLevel());
        PlayerGunnerStats stats = data.get(uuid);
        if (stats == null) {
            src.sendSuccess(() -> Component.literal(
                    "[TCTH] " + (displayName != null ? displayName : "该玩家") + " 还没有任何枪客记录"), false);
            return 1;
        }
        String name = displayName != null && !displayName.isEmpty() ? displayName : uuid.toString();
        src.sendSuccess(() -> formatProfile(name, stats), false);
        return 1;
    }

    /** Compact 5A-compatible stats output (stable text for tests). */
    static Component format(PlayerGunnerStats stats) {
        return formatStats(stats);
    }

    static Component formatStats(PlayerGunnerStats stats) {
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

    /**
     * Full battlefield profile (phase 5C / 5C.1).
     *
     * <p>All player-facing labels use {@link Component#translatable}; the server
     * never reads {@code player.getLanguage()} or embeds zh/en literals.
     */
    static Component formatProfile(String playerName, PlayerGunnerStats stats) {
        String name = (playerName == null || playerName.isEmpty())
                ? "" // empty → client shows unknown key arg; use explicit key below
                : playerName;
        MutableComponent root = Component.empty();
        root.append(Component.translatable("tcth.gunner.profile.title")).append("\n");
        if (name.isEmpty()) {
            root.append(Component.translatable("tcth.gunner.profile.player",
                    Component.translatable("tcth.gunner.profile.unknown_player"))).append("\n");
        } else {
            root.append(Component.translatable("tcth.gunner.profile.player", name)).append("\n");
        }
        root.append(Component.translatable("tcth.gunner.profile.confirmed_kills", stats.getTotalGunKills()))
                .append("\n");
        root.append(Component.translatable("tcth.gunner.profile.unique_weapons", stats.getUniqueWeapons()))
                .append("\n");
        String main = stats.getMostUsedWeapon();
        if (main.isEmpty()) {
            root.append(Component.translatable("tcth.gunner.profile.main_weapon_none")).append("\n");
        } else {
            root.append(Component.translatable(
                    "tcth.gunner.profile.main_weapon", main, stats.getMostUsedWeaponKills())).append("\n");
        }
        root.append(Component.translatable(
                "tcth.gunner.profile.longest_kill",
                String.format(Locale.ROOT, "%.1f", stats.getMaxDistance()))).append("\n");
        root.append(Component.translatable(
                "tcth.gunner.profile.tier_distribution",
                stats.getCommonKills(),
                stats.getEliteKills(),
                stats.getHeavyKills(),
                stats.getBossKills())).append("\n");
        List<Map.Entry<String, Integer>> top = stats.getTopWeapons(PROFILE_TOP_WEAPONS);
        if (!top.isEmpty()) {
            root.append(Component.translatable("tcth.gunner.profile.top_weapons_header")).append("\n");
            int rank = 1;
            for (Map.Entry<String, Integer> e : top) {
                root.append(Component.translatable(
                        "tcth.gunner.profile.top_weapon_line",
                        rank++,
                        e.getKey(),
                        e.getValue())).append("\n");
            }
        }
        List<GunnerMedal> medals = stats.getUnlockedMedalsInOrder();
        if (medals.isEmpty()) {
            root.append(Component.translatable("tcth.gunner.profile.medals_none"));
        } else {
            root.append(Component.translatable(
                    "tcth.gunner.profile.medals",
                    GunnerMedalEvaluator.joinMedalNames(medals)));
        }
        return root;
    }
}
