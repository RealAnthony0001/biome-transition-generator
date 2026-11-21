package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.FeatureCatalog;
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
        if (!cfg.general.enabled || !cfg.transition.distribution.suppressNative || ctx.topFeature().isEmpty()) return false;
        WorldGenLevel level = ctx.getLevel();
        var server = level.getLevel();
        if (!cfg.general.dimensions.isEmpty() && !cfg.general.dimensions.contains(server.dimension().location().toString())) return false;

        PlacedFeature pf = ctx.topFeature().get();
        var e = FeatureCatalog.entry(level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).wrapAsHolder(pf));
        if (e == null || e.category().equals("fluid")) return false; // never remove water bodies
        var own = level.getBiome(pos);
        var step = FeatureCatalog.stepOf(own, pf);
        if (step == null || FeatureCatalog.rejectFor(e, step, cfg.transition, null) != null) return false;
        long seed = level.getSeed();
        double share = 0;
        var s = cfg.transition;
        for (var i : Transitions.at(ctx.generator(), server.getChunkSource().randomState(), seed, pos, own, e.fluidChannel())) {
            if (FeatureCatalog.rejectFor(e, step, s, i.foreign()) != null) continue;
            // Only make room when the neighbour brings something of the same kind; never leave bare holes.
            if (FeatureCatalog.all(i.foreign(), step).stream().noneMatch(f -> f.category().equals(e.category())
                    && FeatureCatalog.rejectFor(f, step, s, own) == null)) continue;
            share += i.share();
        }
        boolean drop = share > 0 && TransitionMath.hash01(seed, pos.getX(), pos.getY(), pos.getZ(), e.id().hashCode()) < share;
        if (drop) Stats.inc("suppressed native");
        return drop;
    }
}
