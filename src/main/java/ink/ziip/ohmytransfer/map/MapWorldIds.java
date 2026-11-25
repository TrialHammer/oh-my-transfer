package ink.ziip.ohmytransfer.map;

import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Produces stable, server-scoped 32-bit world IDs for the map mods. */
public final class MapWorldIds {

    static final NamespacedKey OVERWORLD = NamespacedKey.minecraft("overworld");
    private static final NamespacedKey NETHER = NamespacedKey.minecraft("the_nether");
    private static final NamespacedKey END = NamespacedKey.minecraft("the_end");

    private MapWorldIds() {
    }

    /**
     * Hashes the server ID as well as the world UUID so copies of a world on different
     * servers do not systematically share the same ID.
     */
    public static int forServer(String serverId, UUID worldUid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serverId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(worldUid.toString().getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.wrap(digest.digest()).getInt();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean isVanillaSibling(NamespacedKey key, World.Environment environment) {
        return environment == World.Environment.NETHER && key.equals(NETHER)
                || environment == World.Environment.THE_END && key.equals(END);
    }

    static String legacyOverworldName(String name, World.Environment environment) {
        String suffix = switch (environment) {
            case NETHER -> "_nether";
            case THE_END -> "_the_end";
            default -> null;
        };
        return suffix != null && name.length() > suffix.length() && name.endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length()) : null;
    }
}
