package ink.ziip.ohmytransfer.map;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapCompatProtocolTest {

    @Test
    void xaeroPayloadIsMarkerFollowedByBigEndianId() {
        assertArrayEquals(new byte[]{0, 0, 0, 0, 1},
                MapCompatProtocol.xaeroLevelMapProperties(1));
        assertArrayEquals(new byte[]{0, 0, 0, 5, 57},
                MapCompatProtocol.xaeroLevelMapProperties(1337));
        assertArrayEquals(new byte[]{0, (byte) 0x80, 0, 0, 0},
                MapCompatProtocol.xaeroLevelMapProperties(Integer.MIN_VALUE));
    }

    @Test
    void modernWorldInfoRequestUsesOneZeroAndMagicByte() {
        MapCompatProtocol.WorldInfoRequest request =
                MapCompatProtocol.worldInfoRequest(new byte[]{0, 42, 0}, 774);
        assertNotNull(request);
        assertEquals(1, request.padding());
        assertEquals(true, request.magic());
        assertEquals(request, MapCompatProtocol.worldInfoRequest(new byte[]{0, 42}, 774));
    }

    @Test
    void oldVoxelMapWorldInfoRequestUsesNoMagicByteInResponse() {
        MapCompatProtocol.WorldInfoRequest request =
                MapCompatProtocol.worldInfoRequest(new byte[]{0, 42, 0}, 753);
        assertNotNull(request);
        assertEquals(1, request.padding());
        assertEquals(false, request.magic());
    }

    @Test
    void legacyVoxelMapRequestUsesTwoZeroesWithoutMagicByte() {
        MapCompatProtocol.WorldInfoRequest request =
                MapCompatProtocol.worldInfoRequest(new byte[]{0, 0});
        assertNotNull(request);
        assertEquals(1, request.padding());
        assertEquals(false, request.magic());
    }

    @Test
    void voxelMapFabricOldRequestUsesThreeZeroesAndMagicByte() {
        MapCompatProtocol.WorldInfoRequest request =
                MapCompatProtocol.worldInfoRequest(new byte[]{0, 0, 0, 42});
        assertNotNull(request);
        assertEquals(0, request.padding());
        assertEquals(true, request.magic());
        assertArrayEquals(new byte[]{42, 2, '-', '5'}, MapCompatProtocol.worldInfoResponse(request, "-5"));
    }

    @Test
    void malformedWorldInfoRequestsAreIgnored() {
        assertNull(MapCompatProtocol.worldInfoRequest(null));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[0]));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{0}));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{0, 0, 0}));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{1, 42, 0}));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{0, 42, 1}));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{0, 42, 0, 0}));
        assertNull(MapCompatProtocol.worldInfoRequest(new byte[]{0, 0, 42}));
    }

    @Test
    void journeyMapStyleResponseIsPrefixMagicLengthAndUtf8() {
        byte[] id = "2086065301".getBytes(StandardCharsets.UTF_8);
        byte[] response = MapCompatProtocol.worldInfoResponse(
                new MapCompatProtocol.WorldInfoRequest(1, true), "2086065301");

        assertEquals(3 + id.length, response.length);
        assertEquals(0, response[0]);
        assertEquals(42, response[1]);
        assertEquals(id.length, response[2]);
        assertArrayEquals(id, java.util.Arrays.copyOfRange(response, 3, response.length));
    }

    @Test
    void legacyResponseOmitsMagicByte() {
        assertArrayEquals(new byte[]{0, 1, '1'}, MapCompatProtocol.worldInfoResponse(
                new MapCompatProtocol.WorldInfoRequest(1, false), "1"));
    }

    @Test
    void voxelMapIdentityIsAFramedJsonSettingAndDoesNotOverrideOtherOptions() {
        byte[] payload = MapCompatProtocol.voxelMapIdentity("example-network");
        String expected = "{\"serverIdentity\":\"example-network\"}";

        assertEquals(0, payload[0]);
        assertEquals(expected.length(), payload[1]);
        assertEquals(expected, new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8));
    }
}
