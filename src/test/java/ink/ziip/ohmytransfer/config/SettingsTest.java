package ink.ziip.ohmytransfer.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void floodgateBypassDefaultsToEnabled() throws Exception {
        assertTrue(load("").floodgateBypass());
    }

    @Test
    void floodgateBypassCanBeDisabled() throws Exception {
        assertFalse(load("receiver:\n  floodgate-bypass: false\n").floodgateBypass());
    }

    @Test
    void mapCompatIsDisabledByDefault() throws Exception {
        Settings.MapCompat mapCompat = load("").mapCompat();
        assertFalse(mapCompat.enabled());
        assertTrue(mapCompat.xaero());
        assertTrue(mapCompat.worldInfo());
        assertEquals(3, mapCompat.joinRepeat());
        assertEquals("", mapCompat.voxelMapServerIdentity());
    }

    @Test
    void mapCompatCanBeConfigured() throws Exception {
        Settings.MapCompat mapCompat = load("""
                map-compat:
                  enabled: true
                  xaero: false
                  world-info: true
                  join-repeat: 5
                  voxelmap-server-identity: "example-network_1"
                """).mapCompat();
        assertTrue(mapCompat.enabled());
        assertFalse(mapCompat.xaero());
        assertTrue(mapCompat.worldInfo());
        assertEquals(5, mapCompat.joinRepeat());
        assertEquals("example-network_1", mapCompat.voxelMapServerIdentity());
    }

    @Test
    void mapCompatJoinRepeatIsBounded() throws Exception {
        assertEquals(1, load("map-compat:\n  join-repeat: 0\n").mapCompat().joinRepeat());
        assertEquals(5, load("map-compat:\n  join-repeat: 99\n").mapCompat().joinRepeat());
    }

    @Test
    void rejectsInvalidVoxelMapIdentities() {
        for (String value : new String[]{"../world", "two words", "UpperCase", "_network", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> load("map-compat:\n  voxelmap-server-identity: \"" + value + "\"\n"), value);
        }
    }

    @Test
    void mapsWormholesPeerNamesWithoutChangingOmtIds() throws Exception {
        assertTrue(load("").wormholes().enabled());
        Settings.Wormholes bridge = load("""
                wormholes:
                  enabled: false
                  server-map:
                    OtherPeer: survival
                    broken: [survival]
                """).wormholes();
        assertFalse(bridge.enabled());
        assertTrue(bridge.mapValid());
        assertEquals("survival", bridge.targetId("OtherPeer"));
        assertEquals("unmapped", bridge.targetId("unmapped"));
        assertEquals("", bridge.targetId("broken"));
    }

    @Test
    void rejectsMalformedWormholesMapWithoutDisablingOmt() throws Exception {
        assertFalse(load("wormholes:\n  server-map: not-a-map\n").wormholes().mapValid());
    }

    private static Settings load(String extra) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                server-id: survival
                secret: "%s"
                %s
                """.formatted(SECRET, extra));
        return Settings.load(config);
    }
}
