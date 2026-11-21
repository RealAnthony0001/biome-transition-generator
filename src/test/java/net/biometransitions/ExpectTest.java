package net.biometransitions;

import com.google.gson.JsonParser;
import net.biometransitions.core.Expect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpectTest {
    private static double v(String json) { return Expect.value(JsonParser.parseString(json)); }

    private static double m(String json) { return Expect.modifier(JsonParser.parseString(json).getAsJsonObject()); }

    @Test
    void intProviders() {
        assertEquals(3, v("3"));
        assertEquals(2, v("{\"type\":\"minecraft:uniform\",\"min_inclusive\":0,\"max_inclusive\":4}"));
        assertEquals(0.1, v("{\"type\":\"minecraft:weighted_list\",\"distribution\":[{\"data\":0,\"weight\":9},{\"data\":1,\"weight\":1}]}"), 1e-9);
        assertEquals(5, v("{\"type\":\"minecraft:constant\",\"value\":5}"));
    }

    @Test
    void placementModifiers() {
        assertEquals(10, m("{\"type\":\"minecraft:count\",\"count\":10}"));
        assertEquals(1.0 / 32, m("{\"type\":\"minecraft:rarity_filter\",\"chance\":32}"));
        assertEquals(7.5, m("{\"type\":\"minecraft:noise_threshold_count\",\"noise_level\":-0.8,\"below_noise\":5,\"above_noise\":10}"));
        assertEquals(1, m("{\"type\":\"minecraft:in_square\"}"));
    }

    @Test
    void customMapping() {
        var out = Expect.mapping("ocean-river, forest - plains,broken,-x,minecraft:deep_ocean-minecraft:river");
        assertEquals(3, out.size());
        assertArrayEquals(new String[]{"ocean", "river"}, out.get(0));
        assertArrayEquals(new String[]{"forest", "plains"}, out.get(1));
        assertArrayEquals(new String[]{"minecraft:deep_ocean", "minecraft:river"}, out.get(2));
    }
}
