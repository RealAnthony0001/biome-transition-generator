package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeReplacer;
import net.biometransitions.core.FeatureCatalog;
import net.biometransitions.core.Mixing;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.Transitions;
import net.biometransitions.debug.Stats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/**
 * Thins out a biome's own features inside the zone with the same share the foreign features come in
 * with, so the mix really shifts from A to B instead of only adding B on top.
 */
public final class Suppression {
    private Suppression() {}

    public static boolean suppress(PlacementContext ctx, BlockPos pos) {
        BtgConfig cfg = BtgConfig.get();
        if (!cfg.general.enabled || ctx.topFeature().isEmpty()) return false;
        WorldGenLevel level = ctx.getLevel();
        var server = level.getLevel();
        if (!cfg.general.dimensions.isEmpty() && !cfg.general.dimensions.contains(server.dimension().location().toString())) return false;

        PlacedFeature pf = ctx.topFeature().get();
        var e = FeatureCatalog.entry(level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).wrapAsHolder(pf));
        if (e == null || e.category().equals("fluid")) return false; // never remove water bodies
        var own = level.getBiome(pos);
        var step = FeatureCatalog.stepOf(own, pf);
        // Ores, structures and other features that never mix are never thinned either.
        if (step == null || FeatureCatalog.rejectFor(e, step, null) != null) return false;
        var gen = ctx.generator();
        var rs = server.getChunkSource().randomState();
        if (BiomeReplacer.featuresSuppressed(gen.getBiomeSource(), rs, pos.getX(), pos.getZ(), own)) {
            Stats.inc("suppressed native: small biome");
            return true;
        }
        boolean extreme = cfg.debug.extremePreset;
        if (!cfg.mixing.distribution.suppressNative && !extreme) return false;
        long seed = level.getSeed();
        var influences = Transitions.at(gen, rs, seed, pos, own, e.fluidChannel());
        if (extreme && influences.isEmpty()) { // debug: nothing of its own outside transitions
            Stats.inc("suppressed native: extreme preset");
            return true;
        }
        if (!cfg.mixing.distribution.suppressNative) return false;
        double share = 0, keep = 0;
        for (var i : influences) {
            if (FeatureCatalog.rejectFor(e, step, i.foreign()) != null) continue;
            // Only make room when the neighbour brings something of the same kind; never leave bare holes.
            if (FeatureCatalog.all(i.foreign(), step).stream().noneMatch(f -> f.category().equals(e.category())
                    && FeatureCatalog.rejectFor(f, step, own) == null)) continue;
            share += i.share();
            keep += i.share() * Mixing.nativeKeepFactor(e, step, own, i.foreign(), i.rule());
        }
        if (share <= 0) return false;
        // gradual: keep (1 - share); average: additionally scale towards the average density of both biomes
        double drop = 1 - (1 - Math.min(1, share)) * (keep / share);
        boolean dropped = TransitionMath.hash01(seed, pos.getX(), pos.getY(), pos.getZ(), e.id().hashCode()) < drop;
        if (dropped) Stats.inc("suppressed native");
        return dropped;
    }
}
