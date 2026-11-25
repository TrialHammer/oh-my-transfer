package ink.ziip.ohmytransfer.config;

import ink.ziip.ohmytransfer.ticket.IdentityMatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record Settings(
        String serverId,
        byte[] secret,
        NamespacedKey cookieKey,
        long ticketTtlMillis,
        long clockSkewMillis,
        boolean requireTicket,
        IdentityMatch identityMatch,
        Set<UUID> bypassUuids,
        boolean floodgateBypass,
        long cookieTimeoutMillis,
        Component denyMessage,
        MapCompat mapCompat,
        Wormholes wormholes,
        Map<String, Target> servers
) {
    public static final int MIN_SECRET_BYTES = 32;
    /**
     * Server names are typed as a Brigadier {@code word} argument and must not contain '.' (YAML path separator).
     */
    private static final Pattern SERVER_NAME = Pattern.compile("[0-9A-Za-z_+-]+");
    private static final Pattern VOXELMAP_IDENTITY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Names taken by literal subcommands of /omt. */
    private static final Set<String> RESERVED_SERVER_NAMES = Set.of("reload");

    public record Target(String host, int port) {
    }

    /**
     * Server-side map-mod compatibility. Disabled by default because switching an
     * existing world to a server-provided world ID changes where clients keep their
     * map data.
     */
    public record MapCompat(boolean enabled, boolean xaero, boolean worldInfo, int joinRepeat,
                            String voxelMapServerIdentity) {
    }

    public record Wormholes(boolean enabled, boolean mapValid, Map<String, String> serverMap) {
        public String targetId(String peerName) {
            return serverMap.getOrDefault(peerName, peerName);
        }
    }

    /**
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public static Settings load(FileConfiguration config) {
        String serverId = config.getString("server-id", "").trim();
        checkServerName(serverId, "server-id");

        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(config.getString("secret", "").trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("secret is not valid Base64", e);
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("secret must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }

        NamespacedKey cookieKey = NamespacedKey.fromString(config.getString("cookie-key", "omt:ticket"));
        if (cookieKey == null) {
            throw new IllegalArgumentException("cookie-key is not a valid namespaced key");
        }

        String matchBy = config.getString("receiver.match-by", "uuid").trim();
        IdentityMatch identityMatch;
        try {
            identityMatch = IdentityMatch.valueOf(matchBy.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("receiver.match-by must be uuid, name or uuid-or-name: " + matchBy, e);
        }

        Set<UUID> bypass = new HashSet<>();
        for (String raw : config.getStringList("receiver.bypass-uuids")) {
            try {
                bypass.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid UUID in receiver.bypass-uuids: " + raw, e);
            }
        }

        Map<String, Target> servers = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("servers");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                checkServerName(name, "servers." + name);
                String host = section.getString(name + ".host", "").trim();
                int port = section.getInt(name + ".port", 25565);
                if (host.isEmpty() || port < 1 || port > 65535) {
                    throw new IllegalArgumentException("invalid host/port for servers." + name);
                }
                servers.put(name, new Target(host, port));
            }
        }

        String voxelMapIdentity = config.getString("map-compat.voxelmap-server-identity", "").trim();
        if (!voxelMapIdentity.isEmpty() && !VOXELMAP_IDENTITY.matcher(voxelMapIdentity).matches()) {
            throw new IllegalArgumentException("map-compat.voxelmap-server-identity must be empty or a "
                    + "1..64 character lowercase name using letters, digits, '_' or '-' and starting with a letter or digit");
        }
        Map<String, String> wormholesServers = new LinkedHashMap<>();
        Object wormholesMapValue = config.get("wormholes.server-map");
        if (wormholesMapValue instanceof ConfigurationSection wormholesMap) {
            for (var entry : wormholesMap.getValues(false).entrySet()) {
                // Invalid values must not fall back to signing a ticket for a same-named target.
                wormholesServers.put(entry.getKey(), entry.getValue() instanceof String value ? value.trim() : "");
            }
        }

        return new Settings(
                serverId,
                secret,
                cookieKey,
                Math.max(1, config.getLong("ticket-ttl-seconds", 30)) * 1000L,
                Math.max(0, config.getLong("clock-skew-seconds", 5)) * 1000L,
                config.getBoolean("receiver.require-ticket", true),
                identityMatch,
                Set.copyOf(bypass),
                config.getBoolean("receiver.floodgate-bypass", true),
                Math.max(100, config.getLong("receiver.cookie-timeout-millis", 3000)),
                MiniMessage.miniMessage().deserialize(
                        config.getString("receiver.deny-message", "<red>请通过服务器传送进入本服。</red>")),
                new MapCompat(
                        config.getBoolean("map-compat.enabled", false),
                        config.getBoolean("map-compat.xaero", true),
                        config.getBoolean("map-compat.world-info", true),
                        Math.clamp(config.getInt("map-compat.join-repeat", 3), 1, 5),
                        voxelMapIdentity),
                new Wormholes(config.getBoolean("wormholes.enabled", true),
                        wormholesMapValue == null || wormholesMapValue instanceof ConfigurationSection,
                        Map.copyOf(wormholesServers)),
                Collections.unmodifiableMap(servers)
        );
    }

    private static void checkServerName(String name, String path) {
        if (!SERVER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(path + " must only contain letters, digits, '_', '-' or '+': " + name);
        }
        if (RESERVED_SERVER_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(path + " uses a reserved name: " + name);
        }
    }
}
