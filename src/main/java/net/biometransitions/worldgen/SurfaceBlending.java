package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.Mixing;
import net.biometransitions.core.Mixing.Channel;
import net.biometransitions.core.SurfaceRules;
import net.biometransitions.core.Transitions;
import net.biometransitions.debug.Stats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.List;

/**
 * Spreads the neighbour biome's ground into the zone in noisy patches (first decoration step, before any
 * vegetation), and the snow cover of cold neighbours in the same patches (last step, like vanilla snow).
 * <p>
 * The zone map works on the 4x4 biome grid, while the block surface was built from Minecraft's fuzzy
 * per-block biome. Every column is therefore also checked against its real biome, so a No Blend biome
 * (a river bank, a beach) never receives foreign ground even where the grid calls the column its neighbour.
 */
public final class SurfaceBlending {
    private SurfaceBlending() {}

    /** A neighbour whose ground goes into a column, or null. */
    private record Pick(Holder<Biome> foreign, List<String> blocks) {}

    public static int apply(WorldGenLevel level, ChunkGenerator gen, RandomState rs, long seed, ChunkPos cp) {
        BtgConfig cfg = BtgConfig.get();
        var s = cfg.mixing.surface;
        if (!cfg.general.surfaceMixing || s.intensity <= 0) return 0;
        int changed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++) {
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                m.set(x, top, z);
                Holder<Biome> real = level.getBiome(m);
                // Strongest neighbour whose ground differs from the ground here (a jungle between plains and desert takes the desert's sand).
                List<String> ownPair = blocksFor(real, s.blocks);
                Pick pick = pick(level, gen, rs, seed, m, real, i -> {
                    List<String> p = blocksFor(i.foreign(), s.blocks);
                    return p != null && !p.equals(ownPair) ? p : null;
                });
                if (pick == null) continue;
                BlockState topState = state(pick.blocks.get(0)), underState = state(pick.blocks.get(1));
                if (topState == null || underState == null) continue;
                for (int dy = 0; dy < s.depth; dy++) {
                    m.setY(top - dy);
                    BlockState cur = level.getBlockState(m);
                    if (!SurfaceRules.blockAllowed(cur, s.replaceable)) break;
                    level.setBlock(m, dy == 0 ? topState : underState, Block.UPDATE_CLIENTS);
                }
                changed++;
            }
        if (changed > 0) Stats.inc("surface columns blended");
        return changed;
    }

    /**
     * Snow layers of cold neighbours, in the same patches as their ground. Runs first in the top layer step,
     * where vanilla places snow; uses the neighbour's own snow check (temperature at this height, block light,
     * free spot, a block snow can rest on), so snow never lands on water, ice or in caves.
     */
    public static int snow(WorldGenLevel level, ChunkGenerator gen, RandomState rs, long seed, ChunkPos cp) {
        BtgConfig cfg = BtgConfig.get();
        var s = cfg.mixing.surface;
        if (!cfg.general.surfaceMixing || !s.snowLayers || s.intensity <= 0) return 0;
        int placed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++) {
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                m.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                Holder<Biome> real = level.getBiome(m);
                if (real.value().shouldSnow(level, m)) continue; // vanilla snows here anyway
                Pick pick = pick(level, gen, rs, seed, m, real, i -> i.foreign().value().coldEnoughToSnow(m) ? List.of() : null);
                if (pick == null || !pick.foreign.value().shouldSnow(level, m)) continue;
                level.setBlock(m, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                below.setWithOffset(m, 0, -1, 0);
                BlockState under = level.getBlockState(below);
                if (under.hasProperty(SnowyDirtBlock.SNOWY))
                    level.setBlock(below, under.setValue(SnowyDirtBlock.SNOWY, true), Block.UPDATE_CLIENTS);
                placed++;
            }
        if (placed > 0) Stats.inc("snow columns blended");
        return placed;
    }

    /**
     * The strongest neighbour that offers something for this column ({@code offer} returns null when not) and may
     * spread its surface into both the grid biome of the column and its real block biome; null when the column
     * is outside its patches.
     */
    private static Pick pick(WorldGenLevel level, ChunkGenerator gen, RandomState rs, long seed, BlockPos pos, Holder<Biome> real,
                             java.util.function.Function<Transitions.Influence, List<String>> offer) {
        var s = BtgConfig.get().mixing.surface;
        var influences = Transitions.at(gen, rs, seed, pos, null, Channel.SURFACE);
        if (influences.isEmpty()) return null;
        Transitions.Influence best = null;
        List<String> blocks = null;
        for (var i : influences) {
            if (best != null && i.share() <= best.share()) continue;
            // The real biome must accept it too: No Blend / No Surface Blend / pair directions hold for every block.
            if (!i.foreign().equals(real) && Mixing.between(real, i.foreign(), Channel.SURFACE) == null) continue;
            if (i.foreign().equals(real)) continue; // the block already shows the neighbour's own surface
            List<String> o = offer.apply(i);
            if (o == null) continue;
            best = i;
            blocks = o;
        }
        if (best == null) return null;
        // Patch noise in 0..1 against the share: big blobs near the border, scattered islands further out.
        double patch = 0.5 + 0.5 * BoundaryDetector.noise(rs, seed, pos.getX() + 7919, pos.getZ() - 7919, s.patchScale);
        return patch < best.share() * 2 * s.intensity ? new Pick(best.foreign(), blocks) : null;
    }

    private static List<String> blocksFor(Holder<Biome> biome, java.util.Map<String, List<String>> map) {
        for (var e : map.entrySet())
            if (e.getValue().size() == 2 && BiomeClassifier.match(e.getKey(), biome) > 0) return e.getValue();
        return null;
    }

    private static BlockState state(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Block b = BuiltInRegistries.BLOCK.get(rl);
        return b == Blocks.AIR ? null : b.defaultBlockState();
    }
}
