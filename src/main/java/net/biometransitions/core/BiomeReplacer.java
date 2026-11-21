package net.biometransitions.core;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.biometransitions.config.BtgConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Small biome detection and Biome Replacing. An area is the connected set of quart cells (4x4 blocks)
 * matching the same list entry; its size and verdict are a property of the whole area, so every chunk
 * that touches it gets the same answer no matter which one asks first.
 */
public final class BiomeReplacer {
    /** small = below the size limit; target = replacement biome (null = keep); border = biomes around a small area. */
    record Verdict(boolean small, Holder<Biome> target, Set<Holder<Biome>> border) {}

    private static final Verdict LARGE = new Verdict(false, null, Set.of());
    // shortcut: area sizes are measured on the y=64 quart layer only and deeper layers follow it; per-layer areas need 3D floods.
    private static final int REF_QY = 16;
    /** Overlapping Detection never looks at areas bigger than this (cells). */
    private static final int OVERLAP_CAP = 4096;
    /** Replacement search reaches this many quarts beyond the area. */
    private static final int SEARCH = 32;

    private static final class Data {
        final Map<Long, Holder<Biome>> raw = new ConcurrentHashMap<>();
        final Map<String, Map<Long, Verdict>> replace = new ConcurrentHashMap<>();
        final Map<String, Map<Long, Verdict>> suppress = new ConcurrentHashMap<>();
    }

    private static final Map<RandomState, Data> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    private BiomeReplacer() {}

    public static void clear() { LEVELS.clear(); }

    private static Data data(RandomState rs) {
        synchronized (LEVELS) {
            return LEVELS.computeIfAbsent(rs, r -> new Data());
        }
    }

    private static boolean active() {
        BtgConfig cfg = BtgConfig.get();
        return cfg.general.enabled && cfg.replacing.smallBiomeDetection;
    }

    /** The biome a chunk really gets at this quart: the noise biome, or its replacement when its area is too small. */
    public static Holder<Biome> apply(BiomeSource src, RandomState rs, Holder<Biome> raw, int qx, int qy, int qz) {
        BtgConfig cfg = BtgConfig.get();
        if (!active() || !cfg.general.biomeReplacing) return raw;
        var r = cfg.replacing;
        String selector = BiomeClassifier.firstMatch(r.replaceBiomes, raw);
        if (selector == null || BiomeClassifier.listed(r.neverReplace, raw)) return raw;
        Data d = data(rs);
        Verdict v = region(d, (x, z) -> raw(d, src, rs, x, z), qx, qz, selector, r.replaceMinSize,
                d.replace.computeIfAbsent(selector, s -> new ConcurrentHashMap<>()), true);
        return v.target != null ? v.target : raw;
    }

    /**
     * The biomes around the small area this block column belongs to, when {@code own} (the biome at the block,
     * e.g. from {@code level.getBiome}) is listed in Small Biomes with Feature Suppression and that area is small;
     * null otherwise. Block biomes are looked up with Minecraft's fuzzy zoom, so a block can show the biome of
     * either quart cell around it: every candidate cell is checked, otherwise edge blocks of a small area slip through.
     */
    public static Set<Holder<Biome>> suppressedSurroundings(BiomeSource src, RandomState rs, int x, int z, Holder<Biome> own) {
        String selector = suppressSelector(own);
        if (selector == null) return null;
        int qx0 = (x - 2) >> 2, qz0 = (z - 2) >> 2;
        for (int i = 0; i <= 1; i++)
            for (int j = 0; j <= 1; j++) {
                Verdict v = suppressVerdict(src, rs, qx0 + i, qz0 + j, selector);
                if (v.small) return v.border;
            }
        return null;
    }

    /** {@link #suppressedSurroundings} != null. */
    public static boolean featuresSuppressed(BiomeSource src, RandomState rs, int x, int z, Holder<Biome> own) {
        return suppressedSurroundings(src, rs, x, z, own) != null;
    }

    /** Like {@link #featuresSuppressed}, for an exact quart cell of the biome grid (no fuzzy zoom). */
    public static boolean cellSuppressed(BiomeSource src, RandomState rs, int qx, int qz, Holder<Biome> biome) {
        String selector = suppressSelector(biome);
        return selector != null && suppressVerdict(src, rs, qx, qz, selector).small;
    }

    private static String suppressSelector(Holder<Biome> biome) {
        if (!active()) return null;
        return BiomeClassifier.firstMatch(BtgConfig.get().replacing.suppressFeatureBiomes, biome);
    }

    private static Verdict suppressVerdict(BiomeSource src, RandomState rs, int qx, int qz, String selector) {
        Data d = data(rs);
        return region(d, (x, z) -> apply(src, rs, raw(d, src, rs, x, z), x, REF_QY, z), qx, qz, selector,
                BtgConfig.get().replacing.suppressMinSize, d.suppress.computeIfAbsent(selector, s -> new ConcurrentHashMap<>()), false);
    }

    private interface Sampler { Holder<Biome> at(int qx, int qz); }

    private static Holder<Biome> raw(Data d, BiomeSource src, RandomState rs, int qx, int qz) {
        if (d.raw.size() > 1 << 20) d.raw.clear(); // shortcut: whole-cache clear, fine while the working set fits
        return d.raw.computeIfAbsent(pack(qx, qz), k -> src.getNoiseBiome(qx, REF_QY, qz, rs.sampler()));
    }

    private static long pack(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }

    private static int ux(long p) { return (int) (p >> 32); }

    private static int uz(long p) { return (int) p; }

    private static Verdict region(Data d, Sampler sample, int qx, int qz, String selector, int minSize,
                                  Map<Long, Verdict> cache, boolean replace) {
        Verdict cached = cache.get(pack(qx, qz));
        if (cached != null) return cached;
        Holder<Biome> start = sample.at(qx, qz);
        if (BiomeClassifier.match(selector, start) < 0) return LARGE;

        boolean overlap = minSize < 0;
        int cap = overlap ? OVERLAP_CAP : minSize - 1; // small = fewer cells than minSize
        LongArrayList cells = new LongArrayList();
        LongOpenHashSet in = new LongOpenHashSet();
        Map<Holder<Biome>, Integer> border = new HashMap<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long first = pack(qx, qz);
        in.add(first);
        cells.add(first);
        queue.enqueue(first);
        boolean large = cap < 1;
        while (!queue.isEmpty() && !large) {
            long c = queue.dequeueLong();
            for (int k = 0; k < 4; k++) {
                int nx = ux(c) + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = uz(c) + (k == 2 ? 1 : k == 3 ? -1 : 0);
                long n = pack(nx, nz);
                if (in.contains(n)) continue;
                Holder<Biome> b = sample.at(nx, nz);
                if (BiomeClassifier.match(selector, b) >= 0) {
                    in.add(n);
                    cells.add(n);
                    if (cells.size() > cap) { large = true; break; }
                    queue.enqueue(n);
                } else border.merge(b, 1, Integer::sum);
            }
        }
        Verdict v;
        if (large) v = LARGE;
        else if (overlap && maxDepth(cells, in) * 4 > BtgConfig.get().general.mixingDistance * BtgConfig.get().replacing.overlapMultiplier) v = LARGE;
        else v = new Verdict(true, replace ? target(sample, cells, in, border, start) : null, Set.copyOf(border.keySet()));
        if (v.small) net.biometransitions.debug.Stats.inc(v.target != null ? "small areas replaced" : replace ? "small areas kept (no target found)" : "small areas without own features");
        for (long c : cells) cache.put(c, v);
        if (cache.size() > 1 << 20) cache.clear();
        return v;
    }

    /** Largest distance (in cells) from any cell of the area to its edge. */
    private static int maxDepth(LongArrayList cells, LongOpenHashSet in) {
        Map<Long, Integer> depth = new HashMap<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long c : cells) {
            boolean edge = false;
            for (int k = 0; k < 4 && !edge; k++)
                edge = !in.contains(pack(ux(c) + (k == 0 ? 1 : k == 1 ? -1 : 0), uz(c) + (k == 2 ? 1 : k == 3 ? -1 : 0)));
            if (edge) {
                depth.put(c, 1);
                queue.enqueue(c);
            }
        }
        int max = 1;
        while (!queue.isEmpty()) {
            long c = queue.dequeueLong();
            int dc = depth.get(c);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long n = pack(ux(c) + dx, uz(c) + dz);
                    if (!in.contains(n) || depth.containsKey(n)) continue;
                    depth.put(n, dc + 1);
                    max = Math.max(max, dc + 1);
                    queue.enqueue(n);
                }
        }
        return max;
    }

    /** Replacement for a small area: a matching biome bordering it, else the nearest one further out, else null. */
    private static Holder<Biome> target(Sampler sample, LongArrayList cells, LongOpenHashSet in,
                                        Map<Holder<Biome>, Integer> border, Holder<Biome> original) {
        var r = BtgConfig.get().replacing;
        Predicate<Holder<Biome>> ok;
        switch (r.changeInto) {
            case NEAREST_SAME_GROUP -> ok = b -> BiomeClassifier.shareGroup(b, original);
            case NEAREST_SIMILAR_GROUP -> {
                // the bordering biome with the closest climate; borders always exist for a small area
                Holder<Biome> best = null;
                double bestDiff = Double.MAX_VALUE;
                for (var b : border.keySet()) {
                    double diff = 1 - Transitions.climateFactor(original, b, 1);
                    if (best == null || diff < bestDiff || diff == bestDiff && BiomeClassifier.id(b).compareTo(BiomeClassifier.id(best)) < 0) {
                        best = b;
                        bestDiff = diff;
                    }
                }
                return best;
            }
            default -> {
                String to = null;
                for (String[] m : Expect.mapping(r.customMapping))
                    if (BiomeClassifier.match(m[0], original) >= 0) { to = m[1]; break; }
                if (to == null) return null;
                String target = to;
                ok = b -> BiomeClassifier.match(target, b) >= 0;
            }
        }
        // 1. bordering biomes, most shared edge first
        Holder<Biome> best = null;
        int bestCount = -1;
        for (var e : border.entrySet())
            if (ok.test(e.getKey()) && (e.getValue() > bestCount || e.getValue() == bestCount
                    && BiomeClassifier.id(e.getKey()).compareTo(BiomeClassifier.id(best)) < 0)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        if (best != null) return best;
        // 2. rings around the area's bounding box, nearest to its centre first
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long c : cells) {
            minX = Math.min(minX, ux(c));
            maxX = Math.max(maxX, ux(c));
            minZ = Math.min(minZ, uz(c));
            maxZ = Math.max(maxZ, uz(c));
        }
        double cx = (minX + maxX) / 2.0, cz = (minZ + maxZ) / 2.0;
        for (int k = 2; k <= SEARCH; k += 2) {
            Holder<Biome> found = null;
            double foundDist = Double.MAX_VALUE;
            for (int x = minX - k; x <= maxX + k; x++)
                for (int z = minZ - k; z <= maxZ + k; z++) {
                    int out = Math.max(Math.max(minX - x, x - maxX), Math.max(minZ - z, z - maxZ));
                    if (out <= k - 2 || ((x + z) & 1) != 0 || in.contains(pack(x, z))) continue; // this ring, every other cell
                    Holder<Biome> b = sample.at(x, z);
                    double dist = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                    if (ok.test(b) && (dist < foundDist || dist == foundDist && BiomeClassifier.id(b).compareTo(BiomeClassifier.id(found)) < 0)) {
                        found = b;
                        foundDist = dist;
                    }
                }
            if (found != null) return found;
        }
        return null;
    }
}
