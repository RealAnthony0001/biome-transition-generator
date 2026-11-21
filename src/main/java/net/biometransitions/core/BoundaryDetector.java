package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.TransitionMath.Shape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds biome boundaries straight from the biome source, so it works for chunks that are not
 * generated yet and is deterministic per seed. Biomes are sampled on the 4-block quart grid
 * (the resolution biomes actually have); results are cached per chunk section.
 */
public final class BoundaryDetector {
    /**
     * A foreign biome and the offset from the column to the nearest cell of it. {@code featureless} = that cell
     * lies in a small area under Feature Suppression: it still counts for surface blending, but spreads no features.
     * A biome can appear twice, once per kind of cell; the nearer entry comes first.
     */
    public record Neighbour(Holder<Biome> biome, float dx, float dz, boolean featureless) {
        /** Distance to the boundary (the near edge of that cell), in blocks. */
        public double distance(Shape shape) {
            return Math.max(0, TransitionMath.distance(shape, dx, dz) - 2);
        }
    }

    /** 16x16 columns of one chunk section: own biome + foreign neighbours sorted by distance. */
    public record ZoneMap(Holder<Biome>[] own, Neighbour[][] neighbours) {
        public Holder<Biome> own(int x, int z) { return own[(x & 15) * 16 + (z & 15)]; }
        public Neighbour[] neighbours(int x, int z) { return neighbours[(x & 15) * 16 + (z & 15)]; }
    }

    static final class LevelData {
        final ConcurrentHashMap<Long, Holder<Biome>> samples = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, ZoneMap> zones = new ConcurrentHashMap<>();
        final SimplexNoise noise;

        LevelData(long seed) { noise = new SimplexNoise(new LegacyRandomSource(seed ^ 0x5EED_B10EL)); }
    }

    // RandomState is created once per dimension, so it identifies the level's biome sampling.
    private static final Map<RandomState, LevelData> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final Neighbour[] NONE = new Neighbour[0];

    private BoundaryDetector() {}

    public static void clear() { LEVELS.clear(); }

    private static LevelData data(RandomState rs, long seed) {
        synchronized (LEVELS) {
            return LEVELS.computeIfAbsent(rs, r -> new LevelData(seed));
        }
    }

    /** Coherent noise in -1..1 for irregular zone edges. */
    public static double noise(RandomState rs, long seed, int x, int z, double scale) {
        return data(rs, seed).noise.getValue(x / scale, z / scale);
    }

    public static ZoneMap zoneMap(ChunkGenerator gen, RandomState rs, long seed, int chunkX, int sectionY, int chunkZ) {
        LevelData d = data(rs, seed);
        int cacheSize = BtgConfig.get().performance.cacheSize;
        // shortcut: whole-cache clear instead of LRU, fine while the working set fits; upgrade if hit rate matters.
        if (d.zones.size() > cacheSize) d.zones.clear();
        if (d.samples.size() > cacheSize * 64) d.samples.clear();
        return d.zones.computeIfAbsent(SectionPos.asLong(chunkX, sectionY, chunkZ), k -> compute(d, gen, rs, chunkX, sectionY, chunkZ));
    }

    public static ZoneMap zoneMap(ChunkGenerator gen, RandomState rs, long seed, BlockPos pos) {
        return zoneMap(gen, rs, seed, pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    @SuppressWarnings("unchecked")
    private static ZoneMap compute(LevelData d, ChunkGenerator gen, RandomState rs, int chunkX, int sectionY, int chunkZ) {
        BtgConfig cfg = BtgConfig.get();
        // Never look less far than the widest zone, or wide zones get cut off.
        int range = Mixing.maxWidth() + 4;
        int rc = (range + 3) / 4 + 1;                 // radius in quart cells
        int size = 4 + 2 * rc;                         // quart cells across
        int qx0 = (chunkX << 2) - rc, qz0 = (chunkZ << 2) - rc, qy = ((sectionY << 4) + 8) >> 2;
        // Full resolution up to the global mixing distance; beyond it (only wider pair/similar zones reach there)
        // every other cell on a world-aligned lattice, so all chunks see the same cells and no seams appear.
        // Deep sections (cave biomes are big blobs) use the coarse lattice everywhere but the chunk itself.
        boolean deep = (sectionY << 4) + 16 <= 48;
        int fine = deep ? 0 : (cfg.general.mixingDistance + 3) / 4 + 2;
        List<Holder<Biome>> biomes = new ArrayList<>();
        Map<Holder<Biome>, Integer> ids = new HashMap<>();
        int[] grid = new int[size * size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                int qx = qx0 + i, qz = qz0 + j;
                int out = Math.max(Math.max(rc - i, i - rc - 3), Math.max(rc - j, j - rc - 3));
                if (out > fine && ((qx | qz) & 1) != 0) {
                    grid[i * size + j] = -1;
                    continue;
                }
                Holder<Biome> b = d.samples.computeIfAbsent(BlockPos.asLong(qx, qy, qz),
                        k -> BiomeReplacer.apply(gen.getBiomeSource(), rs, gen.getBiomeSource().getNoiseBiome(qx, qy, qz, rs.sampler()), qx, qy, qz));
                grid[i * size + j] = ids.computeIfAbsent(b, x -> {
                    biomes.add(x);
                    return biomes.size() - 1;
                });
            }

        Holder<Biome>[] own = new Holder[256];
        Neighbour[][] neighbours = new Neighbour[256][];
        if (biomes.size() == 1) {
            Arrays.fill(own, biomes.get(0));
            Arrays.fill(neighbours, NONE);
            return new ZoneMap(own, neighbours);
        }

        // Scan once per 4x4 quart cell (16 per chunk, not 256 columns), then shift the offsets to each column.
        boolean[] barrier = new boolean[biomes.size()];
        boolean anyBarrier = false;
        for (int i = 0; i < barrier.length; i++) anyBarrier |= barrier[i] = BiomeClassifier.listed(cfg.mixing.barriers, biomes.get(i));
        // Cells of small areas whose own features are suppressed (Biome Replacing): they must not spread features.
        boolean[] featureless = new boolean[size * size];
        boolean[] suppressible = new boolean[biomes.size()];
        boolean anySuppressible = false;
        for (int i = 0; i < suppressible.length; i++)
            anySuppressible |= suppressible[i] = BiomeClassifier.listed(cfg.replacing.suppressFeatureBiomes, biomes.get(i));
        if (anySuppressible)
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++) {
                    int b = grid[i * size + j];
                    featureless[i * size + j] = b >= 0 && suppressible[b]
                            && BiomeReplacer.cellSuppressed(gen.getBiomeSource(), rs, qx0 + i, qz0 + j, biomes.get(b));
                }
        double rangeSq = (range + 4.0) * (range + 4.0);
        for (int cx = 0; cx < 4; cx++)
            for (int cz = 0; cz < 4; cz++) {
                int mine = grid[(rc + cx) * size + rc + cz];
                // biomes that can never mix in here would only crowd out the ones that can
                boolean[] canMix = new boolean[biomes.size()];
                for (int b = 0; b < canMix.length; b++) canMix[b] = b != mine && Mixing.any(biomes.get(mine), biomes.get(b));
                List<Neighbour> found = new ArrayList<>();
                for (int i = 0; i < size; i++)
                    for (int j = 0; j < size; j++) {
                        int b = grid[i * size + j];
                        if (b < 0 || !canMix[b]) continue;
                        float dx = (i - rc - cx) * 4, dz = (j - rc - cz) * 4;
                        if (dx * dx + dz * dz > rangeSq) continue;
                        if (anyBarrier && blocked(grid, barrier, size, rc + cx, rc + cz, i, j, mine, b)) continue;
                        keepNearest(found, new Neighbour(biomes.get(b), dx, dz, featureless[i * size + j]));
                    }
                for (int ox = 0; ox < 4; ox++)
                    for (int oz = 0; oz < 4; oz++) {
                        float sx = 1.5f - ox, sz = 1.5f - oz; // column centre -> cell centre
                        List<Neighbour> col = new ArrayList<>(found.size());
                        for (Neighbour n : found) col.add(new Neighbour(n.biome, n.dx + sx, n.dz + sz, n.featureless));
                        col.sort((p, q) -> Float.compare(p.dx * p.dx + p.dz * p.dz, q.dx * q.dx + q.dz * q.dz));
                        int n = (cx * 4 + ox) * 16 + cz * 4 + oz;
                        own[n] = biomes.get(mine);
                        neighbours[n] = col.isEmpty() ? NONE : limit(col, cfg.advanced.maxNeighbours);
                    }
            }
        return new ZoneMap(own, neighbours);
    }

    /** True when a barrier biome (e.g. a river) lies on the straight line between the two cells. */
    private static boolean blocked(int[] grid, boolean[] barrier, int size, int fi, int fj, int ti, int tj, int mine, int target) {
        int steps = Math.max(Math.abs(ti - fi), Math.abs(tj - fj));
        for (int k = 1; k < steps; k++) {
            int i = fi + Math.round((ti - fi) * (float) k / steps), j = fj + Math.round((tj - fj) * (float) k / steps);
            int b = grid[i * size + j];
            if (b >= 0 && b != mine && b != target && barrier[b]) return true;
        }
        return false;
    }

    /** The nearest entries of at most {@code max} different biomes (sorted input). */
    private static Neighbour[] limit(List<Neighbour> sorted, int max) {
        List<Holder<Biome>> seen = new ArrayList<>(max);
        List<Neighbour> out = new ArrayList<>(sorted.size());
        for (Neighbour n : sorted) {
            if (!seen.contains(n.biome)) {
                if (seen.size() >= max) continue;
                seen.add(n.biome);
            }
            out.add(n);
        }
        return out.toArray(Neighbour[]::new);
    }

    /** One entry per foreign biome and kind of cell (featureless or not): the closest cell of it. */
    private static void keepNearest(List<Neighbour> found, Neighbour n) {
        float dn = n.dx * n.dx + n.dz * n.dz;
        for (int k = 0; k < found.size(); k++) {
            Neighbour o = found.get(k);
            if (o.biome.equals(n.biome) && o.featureless == n.featureless) {
                if (dn < o.dx * o.dx + o.dz * o.dz) found.set(k, n);
                return;
            }
        }
        found.add(n);
    }
}
