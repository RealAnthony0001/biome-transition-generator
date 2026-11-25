package net.biometransitions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.biometransitions.config.ConfigMigration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMigrationTest {
    @Test
    void detectionMovesIntoReplacingAndPairsGetBlockDirection() {
        JsonObject root = JsonParser.parseString("""
                {"configVersion":4,
                 "detection":{"overlapMultiplier":0.7,"smallBiomeDetection":false},
                 "replacing":{"replaceMinSize":100},
                 "pairs":[{"a":"forest","b":"plains","mode":"A_INTO_B","surfaceMixing":true},
                          {"a":"x","b":"y","mode":"BOTH","surfaceMixing":false},
                          {"a":"p","b":"q","mode":"DO_NOT_BLEND","surfaceMixing":true},
                          {"a":"m","b":"n","mode":"BOTH"}]}""").getAsJsonObject();
        ConfigMigration.migrate(root, 4);
        assertFalse(root.has("detection"));
        var r = root.getAsJsonObject("replacing");
        assertEquals(0.7, r.get("overlapMultiplier").getAsDouble());
        assertFalse(r.get("smallBiomeDetection").getAsBoolean());
        assertEquals(100, r.get("replaceMinSize").getAsInt());
        var pairs = root.getAsJsonArray("pairs");
        assertEquals("A_INTO_B", pairs.get(0).getAsJsonObject().get("blockDirection").getAsString());
        assertEquals("NO_BLENDING", pairs.get(1).getAsJsonObject().get("blockDirection").getAsString());
        assertEquals("NO_BLENDING", pairs.get(2).getAsJsonObject().get("blockDirection").getAsString());
        assertFalse(pairs.get(3).getAsJsonObject().has("blockDirection")); // new field default applies
        for (var p : pairs) assertFalse(p.getAsJsonObject().has("surfaceMixing"));
    }

    @Test
    void pairDistancesMinusOneMeantGlobalBeforeVersion6() {
        String json = """
                {"pairs":[{"a":"taiga","b":"snowy","distance":-1,"treeDistance":-1,"blockDistance":-1},
                          {"a":"x","b":"y","treeDistance":40,"blockDistance":0},
                          {"a":"m","b":"n"}]}""";
        JsonObject old = JsonParser.parseString(json).getAsJsonObject();
        ConfigMigration.migrate(old, 5);
        var p0 = old.getAsJsonArray("pairs").get(0).getAsJsonObject();
        assertEquals(-1, p0.get("distance").getAsInt()); // Mixing Distance keeps -1 = Global
        assertEquals(129, p0.get("treeDistance").getAsInt());
        assertEquals(129, p0.get("blockDistance").getAsInt());
        var p1 = old.getAsJsonArray("pairs").get(1).getAsJsonObject();
        assertEquals(40, p1.get("treeDistance").getAsInt());
        assertEquals(0, p1.get("blockDistance").getAsInt());
        assertFalse(old.getAsJsonArray("pairs").get(2).getAsJsonObject().has("treeDistance"));

        JsonObject current = JsonParser.parseString(json).getAsJsonObject();
        ConfigMigration.migrate(current, 6);
        assertEquals(-1, current.getAsJsonArray("pairs").get(0).getAsJsonObject().get("treeDistance").getAsInt());
    }
}
