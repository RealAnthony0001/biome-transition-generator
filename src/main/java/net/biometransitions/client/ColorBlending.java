package net.biometransitions.client;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.TransitionMath;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Biome Color Blending: grass, foliage and water colours (and any other block tint going through the
 * level's tint caches) blend over up to 64 blocks.
 * <p>
 * Colours are averaged with a tent weight (closer = stronger) at nodes on the world-aligned 4x4 biome grid,
 * from samples on that same grid, and every block interpolates bilinearly between the four nodes around it.
 * The result is a smooth gradient without per-block noise; each node is computed once and shared by 16
 * columns, so wide blending stays cheaper than vanilla's per-block averaging.
 * <p>
 * Samples in chunks that are not loaded yet are left out (they would read as plains). Sections whose
 * colours were computed that way are remembered and re-rendered once the missing chunks arrive.
 */
public final class ColorBlending {
    private static final int MAX_NODES = 1 << 18;
    private static final int NO_DATA = -1; // colours are 24-bit, so -1 never collides

    private static final Map<ColorResolver, Map<Long, Integer>> NODES = new ConcurrentHashMap<>();
    /** Chunk -> section ys whose tints used incomplete data. */
    private static final Map<Long, Set<Integer>> PROVISIONAL = new ConcurrentHashMap<>();
    private static volatile WeakReference<ClientLevel> owner = new WeakReference<>(null);
    private static volatile BtgConfig configOf;

    private ColorBlending() {}

    /** Largest blending distance of the current settings, 0 = vanilla blending. */
    static int radius(BtgConfig cfg) {
        if (!cfg.general.enabled) return 0;
        int r = cfg.general.colorBlendDistance;
        if (cfg.colors.sameAsMixingDistance) r = Math.min(r, cfg.general.mixingDistance);
        return Math.max(0, r);
    }

    /** Blended colour, or null to let vanilla do it (feature off or distance 0). */
    public static Integer tint(ClientLevel level, BlockPos pos, ColorResolver resolver) {
        BtgConfig cfg = BtgConfig.get();
        int r = radius(cfg);
        if (r <= 0) return null;
        sync(level, cfg);
        // Node (qx, qz) sits at the centre of quart cell qx, i.e. block coordinate 4 * qx + 2.
        double fx = (pos.getX() - 1.5) / 4.0, fz = (pos.getZ() - 1.5) / 4.0;
        int qx = Mth.floor(fx), qz = Mth.floor(fz), qy = QuartPos.fromBlock(pos.getY());
        boolean[] complete = {true};
        int c00 = node(level, cfg, r, resolver, qx, qy, qz, complete), c10 = node(level, cfg, r, resolver, qx + 1, qy, qz, complete);
        int c01 = node(level, cfg, r, resolver, qx, qy, qz + 1, complete), c11 = node(level, cfg, r, resolver, qx + 1, qy, qz + 1, complete);
        if (!complete[0]) {
            int sy = pos.getY() >> 4;
            // atomic with the removal in chunkLoaded, so no mark gets lost in a set that was just taken out
            PROVISIONAL.compute(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), (k, ys) -> {
                Set<Integer> out = ys != null ? ys : ConcurrentHashMap.newKeySet();
                out.add(sy);
                return out;
            });
        }
        // A node without any loaded sample borrows from the others.
        int any = c00 != NO_DATA ? c00 : c10 != NO_DATA ? c10 : c01 != NO_DATA ? c01 : c11;
        if (any == NO_DATA) return null;
        return TransitionMath.bilerpRgb(c00 == NO_DATA ? any : c00, c10 == NO_DATA ? any : c10,
                c01 == NO_DATA ? any : c01, c11 == NO_DATA ? any : c11, Mth.frac(fx), Mth.frac(fz));
    }

    private static int node(ClientLevel level, BtgConfig cfg, int r, ColorResolver resolver, int qx, int qy, int qz, boolean[] complete) {
        Map<Long, Integer> cache = NODES.computeIfAbsent(resolver, k -> new ConcurrentHashMap<>());
        long key = BlockPos.asLong(qx, qy, qz);
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        var chunks = level.getChunkSource();
        if (chunks.hasChunk(QuartPos.toSection(qx), QuartPos.toSection(qz))) {
            Holder<Biome> centre = level.getNoiseBiome(qx, qy, qz);
            if (!cfg.colors.reducedBiomes.isEmpty() && BiomeClassifier.listed(cfg.colors.reducedBiomes, centre))
                r = Math.min(r, cfg.colors.reducedDistance);
        }
        int cells = r >> 2;
        double red = 0, green = 0, blue = 0, total = 0;
        boolean full = true;
        for (int i = -cells; i <= cells; i++)
            for (int j = -cells; j <= cells; j++) {
                int sx = qx + i, sz = qz + j;
                if (!chunks.hasChunk(QuartPos.toSection(sx), QuartPos.toSection(sz))) {
                    full = false;
                    continue;
                }
                double w = (r + 1 - 4 * Math.abs(i)) * (double) (r + 1 - 4 * Math.abs(j));
                int c = resolver.getColor(level.getNoiseBiome(sx, qy, sz).value(), QuartPos.toBlock(sx) + 2, QuartPos.toBlock(sz) + 2);
                red += w * (c >> 16 & 255);
                green += w * (c >> 8 & 255);
                blue += w * (c & 255);
                total += w;
            }
        if (!full) complete[0] = false;
        if (total <= 0) return NO_DATA;
        int color = (int) Math.round(red / total) << 16 | (int) Math.round(green / total) << 8 | (int) Math.round(blue / total);
        if (full) {
            if (cache.size() > MAX_NODES) cache.clear(); // shortcut: whole-cache clear, fine while the working set fits
            cache.put(key, color);
        }
        return color;
    }

    /** New level or changed settings: cached nodes no longer apply. */
    private static void sync(ClientLevel level, BtgConfig cfg) {
        if (owner.get() != level || configOf != cfg) {
            synchronized (ColorBlending.class) {
                if (owner.get() != level || configOf != cfg) {
                    NODES.clear();
                    PROVISIONAL.clear();
                    owner = new WeakReference<>(level);
                    configOf = cfg;
                }
            }
        }
    }

    /**
     * A chunk arrived on the client: sections near it whose colours were computed without it are re-rendered
     * with fresh tints. Vanilla only refreshes the chunks right next to it, enough for its own 7-block blending.
     */
    public static void chunkLoaded(ClientLevel level, ChunkPos pos, Collection<BlockTintCache> tintCaches, LevelRenderer renderer) {
        int r = radius(BtgConfig.get());
        if (r <= 0 || PROVISIONAL.isEmpty() || owner.get() != level) return;
        int reach = (r + 6 + 15) >> 4; // samples reach r blocks from a node, nodes lie within 4 blocks of the block
        for (int x = pos.x - reach; x <= pos.x + reach; x++)
            for (int z = pos.z - reach; z <= pos.z + reach; z++) {
                Set<Integer> ys = PROVISIONAL.remove(ChunkPos.asLong(x, z));
                if (ys == null) continue;
                for (BlockTintCache cache : tintCaches) cache.invalidateForChunk(x, z);
                for (int y : ys) renderer.setSectionDirty(x, y, z);
            }
    }

    /** Settings changed in the config screen: drop all cached colours and redraw. */
    public static void refresh() {
        NODES.clear();
        PROVISIONAL.clear();
    }
}
