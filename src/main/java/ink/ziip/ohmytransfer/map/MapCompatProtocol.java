package ink.ziip.ohmytransfer.map;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire formats used by the supported map mods.
 *
 * <p>Xaero's Minimap and World Map both use {@code LevelMapProperties}: a marker byte
 * followed by a big-endian int32 world ID. Diorama reads the same messages from those
 * channels when Xaero is not installed.</p>
 *
 * <p>JourneyMap and VoxelMap use {@code worldinfo:world_id}. The client sends a short
 * request and the server echoes its shape with a one-byte-length UTF-8 world ID.</p>
 */
public final class MapCompatProtocol {

    public static final String XAERO_MINIMAP_CHANNEL = "xaerominimap:main";
    public static final String XAEROWORLDMAP_CHANNEL = "xaeroworldmap:main";
    public static final String WORLD_INFO_CHANNEL = "worldinfo:world_id";
    public static final String VOXELMAP_SETTINGS_CHANNEL = "voxelmap:settings";

    public static final byte XAERO_LEVEL_MAP_PROPERTIES = 0x00;
    private static final int WORLD_INFO_MAGIC = 42;

    private MapCompatProtocol() {
    }

    /** Five-byte Xaero {@code LevelMapProperties} payload. */
    public static byte[] xaeroLevelMapProperties(int worldId) {
        return ByteBuffer.allocate(5)
                .put(XAERO_LEVEL_MAP_PROPERTIES)
                .putInt(worldId)
                .array();
    }

    /**
     * Parses the known JourneyMap/VoxelMap world ID requests. The prefix carries the
     * packet ID expected by older mod versions; it is not part of the channel name.
     *
     * @return the request shape, or {@code null} if the payload is not supported
     */
    public static WorldInfoRequest worldInfoRequest(byte[] payload) {
        return worldInfoRequest(payload, -1);
    }

    /**
     * @param protocolVersion the client's protocol version, or a negative value if unknown
     */
    public static WorldInfoRequest worldInfoRequest(byte[] payload, int protocolVersion) {
        if (payload == null || payload.length < 2 || payload.length > 5) {
            return null;
        }
        int zeros = 0;
        while (zeros < payload.length && payload[zeros] == 0) {
            zeros++;
        }
        if (zeros == payload.length) {
            // VoxelMap Forge 1.12.2: two zero bytes, no magic byte.
            return payload.length == 2 ? new WorldInfoRequest(1, false) : null;
        }
        if (payload[zeros] != WORLD_INFO_MAGIC
                || (payload.length != zeros + 1 && (payload.length != zeros + 2 || payload[zeros + 1] != 0))) {
            return null;
        }
        if (zeros == 1) {
            // VoxelMap Forge 1.13.2 - 1.16.3 used no magic byte in the response.
            if (protocolVersion >= 0 && protocolVersion <= 753) {
                return new WorldInfoRequest(1, false);
            }
            // JourneyMap 1.16.5+, VoxelMap 1.19.3+.
            return new WorldInfoRequest(1, true);
        }
        if (zeros == 3) {
            // VoxelMap Fabric 1.14.4 - 1.19.x.
            return new WorldInfoRequest(0, true);
        }
        return null;
    }

    /** Builds a JourneyMap/VoxelMap {@code worldinfo:world_id} response. */
    public static byte[] worldInfoResponse(WorldInfoRequest request, String worldId) {
        byte[] encoded = worldId.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > 127) {
            throw new IllegalArgumentException("world ID must be 1..127 UTF-8 bytes");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(3 + request.padding() + encoded.length);
        for (int i = 0; i < request.padding(); i++) {
            output.write(0);
        }
        if (request.magic()) {
            output.write(WORLD_INFO_MAGIC);
        }
        // VoxelMap reads one unsigned byte; JourneyMap's VarInt has the same encoding up to 127.
        output.write(encoded.length);
        output.writeBytes(encoded);
        return output.toByteArray();
    }

    /** Sends only the VoxelMap root identity, leaving all client map options unchanged. */
    public static byte[] voxelMapIdentity(String identity) {
        JsonObject settings = new JsonObject();
        settings.addProperty("serverIdentity", identity);
        byte[] json = settings.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(2 + json.length);
        output.write(0);
        int length = json.length;
        while (length > 127) {
            output.write((length & 127) | 128);
            length >>>= 7;
        }
        output.write(length);
        output.writeBytes(json);
        return output.toByteArray();
    }

    public record WorldInfoRequest(int padding, boolean magic) {
        public WorldInfoRequest {
            if (padding < 0 || padding > 3) {
                throw new IllegalArgumentException("padding must be between 0 and 3");
            }
        }
    }
}
