package ink.ziip.ohmytransfer.wormholes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesBridgeTest {

    @Test
    void blocksDirectAndShorthandConnectAcrossAliases() {
        for (String label : new String[]{"wh", "wormhole", "wormholes", "wormholes:wh",
                "wormholes:wormhole", "wormholes:wormholes"}) {
            assertTrue(WormholesBridge.isUnsignedConnectCommand("/" + label + " server connect Peer"), label);
            assertTrue(WormholesBridge.isUnsignedConnectCommand("/" + label + " server Peer"), label);
        }
        assertTrue(WormholesBridge.isUnsignedConnectCommand("/WH  SERVER  CONNECT name=Peer"));
    }

    @Test
    void doesNotBlockManagementCommandsOrOmt() {
        for (String command : new String[]{"/wh server", "/wh server list", "/wh server export",
                "/wh server import code", "/wh server remove name", "/wh server help",
                "/wh portal connect", "/server peer", "/omt peer", "/other:wh server peer"}) {
            assertFalse(WormholesBridge.isUnsignedConnectCommand(command), command);
        }
    }
}
