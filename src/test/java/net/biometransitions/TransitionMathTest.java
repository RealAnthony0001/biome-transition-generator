package net.biometransitions;

import com.google.gson.JsonParser;
import net.biometransitions.core.Json;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.TransitionMath.Curve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitionMathTest {
    /** Neutral settings, both biomes reaching {@code width} into each other. */
    static double share(double d, double width, Curve c) {
        return share(d, width, width, c);
    }

    static double share(double d, double wIn, double wOut, Curve c) {
        return TransitionMath.share(d, wIn, wOut, 1, c, List.of(), 0, 0, 1, 1, 1);
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
        for (Curve c : Curve.values()) {
            double prev = 1;
            for (int d = 0; d <= 16; d++) {
                double s = share(d, 16, c);
                assertTrue(s <= prev + 1e-12, c.name() + " at " + d);
                prev = s;
            }
        }
    }

    /** Both biomes evaluate one curve: at mirrored spots their shares add up to exactly one biome's worth. */
    @Test
    void bothSidesShareOneCurve() {
        for (Curve c : Curve.values())
            for (double smooth : new double[]{0, 0.2, 1})
                for (double[] w : new double[][]{{16, 16}, {16, 0}, {48, 16}, {8, 30}}) {
                    double a = TransitionMath.share(0, w[0], w[1], 1, c, List.of(0.0, 0.1, 0.35, 0.75, 1.0), smooth, 0, 1, 1, 1);
                    double b = TransitionMath.share(0, w[1], w[0], 1, c, List.of(0.0, 0.1, 0.35, 0.75, 1.0), smooth, 0, 1, 1, 1);
                    assertEquals(1, a + b, 1e-9, c + " " + w[0] + "/" + w[1] + " smoothness " + smooth);
                }
    }

    /** Only A spreads into B: 100% A right at the boundary, fading to 0 at the zone edge; nothing of B in A. */
    @Test
    void oneWayTransitionHasNoJumpAtTheBoundary() {
        assertEquals(1, share(0, 32, 0, Curve.LINEAR), 1e-9);
        assertEquals(0.5, share(16, 32, 0, Curve.LINEAR), 1e-9);
        assertEquals(0, share(0, 0, 32, Curve.LINEAR), 1e-9);
    }

    /** An S curve over the whole transition changes fastest at the boundary (no 50/50 plateau there). */
    @Test
    void sCurveSpansTheBoundary() {
        double nearBoundary = 0.5 - share(1, 16, Curve.SMOOTHSTEP);
        double nearEdge = share(14, 16, Curve.SMOOTHSTEP) - share(15, 16, Curve.SMOOTHSTEP);
        assertTrue(nearBoundary > nearEdge);
        assertTrue(nearBoundary > 0.02);
    }

    @Test
    void logarithmicDropsFastThenTails() {
        double log = share(8, 16, Curve.LOGARITHMIC), lin = share(8, 16, Curve.LINEAR);
        assertTrue(log < lin);
        assertTrue(share(15, 16, Curve.LOGARITHMIC) > 0); // long tail still reaches the edge
        assertTrue(0.5 - share(1, 16, Curve.LOGARITHMIC) > 0.5 - share(1, 16, Curve.LINEAR)); // steep start
        assertEquals(0, share(16, 16, Curve.LOGARITHMIC), 1e-9); // never beyond the configured distance
    }

    /** Coherent noise passes opposite signs on the two sides: the shared curve shifts, the sides still agree. */
    @Test
    void warpKeepsSidesConsistentAndEdgesFixed() {
        for (Curve c : Curve.values()) {
            double a = TransitionMath.share(0, 16, 16, 1, c, List.of(), 0, 0.3, 1, 1, 1);
            double b = TransitionMath.share(0, 16, 16, 1, c, List.of(), 0, -0.3, 1, 1, 1);
            assertEquals(1, a + b, 1e-9, c.name());
            assertEquals(0, TransitionMath.share(16, 16, 16, 1, c, List.of(), 0, 0.9, 1, 1, 1), 1e-9, c.name());
        }
    }

    @Test
    void asymmetricWidthsAndInfluence() {
        assertTrue(share(8, 32, Curve.LINEAR) > share(8, 10, Curve.LINEAR));
        assertEquals(0, share(0, 0, Curve.LINEAR));
        double strongForeign = TransitionMath.share(0, 16, 16, 1, Curve.LINEAR, List.of(), 0, 0, 1, 1, 3);
        assertEquals(0.75, strongForeign, 1e-9);
    }

    @Test
    void customCurveInterpolates() {
        // half of the zone towards the boundary: point 0.25 of 1 -> a quarter of the balanced 50%
        assertEquals(0.125, TransitionMath.share(8, 16, 16, 1, Curve.CUSTOM, List.of(0.0, 0.25, 1.0), 0, 0, 1, 1, 1), 1e-9);
        assertEquals(0.3125, TransitionMath.share(4, 16, 16, 1, Curve.CUSTOM, List.of(0.0, 0.25, 1.0), 0, 0, 1, 1, 1), 1e-9);
    }

    @Test
    void bilinearColour() {
        assertEquals(0x808080, TransitionMath.bilerpRgb(0x000000, 0xFFFFFF, 0x000000, 0xFFFFFF, 0.5, 0.3) & 0xFEFEFE);
        assertEquals(0x123456, TransitionMath.bilerpRgb(0x123456, 0xFFFFFF, 0x000000, 0x000000, 0, 0));
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
