package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeReplacer;
import net.biometransitions.core.FeatureCatalog;
import net.biometransitions.core.Mixing;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.Transitions;
import net.biometransitions.debug.Stats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.Set;

/**
 * Hooked into the vanilla biome filter, which every biome feature passes at its final position, in every
 * chunk (also features started in a neighbouring chunk). Two jobs:
 * <ol>
 * <li>Small Biomes with Feature Suppression: the biome's own features do not generate in its small areas.</li>
 * <li>Thins out a biome's own features inside the zone with the same share the foreign features come in
 * with, so the mix really shifts from A to B instead of only adding B on top.</li>
 * </ol>
 */
public final class Suppression {
    private Suppression() {}

    /**
     * True when {@code biome} at this column is a small area under Feature Suppression and its own copy of the
     * feature does not generate there. Everything that is the small biome's own goes, whether it could ever mix
     * or not. Kept: excluded feature types (ores, snow, dungeons... are world features, some act on the whole
     * chunk) and features the surrounding biomes have too, which are not this biome's own and would leave holes.
     */
    public static boolean ownCopySuppressed(BiomeSource src, RandomState rs, BlockPos pos, Holder<Biome> biome, PlacedFeature pf, String type) {
        Set<Holder<Biome>> surroundings = BiomeReplacer.suppressedSurroundings(src, rs, pos.getX(), pos.getZ(), biome);
        if (surroundings == null || BtgConfig.get().features.excludedFeatureTypes.contains(type)) return false;
        return surroundings.stream().noneMatch(b -> b.value().getGenerationSettings().hasFeature(pf));
    }

    public static boolean suppress(PlacementContext ctx, BlockPos pos) {
        BtgConfig cfg = BtgConfig.get();
        if (!cfg.general.enabled || ctx.topFeature().isEmpty()) return false;
        WorldGenLevel level = ctx.getLevel();
        var server = level.getLevel();
        if (!cfg.general.dimensions.isEmpty() && !cfg.general.dimensions.contains(server.dimension().location().toString())) return false;

        PlacedFeature pf = ctx.topFeature().get();
        var e = FeatureCatalog.entry(level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).wrapAsHolder(pf));
        if (e == null) return false;
        // The same biome the vanilla filter just accepted (fuzzy per-block biome).
        var own = level.getBiome(pos);
        var step = FeatureCatalog.stepOf(own, pf);
        if (step == null) return false;
        var gen = ctx.generator();
        var rs = server.getChunkSource().randomState();

        if (BiomeReplacer.featuresSuppressed(gen.getBiomeSource(), rs, pos.getX(), pos.getZ(), own)) {
            if (ownCopySuppressed(gen.getBiomeSource(), rs, pos, own, pf, e.type())) {
                Stats.inc("suppressed native: small biome");
                return true;
            }
            return false; // shared features stay at their normal rate; the neighbours fill in the rest
        }

        if (e.category().equals("fluid")) return false; // never thin out water bodies
        // Ores, structures and other features that never mix are never thinned either.
        if (FeatureCatalog.rejectFor(e, step, null) != null) return false;
        boolean extreme = cfg.debug.extremePreset;
        if (!cfg.mixing.distribution.suppressNative && !extreme) return false;
        long seed = level.getSeed();
        var influences = Transitions.at(gen, rs, seed, pos, own, Mixing.Channel.of(e));
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
