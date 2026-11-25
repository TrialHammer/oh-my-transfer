package ink.ziip.ohmytransfer.map;

import org.bukkit.NamespacedKey;
import org.bukkit.World.Environment;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapWorldIdsTest {

    private static final UUID WORLD = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void preservesPreviouslyPublishedWorldId() {
        assertEquals(820967805, MapWorldIds.forServer("survival", WORLD));
    }

    @Test
    void differsAcrossServersSharingACopiedWorld() {
        assertNotEquals(MapWorldIds.forServer("survival", WORLD), MapWorldIds.forServer("creative", WORLD));
    }

    @Test
    void differsAcrossWorldsOnOneServer() {
        UUID other = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        assertNotEquals(MapWorldIds.forServer("survival", WORLD), MapWorldIds.forServer("survival", other));
    }

    @Test
    void usesDimensionKeysForTheVanillaSiblingsOnly() {
        assertTrue(MapWorldIds.isVanillaSibling(NamespacedKey.minecraft("the_nether"), Environment.NETHER));
        assertTrue(MapWorldIds.isVanillaSibling(NamespacedKey.minecraft("the_end"), Environment.THE_END));
        assertFalse(MapWorldIds.isVanillaSibling(NamespacedKey.minecraft("the_end"), Environment.NETHER));
        assertFalse(MapWorldIds.isVanillaSibling(new NamespacedKey("custom", "the_nether"), Environment.NETHER));
    }

    @Test
    void retainsSuffixGroupingForLegacyNamedSiblings() {
        assertEquals("survival", MapWorldIds.legacyOverworldName("survival_nether", Environment.NETHER));
        assertEquals("survival", MapWorldIds.legacyOverworldName("survival_the_end", Environment.THE_END));
        assertNull(MapWorldIds.legacyOverworldName("arena_nether", Environment.NORMAL));
        assertNull(MapWorldIds.legacyOverworldName("the_end", Environment.THE_END));
        assertNull(MapWorldIds.legacyOverworldName("_nether", Environment.NETHER));
    }
}
