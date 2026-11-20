package net.biometransitions;

import com.google.gson.JsonParser;
import net.biometransitions.core.Json;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.TransitionMath.Curve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitionMathTest {
    static double share(double d, double width, Curve c) {
        return TransitionMath.foreignShare(d, width, 1, c, List.of(), 0, 0, 0, 0, 0, 1, 1, 1);
    }

    @Test
    void balancedAtBoundaryAndZeroAtEdge() {
        for (Curve c : Curve.values()) {
            assertEquals(0.5, share(0, 16, c), 1e-9, c.name());
            assertEquals(0, share(16, 16, c), 1e-9, c.name());
            assertEquals(0, share(40, 16, c), 1e-9, c.name());
        }
    }

    @Test
    void monotonicInDistance() {
        double prev = 1;
        for (int d = 0; d <= 16; d++) {
            double s = share(d, 16, Curve.SMOOTHSTEP);
            assertTrue(s <= prev);
            prev = s;
        }
    }

    @Test
    void asymmetricWidthsAndInfluence() {
        assertTrue(share(8, 32, Curve.LINEAR) > share(8, 10, Curve.LINEAR));
        assertEquals(0, share(0, 0, Curve.LINEAR));
        double strongForeign = TransitionMath.foreignShare(0, 16, 1, Curve.LINEAR, List.of(), 0, 0, 0, 0, 0, 1, 1, 3);
        assertEquals(0.75, strongForeign, 1e-9);
    }

    @Test
    void customCurveInterpolates() {
        assertEquals(0.25, TransitionMath.curve(Curve.CUSTOM, 0.5, List.of(0.0, 0.25, 1.0)), 1e-9);
        assertEquals(0.625, TransitionMath.curve(Curve.CUSTOM, 0.75, List.of(0.0, 0.25, 1.0)), 1e-9);
    }

    @Test
    void weightedKeyFavoursHeavierWeight() {
        assertTrue(TransitionMath.weightedKey(0.5, 4) > TransitionMath.weightedKey(0.5, 1));
        assertEquals(-1, TransitionMath.weightedKey(0.5, 0));
    }

    @Test
    void hashIsDeterministicAndInRange() {
        double a = TransitionMath.hash01(42, 1, 2, 3, 7);
        assertEquals(a, TransitionMath.hash01(42, 1, 2, 3, 7));
        assertNotEquals(a, TransitionMath.hash01(42, 1, 2, 4, 7));
        assertTrue(a >= 0 && a < 1);
    }

    @Test
    void jsonMergeOverridesOnlyGivenFields() {
        var base = JsonParser.parseString("{\"zone\":{\"width\":16,\"falloff\":1},\"fluid\":{\"width\":8}}").getAsJsonObject();
        var over = JsonParser.parseString("{\"zone\":{\"width\":24}}").getAsJsonObject();
        assertEquals("{\"zone\":{\"width\":24,\"falloff\":1},\"fluid\":{\"width\":8}}", Json.merge(base, over).toString());
    }

    @Test
    void climateNarrowsZoneForDifferentBiomes() {
        assertEquals(1, TransitionMath.climateWidthFactor(0.7, 0.8, 0.7, 0.8, 1), 1e-9);       // forest / forest
        assertEquals(0.25, TransitionMath.climateWidthFactor(2.0, 0.0, -0.5, 0.9, 1), 1e-9);   // desert / snowy
        double plains = TransitionMath.climateWidthFactor(0.7, 0.8, 0.8, 0.4, 1);              // forest / plains
        assertTrue(plains > 0.25 && plains < 1);
        assertEquals(1, TransitionMath.climateWidthFactor(2.0, 0.0, -0.5, 0.9, 0), 1e-9);      // adaptation off
    }
}
