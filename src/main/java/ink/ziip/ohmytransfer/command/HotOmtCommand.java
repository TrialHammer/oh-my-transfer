package ink.ziip.ohmytransfer.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Command-map adapter used until Paper next runs the Brigadier registration lifecycle. */
final class HotOmtCommand extends Command implements PluginIdentifiableCommand {

    private static final String SELECTOR_PERMISSION = "minecraft.command.selector";
    private final Plugin owner;
    private final OmtCommand command;

    HotOmtCommand(Plugin owner, OmtCommand command, boolean claimAlias) {
        super(OmtCommand.NAME, OmtCommand.DESCRIPTION, "/omt", claimAlias ? List.of(OmtCommand.ALIAS) : List.of());
        this.owner = owner;
        this.command = command;
    }

    @Override
    public Plugin getPlugin() {
        return owner;
    }

    @Override
    public boolean testPermissionSilent(CommandSender sender) {
        return owner.isEnabled() && (OmtCommand.canAdmin(sender) || OmtCommand.canSelf(sender, executor(sender) != null));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }
        Player executor = executor(sender);
        boolean admin = OmtCommand.canAdmin(sender);
        boolean self = OmtCommand.canSelf(sender, executor != null);
        switch (route(args, admin, self)) {
            case RELOAD -> command.reload(sender);
            case SELF -> command.transfer(sender, executor, args[0]);
            case OTHER -> {
                String selector = String.join(" ", Arrays.copyOf(args, args.length - 1));
                try {
                    Player player = selectPlayer(sender, selector);
                    command.transfer(sender, player, args[args.length - 1]);
                } catch (CommandSyntaxException | IllegalArgumentException e) {
                    sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                }
            }
            case DENIED -> sender.sendMessage(Component.text("你没有权限使用此子命令", NamedTextColor.RED));
            case USAGE -> {
                String usage = self ? "/" + label + " <server>" : "";
                if (admin) {
                    usage += (self ? " | " : "") + "/" + label + " <player> <server> | /" + label + " reload";
                }
                sender.sendMessage(Component.text(usage, NamedTextColor.RED));
            }
        }
        return true;
    }

    private Player selectPlayer(CommandSender sender, String selector) throws CommandSyntaxException {
        // Bukkit.selectEntities bypasses vanilla selector permissions. Preserve Paper's selector gate.
        if (selector.startsWith("@") && !sender.hasPermission(SELECTOR_PERMISSION)) {
            throw new IllegalArgumentException("你没有权限使用玩家选择器");
        }
        StringReader input = new StringReader(selector);
        ArgumentTypes.player().parse(input); // Preserve Paper's single-player selector syntax.
        if (input.canRead()) {
            throw new IllegalArgumentException("无效的玩家名称或选择器");
        }
        List<Entity> entities = owner.getServer().selectEntities(sender, selector);
        if (entities.size() != 1 || !(entities.getFirst() instanceof Player player)) {
            throw new IllegalArgumentException("请选择一名在线玩家");
        }
        return player;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender) || args.length == 0) {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        boolean admin = OmtCommand.canAdmin(sender);
        if (args.length > 1) {
            return admin && !OmtCommand.RELOAD.equals(args[0]) ? command.serverSuggestions(prefix) : List.of();
        }
        List<String> suggestions = new ArrayList<>();
        Player executor = executor(sender);
        if (OmtCommand.canSelf(sender, executor != null)) {
            suggestions.addAll(command.serverSuggestions(prefix));
        }
        if (admin) {
            Stream<String> players = owner.getServer().getOnlinePlayers().stream()
                    .filter(player -> executor == null || executor.canSee(player))
                    .map(Player::getName);
            Stream<String> adminSuggestions = Stream.concat(Stream.of(OmtCommand.RELOAD), players);
            if (sender.hasPermission(SELECTOR_PERMISSION)) {
                adminSuggestions = Stream.concat(adminSuggestions, Stream.of("@p", "@r", "@s"));
            }
            adminSuggestions.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).forEach(suggestions::add);
        }
        return suggestions.stream().distinct().toList();
    }

    private static Player executor(CommandSender sender) {
        CommandSender callee = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        return callee instanceof Player player ? player : null;
    }

    static Action route(String[] args, boolean admin, boolean self) {
        if (args.length > 0 && OmtCommand.RELOAD.equals(args[0])) {
            return !admin ? Action.DENIED : args.length == 1 ? Action.RELOAD : Action.USAGE;
        }
        if (args.length == 1 && self) {
            return Action.SELF;
        }
        if (args.length > 1) {
            return admin ? Action.OTHER : Action.DENIED;
        }
        return Action.USAGE;
    }

    enum Action { RELOAD, SELF, OTHER, USAGE, DENIED }
}
