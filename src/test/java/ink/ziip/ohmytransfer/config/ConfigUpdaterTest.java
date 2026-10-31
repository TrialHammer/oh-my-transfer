package ink.ziip.ohmytransfer.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    /** A config.yml written by an older plugin version: no receiver.match-by, user-edited values. */
    private static final String OLD_CONFIG = """
            # my own header comment
            server-id: survival
            secret: "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0MTI="
            cookie-key: "omt:ticket"
            ticket-ttl-seconds: 10
            clock-skew-seconds: 5
            receiver:
              require-ticket: false
              bypass-uuids:
              - 069a79f4-44e9-4726-a5be-fca90e38aaf5
              cookie-timeout-millis: 3000
              deny-message: "<red>nope</red>"
            servers:
              lobby:
                host: "10.0.0.1"
                port: 25565
            """;

    private static YamlConfiguration bundledDefaults() throws Exception {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(
                ConfigUpdaterTest.class.getClassLoader().getResourceAsStream("config.yml")), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static YamlConfiguration load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test
    void addsNewOptionWithCommentsAndKeepsUserValues() throws Exception {
        YamlConfiguration config = load(OLD_CONFIG);
        YamlConfiguration defaults = bundledDefaults();
        config.setDefaults(defaults); // same setup as JavaPlugin#reloadConfig

        List<String> added = ConfigUpdater.addMissingKeys(config, defaults, Set.of("servers"));
        assertEquals(List.of("receiver.match-by"), added);

        // Round-trip through the file format, as saveConfig() would.
        YamlConfiguration saved = load(config.saveToString());
        assertEquals("uuid", saved.getString("receiver.match-by"));
        assertFalse(saved.getComments("receiver.match-by").isEmpty(), "comments of the new option are copied");

        assertEquals("survival", saved.getString("server-id"));
        assertEquals(10, saved.getInt("ticket-ttl-seconds"));
        assertFalse(saved.getBoolean("receiver.require-ticket"));
        assertEquals(List.of("069a79f4-44e9-4726-a5be-fca90e38aaf5"), saved.getStringList("receiver.bypass-uuids"));
        assertEquals("<red>nope</red>", saved.getString("receiver.deny-message"));
        assertTrue(config.saveToString().contains("# my own header comment"));
    }

    @Test
    void doesNotMergeExampleServers() throws Exception {
        YamlConfiguration config = load(OLD_CONFIG);
        YamlConfiguration defaults = bundledDefaults();
        config.setDefaults(defaults);

        ConfigUpdater.addMissingKeys(config, defaults, Set.of("servers"));

        YamlConfiguration saved = load(config.saveToString());
        assertEquals(Set.of("lobby"), saved.getConfigurationSection("servers").getKeys(false));
    }

    @Test
    void upToDateConfigIsUnchanged() throws Exception {
        YamlConfiguration defaults = bundledDefaults();
        YamlConfiguration config = load(defaults.saveToString());
        config.setDefaults(defaults);

        assertTrue(ConfigUpdater.addMissingKeys(config, defaults, Set.of("servers")).isEmpty());
    }

    @Test
    void missingSectionIsCreatedWithAllChildren() throws Exception {
        YamlConfiguration config = load("server-id: a\n");
        YamlConfiguration defaults = bundledDefaults();
        config.setDefaults(defaults);

        List<String> added = ConfigUpdater.addMissingKeys(config, defaults, Set.of("servers"));

        assertTrue(added.contains("receiver.require-ticket"));
        assertTrue(added.contains("receiver.match-by"));
        assertFalse(added.contains("server-id"));
        assertTrue(added.stream().noneMatch(path -> path.startsWith("servers")));
        YamlConfiguration saved = load(config.saveToString());
        assertTrue(saved.isConfigurationSection("receiver"));
        assertFalse(saved.contains("servers"));
    }
}
