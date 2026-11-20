package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.MultiBiomeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.List;

/**
 * How strongly each neighbouring biome's features reach a given position. One universal rule set for
 * every pair; the zone width adapts to how different the two climates are.
 */
public final class Transitions {
    public record Influence(Holder<Biome> foreign, double share, double distance, double width) {}

    private Transitions() {}

    /**
     * @param own   biome at the position (null = take it from the detector)
     * @param fluid fluid channel (separate width/intensity) instead of the vegetation channel
     */
    public static List<Influence> at(ChunkGenerator gen, RandomState rs, long seed, BlockPos pos, Holder<Biome> own, boolean fluid) {
        BtgConfig cfg = BtgConfig.get();
        var s = cfg.transition;
        var dist = s.distribution;
        var zone = BoundaryDetector.zoneMap(gen, rs, seed, pos);
        if (own == null) own = zone.own(pos.getX(), pos.getZ());
        List<Influence> out = new ArrayList<>(2);
        double total = 0;
        for (var n : zone.neighbours(pos.getX(), pos.getZ())) {
            if (n.biome().equals(own)) continue;
            double d = n.distance(s.zone.shape);
            double width = (fluid ? s.fluid.width : s.zone.width) * climateFactor(own, n.biome(), s.zone.climateAdaptation);
            double intensity = fluid ? s.fluid.intensity : s.zone.intensity;
            double noise = dist.noiseInfluence > 0 ? BoundaryDetector.noise(rs, seed, pos.getX(), pos.getZ(), dist.noiseScale) : 0;
            double jitter = TransitionMath.hash01(seed, pos.getX(), 0, pos.getZ(), 0x7A11) * 2 - 1;
            double share = TransitionMath.foreignShare(d, width, s.zone.falloff, dist.curve, dist.customCurve,
                    dist.smoothness, dist.randomVariation, jitter, dist.noiseInfluence, noise,
                    intensity, dist.ownInfluence, dist.neighbourInfluence);
            if (share > 0) {
                out.add(new Influence(n.biome(), share, d, width));
                total += share;
            }
            if (cfg.detection.multiBiomeMode == MultiBiomeMode.NEAREST_PAIR) break;
        }
        // Several neighbours together may not push the own biome below zero.
        final double sum = total;
        if (sum > 1) out.replaceAll(i -> new Influence(i.foreign, i.share / sum, i.distance, i.width));
        return out;
    }

    public static double climateFactor(Holder<Biome> a, Holder<Biome> b, double adaptation) {
        if (adaptation <= 0) return 1;
        var ca = a.value().getModifiedClimateSettings();
        var cb = b.value().getModifiedClimateSettings();
        return TransitionMath.climateWidthFactor(ca.temperature(), ca.downfall(), cb.temperature(), cb.downfall(), adaptation);
    }

    public static double shareOf(List<Influence> list, Holder<Biome> foreign) {
        for (var i : list) if (i.foreign.equals(foreign)) return i.share;
        return 0;
    }
}
