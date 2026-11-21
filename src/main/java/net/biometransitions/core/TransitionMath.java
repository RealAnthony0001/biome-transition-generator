package net.biometransitions.core;

import java.util.List;

/** Pure math of the transition zone. No Minecraft classes, so it is unit-testable. */
public final class TransitionMath {
    private TransitionMath() {}

    public enum Curve { LINEAR, SMOOTHSTEP, EASE_IN, EASE_OUT, EASE_IN_OUT, LOGARITHMIC, CUSTOM, NOISE }

    /** Distance metric of the zone edge: ROUND = circular, SQUARE = chessboard, DIAMOND = manhattan. */
    public enum Shape { ROUND, SQUARE, DIAMOND }

    /** Steepness of the LOGARITHMIC curve: how sharply the neighbour's share drops right at the boundary. */
    static final double LOG_STEEPNESS = 20;

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

    /**
     * The one distribution curve of a whole transition. {@code u} runs across the complete transition area:
     * 0 = the far edge of the zone inside this biome, 1 = the far edge of the zone inside the neighbour.
     * The result is the neighbour's share of the features there.
     * <p>
     * Every curve is point-symmetric ({@code profile(1 - u) = 1 - profile(u)}), so the two biomes of a
     * boundary evaluate the very same curve from opposite ends and always agree on the mix at every spot.
     */
    public static double profile(Curve c, double u, List<Double> custom) {
        u = clamp01(u);
        return switch (c) {
            case LINEAR, NOISE -> u;
            // S shapes spanning the whole transition: soft at both outer edges, fastest change at the boundary
            case SMOOTHSTEP -> smoothstep(u);
            case EASE_IN_OUT -> u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2;
            // shapes defined by how the neighbour fades with distance from the boundary
            default -> u <= 0.5 ? 0.5 * decay(c, 2 * u, custom) : 1 - 0.5 * decay(c, 2 * (1 - u), custom);
        };
    }

    /** Neighbour share relative to the balanced mix at the boundary: x = 0 at the zone edge, 1 at the boundary. */
    static double decay(Curve c, double x, List<Double> custom) {
        x = clamp01(x);
        return switch (c) {
            case EASE_IN -> x * x;
            case EASE_OUT -> 1 - (1 - x) * (1 - x);
            case LOGARITHMIC -> 1 - Math.log1p(LOG_STEEPNESS * (1 - x)) / Math.log1p(LOG_STEEPNESS);
            case CUSTOM -> {
                // the last point is the boundary; scaled to 1 so both sides still meet without a jump
                double end = piecewise(custom, 1);
                yield end <= 0 ? 0 : clamp01(piecewise(custom, x) / end);
            }
            default -> x;
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
     * Share (0..1) of a neighbour's features at distance {@code d} from the boundary, inside this biome.
     * <p>
     * The transition is one area from the far edge of the zone in this biome ({@code wIn} blocks from the
     * boundary) to the far edge of the zone in the neighbour ({@code wOut} blocks on the other side), and one
     * {@link #profile curve} spans all of it. Both biomes call this with their widths swapped and get matching
     * values: 50/50 at the boundary when both reach equally far; when only the neighbour may spread
     * ({@code wOut = 0}) it is 100% neighbour at the boundary, fading to 0 at the zone edge.
     *
     * @param wIn  how far the neighbour reaches into this biome (0 = not at all)
     * @param wOut how far this biome reaches into the neighbour (0 = not at all)
     * @param warp shift of the mix inside the transition (noise, randomness); positive = more neighbour.
     *             For coherent noise the two biomes must pass opposite signs. The zone edges never move, so
     *             nothing reaches further than the configured distance.
     */
    public static double share(double d, double wIn, double wOut, double falloff, Curve curve, List<Double> custom,
                               double smoothness, double warp, double intensity, double ownInfluence, double foreignInfluence) {
        if (wIn <= 0 || intensity <= 0 || d >= wIn) return 0;
        double total = wIn + Math.max(0, wOut);
        double t = Math.pow(1 - Math.max(0, d) / wIn, Math.max(0.01, falloff)); // 1 at the boundary, 0 at the zone edge
        double u = wIn / total * t;                                                // position across the whole transition
        u = clamp01(u + warp * 2 * Math.min(u, 1 - u));
        double v = profile(curve, u, custom);
        v += (smoothstep(v) - v) * clamp01(smoothness);
        double inf = ownInfluence + foreignInfluence;
        double balance = inf <= 0 ? 0 : 2 * foreignInfluence / inf;
        return clamp01(v * intensity * balance);
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

    /** Bilinear blend of four packed RGB colours; tx, tz in 0..1. */
    public static int bilerpRgb(int c00, int c10, int c01, int c11, double tx, double tz) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double a = (c00 >> shift & 255) * (1 - tx) + (c10 >> shift & 255) * tx;
            double b = (c01 >> shift & 255) * (1 - tx) + (c11 >> shift & 255) * tx;
            out |= (int) Math.round(a * (1 - tz) + b * tz) << shift;
        }
        return out;
    }
}
