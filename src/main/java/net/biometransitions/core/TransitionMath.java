package net.biometransitions.core;

import java.util.List;

/** Pure math of the transition zone. No Minecraft classes, so it is unit-testable. */
public final class TransitionMath {
    private TransitionMath() {}

    public enum Curve { LINEAR, SMOOTHSTEP, EASE_IN, EASE_OUT, EASE_IN_OUT, CUSTOM, NOISE }

    /** Distance metric of the zone edge: ROUND = circular, SQUARE = chessboard, DIAMOND = manhattan. */
    public enum Shape { ROUND, SQUARE, DIAMOND }

    public static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    public static double smoothstep(double t) { return t * t * (3 - 2 * t); }

    public static double distance(Shape shape, double dx, double dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        return switch (shape) {
            case ROUND -> Math.sqrt(dx * dx + dz * dz);
            case SQUARE -> Math.max(dx, dz);
            case DIAMOND -> dx + dz;
        };
    }

    public static double curve(Curve c, double t, List<Double> custom) {
        t = clamp01(t);
        return switch (c) {
            case LINEAR, NOISE -> t;
            case SMOOTHSTEP -> smoothstep(t);
            case EASE_IN -> t * t;
            case EASE_OUT -> 1 - (1 - t) * (1 - t);
            case EASE_IN_OUT -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            case CUSTOM -> piecewise(custom, t);
        };
    }

    /** Evenly spaced control points over 0..1, linearly interpolated. */
    static double piecewise(List<Double> pts, double t) {
        if (pts == null || pts.isEmpty()) return t;
        if (pts.size() == 1) return clamp01(pts.get(0));
        double f = t * (pts.size() - 1);
        int i = Math.min((int) f, pts.size() - 2);
        return clamp01(pts.get(i) + (pts.get(i + 1) - pts.get(i)) * (f - i));
    }

    /**
     * Share (0..1) of the foreign biome's features at distance {@code d} from the boundary,
     * on a side whose zone is {@code width} blocks wide. 0.5 at the boundary with neutral settings,
     * falling to 0 at the zone edge, so both sides meet in a balanced mix.
     *
     * @param noise  coherent noise in -1..1 (irregular edges)
     * @param jitter per-column random in -1..1
     */
    public static double foreignShare(double d, double width, double falloff, Curve curve, List<Double> custom,
                                      double smoothness, double variation, double jitter,
                                      double noiseInfluence, double noise,
                                      double intensity, double ownInfluence, double foreignInfluence) {
        if (width <= 0 || intensity <= 0) return 0;
        double t = 1 - d / width;
        t += noiseInfluence * noise * (curve == Curve.NOISE ? 2 : 1) + variation * jitter;
        if (t <= 0) return 0;
        t = Math.pow(Math.min(1, t), Math.max(0.01, falloff));
        double v = curve(curve, t, custom);
        v += (smoothstep(v) - v) * clamp01(smoothness);
        double inf = ownInfluence + foreignInfluence;
        double balance = inf <= 0 ? 0 : 2 * foreignInfluence / inf;
        return clamp01(0.5 * v * intensity * balance);
    }

    /**
     * Zone width multiplier from how different two climates are (vanilla temperature ~ -0.7..2, downfall 0..1).
     * Identical climates keep the full width; at full adaptation the most different pairs drop to 25%.
     */
    public static double climateWidthFactor(double tempA, double rainA, double tempB, double rainB, double adaptation) {
        double difference = clamp01(Math.abs(tempA - tempB) / 1.5 + Math.abs(rainA - rainB));
        return 1 - clamp01(adaptation) * difference * 0.75;
    }

    /** Efraimidis–Spirakis key: sorting by it descending is weighted random sampling without replacement. */
    public static double weightedKey(double u, double weight) {
        return weight <= 0 ? -1 : Math.pow(u, 1 / weight);
    }

    /** Deterministic hash of a position to 0..1. */
    public static double hash01(long seed, int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
