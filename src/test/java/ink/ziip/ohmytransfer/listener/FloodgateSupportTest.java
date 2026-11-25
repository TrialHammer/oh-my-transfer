package ink.ziip.ohmytransfer.listener;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodgateSupportTest {

    @Test
    void delegatesFloodgatePlayerCheck() {
        UUID bedrockId = UUID.fromString("00000000-0000-0000-0009-01f4b7e9d2c3");
        UUID javaId = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        Set<UUID> floodgateIds = Set.of(bedrockId);
        FloodgateSupport support = new FloodgateSupport(floodgateIds::contains);

        assertTrue(support.isFloodgatePlayer(bedrockId));
        assertFalse(support.isFloodgatePlayer(javaId));
    }
}
