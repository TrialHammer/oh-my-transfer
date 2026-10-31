package ink.ziip.ohmytransfer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import ink.ziip.ohmytransfer.config.Settings;
import ink.ziip.ohmytransfer.ticket.TicketCodec;
import ink.ziip.ohmytransfer.ticket.TransferTicket;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /omt <server>}, {@code /omt <player> <server>} and {@code /omt reload}.
 */
public final class OmtCommand {

    public static final String ADMIN_PERMISSION = "omt.use";
    public static final String SELF_PERMISSION = "omt.self";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OhMyTransferPlugin plugin;

    public OmtCommand(OhMyTransferPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("omt")
                .requires(source -> canAdmin(source) || canSelf(source))
                .then(Commands.literal("reload")
                        .requires(OmtCommand::canAdmin)
                        .executes(this::reload))
                // Must be registered before <player>: "/omt survival" also parses as an incomplete
                // "/omt <player>", and Brigadier picks the first of two equally complete parses.
                .then(serverArgument()
                        .requires(OmtCommand::canSelf)
                        .executes(this::transferSelf))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(OmtCommand::canAdmin)
                        .then(serverArgument().executes(this::transferOther)))
                .build();
    }

    private static boolean canAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission(ADMIN_PERMISSION);
    }

    private static boolean canSelf(CommandSourceStack source) {
        return source.getExecutor() instanceof Player && source.getSender().hasPermission(SELF_PERMISSION);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> serverArgument() {
        return Commands.argument("server", StringArgumentType.word()).suggests(this::suggestServers);
    }

    private CompletableFuture<Suggestions> suggestServers(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        Settings settings = plugin.settings();
        String remaining = builder.getRemainingLowerCase();
        for (String name : settings.servers().keySet()) {
            if (!name.equals(settings.serverId()) && name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private int transferSelf(CommandContext<CommandSourceStack> ctx) {
        // canSelf() guarantees the executor is a player.
        Player player = (Player) ctx.getSource().getExecutor();
        return transfer(ctx.getSource().getSender(), player, StringArgumentType.getString(ctx, "server"));
    }

    private int transferOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getArgument("player", PlayerSelectorArgumentResolver.class)
                .resolve(ctx.getSource()).getFirst();
        return transfer(ctx.getSource().getSender(), player, StringArgumentType.getString(ctx, "server"));
    }

    private int transfer(CommandSender sender, Player player, String serverName) {
        Settings settings = plugin.settings();
        Settings.Target target = settings.servers().get(serverName);
        if (target == null) {
            sender.sendMessage(Component.text("未知服务器: " + serverName, NamedTextColor.RED));
            return 0;
        }
        if (serverName.equals(settings.serverId())) {
            sender.sendMessage(Component.text(serverName + " 就是当前服务器", NamedTextColor.RED));
            return 0;
        }

        long now = System.currentTimeMillis();
        byte[] nonce = new byte[TransferTicket.NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        TransferTicket ticket = new TransferTicket(settings.serverId(), serverName, player.getUniqueId(),
                player.getName(), now, now + settings.ticketTtlMillis(), nonce);

        // Both packets go out on the same connection in order, so the cookie is stored before the client leaves.
        player.storeCookie(settings.cookieKey(), TicketCodec.encode(ticket, settings.secret()));
        player.transfer(target.host(), target.port());

        plugin.getLogger().info(sender.getName() + " transferred " + player.getName() + " to "
                + serverName + " (" + target.host() + ":" + target.port() + ")");
        if (sender.equals(player)) {
            sender.sendMessage(Component.text("正在传送到 " + serverName, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("已将 " + player.getName() + " 传送到 " + serverName,
                    NamedTextColor.GREEN));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            plugin.reloadSettings();
            sender.sendMessage(Component.text("OhMyTransfer 配置已重载", NamedTextColor.GREEN));
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("配置无效，仍使用旧配置: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
    }
}
