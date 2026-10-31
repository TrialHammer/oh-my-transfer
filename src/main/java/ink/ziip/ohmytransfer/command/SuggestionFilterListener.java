package ink.ziip.ohmytransfer.command;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import ink.ziip.ohmytransfer.OhMyTransferPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hides admin-only suggestions from players who may only use {@code /omt <server>}.
 *
 * <p>The {@code <server>} argument has custom suggestions, so the client asks the server for them. Brigadier
 * then lists suggestions of every sibling node at that position without checking {@code requires}, which
 * would show {@code reload} and online player names (from the admin-only {@code <player>} branch) to
 * players that only have {@value OmtCommand#SELF_PERMISSION}.
 */
public final class SuggestionFilterListener implements Listener {

    private static final Pattern OMT_COMMAND = Pattern.compile("^/(?:ohmytransfer:)?omt ", Pattern.CASE_INSENSITIVE);

    private final OhMyTransferPlugin plugin;

    public SuggestionFilterListener(OhMyTransferPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onSendSuggestions(AsyncPlayerSendSuggestionsEvent event) {
        if (event.getPlayer().hasPermission(OmtCommand.ADMIN_PERMISSION)) {
            return;
        }
        Suggestions filtered = restrictToServers(event.getBuffer(), event.getSuggestions(),
                plugin.settings().servers().keySet());
        if (filtered != null) {
            event.setSuggestions(filtered);
        }
    }

    /**
     * @return the suggestions without entries that are not server names, or {@code null} if nothing changes
     */
    static Suggestions restrictToServers(String buffer, Suggestions suggestions, Set<String> servers) {
        if (!OMT_COMMAND.matcher(buffer).find()) {
            return null;
        }
        List<Suggestion> kept = suggestions.getList().stream()
                .filter(suggestion -> servers.contains(suggestion.getText()))
                .toList();
        return kept.size() == suggestions.getList().size() ? null : new Suggestions(suggestions.getRange(), kept);
    }
}
