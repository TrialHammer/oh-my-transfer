package ink.ziip.ohmytransfer.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps the native tree on startup and handles runtime enable when no registrar event fires. */
public final class OmtCommandRegistration {

    private final JavaPlugin plugin;
    private final OmtCommand command;
    private final SuggestionFilterListener suggestionFilter;
    private final String namespace;
    private Set<String> nativeLabels = Set.of();
    private HotOmtCommand fallback;

    public OmtCommandRegistration(JavaPlugin plugin, OmtCommand command, SuggestionFilterListener suggestionFilter) {
        this.plugin = plugin;
        this.command = command;
        this.suggestionFilter = suggestionFilter;
        this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
    }

    public void start() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            removeFallback();
            nativeLabels = event.registrar().register(command.build(), OmtCommand.DESCRIPTION, List.of(OmtCommand.ALIAS));
            suggestionFilter.setCommandLabels(nativeLabels);
        });

        // Normal startup fires COMMANDS before the first tick. PlugMan's enable path does not.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (nativeLabels.isEmpty()) {
                registerFallback();
            }
        });
    }

    private void registerFallback() {
        var commandMap = plugin.getServer().getCommandMap();
        fallback = new HotOmtCommand(plugin, command, commandMap.getCommand(OmtCommand.ALIAS) == null);
        commandMap.register(namespace, fallback);
        String namespacedAlias = namespace + ":" + OmtCommand.ALIAS;
        if (commandMap.getCommand(namespacedAlias) == null) {
            commandMap.getKnownCommands().put(namespacedAlias, fallback);
        }
        plugin.getServer().getOnlinePlayers().forEach(Player::updateCommands);
        plugin.getLogger().info("Registered /omt through the command map after runtime enable.");
    }

    private void removeFallback() {
        if (fallback == null) {
            return;
        }
        var commandMap = plugin.getServer().getCommandMap();
        var knownCommands = commandMap.getKnownCommands();
        for (String name : List.of(OmtCommand.NAME, OmtCommand.ALIAS)) {
            knownCommands.remove(name, fallback);
            knownCommands.remove(namespace + ":" + name, fallback);
        }
        fallback.unregister(commandMap);
        fallback = null;
    }

    public void stop() {
        removeFallback();
        if (!nativeLabels.isEmpty()) {
            var commandMap = plugin.getServer().getCommandMap();
            for (String label : nativeLabels) {
                if (commandMap.getCommand(label) instanceof PluginIdentifiableCommand owned
                        && owned.getPlugin() == plugin) {
                    commandMap.getKnownCommands().remove(label);
                }
            }
        }
        nativeLabels = Set.of();
        suggestionFilter.setCommandLabels(Set.of());
    }
}
