package ink.ziip.ohmytransfer.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Brings an existing config.yml up to date with the bundled one after a plugin upgrade.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * Copies every key that exists in {@code defaults} but not in {@code config}, together with its comments.
     * Existing values are never changed. Sections in {@code userOwned} are skipped entirely, because their
     * children are user data (e.g. the example server under {@code servers}) rather than options.
     *
     * @return the paths of the added values
     */
    public static List<String> addMissingKeys(ConfigurationSection config, ConfigurationSection defaults,
                                              Set<String> userOwned) {
        List<String> added = new ArrayList<>();
        // Deep keys are listed parent-first, so a missing section is created before its children are filled in.
        for (String path : defaults.getKeys(true)) {
            if (isUserOwned(path, userOwned) || config.contains(path, true)) {
                continue;
            }
            if (defaults.isConfigurationSection(path)) {
                config.createSection(path);
            } else {
                config.set(path, defaults.get(path));
                added.add(path);
            }
            config.setComments(path, defaults.getComments(path));
            config.setInlineComments(path, defaults.getInlineComments(path));
        }
        return added;
    }

    private static boolean isUserOwned(String path, Set<String> userOwned) {
        for (String section : userOwned) {
            if (path.equals(section) || path.startsWith(section + ".")) {
                return true;
            }
        }
        return false;
    }
}
