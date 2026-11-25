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
import ink.ziip.ohmytransfer.ticket.TransferTicketService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * {@code /omt <server>}, {@code /omt <player> <server>} and {@code /omt reload}.
 */
public final class OmtCommand {

    public static final String ADMIN_PERMISSION = "omt.use";
    public static final String SELF_PERMISSION = "omt.self";
    static final String NAME = "omt";
    static final String ALIAS = "server";
    static final String DESCRIPTION = "Transfer a player to another server with a signed ticket";
    static final String RELOAD = "reload";
    private final OhMyTransferPlugin plugin;
    private final TransferTicketService tickets;

    public OmtCommand(OhMyTransferPlugin plugin, TransferTicketService tickets) {
        this.plugin = plugin;
        this.tickets = tickets;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .requires(source -> canAdmin(source) || canSelf(source))
                .then(Commands.literal(RELOAD)
                        .requires(OmtCommand::canAdmin)
                        .executes(ctx -> reload(ctx.getSource().getSender())))
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
        return canAdmin(source.getSender());
    }

    private static boolean canSelf(CommandSourceStack source) {
        return canSelf(source.getSender(), source.getExecutor() instanceof Player);
    }

    static boolean canAdmin(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION);
    }

    static boolean canSelf(CommandSender sender, boolean playerExecutor) {
        return playerExecutor && sender.hasPermission(SELF_PERMISSION);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> serverArgument() {
        return Commands.argument("server", StringArgumentType.word()).suggests(this::suggestServers);
    }

    private CompletableFuture<Suggestions> suggestServers(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        serverSuggestions(builder.getRemainingLowerCase()).forEach(builder::suggest);
        return builder.buildFuture();
    }

    List<String> serverSuggestions(String prefix) {
        Settings settings = plugin.settings();
        String remaining = prefix.toLowerCase(Locale.ROOT);
        return settings.servers().keySet().stream()
                .filter(name -> !name.equals(settings.serverId()) && name.toLowerCase(Locale.ROOT).startsWith(remaining))
                .toList();
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

    int transfer(CommandSender sender, Player player, String serverName) {
        Settings.Target target;
        try {
            target = tickets.writeTicket(plugin.settings(), player, serverName);
        } catch (TransferTicketService.Failure error) {
            sender.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
            if (error.getCause() != null) {
                plugin.getLogger().log(Level.WARNING, "Could not write transfer ticket for " + player.getName(),
                        error.getCause());
            }
            return 0;
        }
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

    int reload(CommandSender sender) {
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
