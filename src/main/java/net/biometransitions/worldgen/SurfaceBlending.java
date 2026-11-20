package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.SurfaceRules;
import net.biometransitions.core.TransitionMath;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.List;

/** Spreads the neighbour biome's ground into the zone in noisy patches. Runs before any vegetation. */
public final class SurfaceBlending {
    private SurfaceBlending() {}

    public static int apply(WorldGenLevel level, ChunkGenerator gen, RandomState rs, long seed, ChunkPos cp) {
        var s = BtgConfig.get().transition.surface;
        if (!s.enabled || s.intensity <= 0) return 0;
        int changed = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++) {
                int x = cp.getMinBlockX() + lx, z = cp.getMinBlockZ() + lz;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                m.set(x, top, z);
                var influences = Transitions.at(gen, rs, seed, m, null, false);
                if (influences.isEmpty()) continue;
                // Strongest neighbour whose ground differs from ours (a jungle between plains and desert takes the desert's sand).
                Holder<Biome> own = BoundaryDetector.zoneMap(gen, rs, seed, m).own(x, z);
                List<String> ownPair = blocksFor(own, s.blocks);
                Transitions.Influence best = null;
                List<String> pair = null;
                for (var i : influences) {
                    List<String> p = blocksFor(i.foreign(), s.blocks);
                    if (p != null && !p.equals(ownPair) && (best == null || i.share() > best.share())) { best = i; pair = p; }
                }
                if (best == null) continue;
                // Patch noise in 0..1 against the share: big blobs near the border, scattered islands further out.
                double patch = 0.5 + 0.5 * BoundaryDetector.noise(rs, seed, x + 7919, z - 7919, s.patchScale);
                if (patch >= best.share() * 2 * s.intensity) continue;
                BlockState topState = state(pair.get(0)), underState = state(pair.get(1));
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
