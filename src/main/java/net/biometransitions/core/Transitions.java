package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.IntersectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.List;

/**
 * How strongly each neighbouring biome's features reach a given position: Biome Mixing rules decide
 * whether and how far, the mixing distribution decides how the share falls off.
 */
public final class Transitions {
    public record Influence(Holder<Biome> foreign, double share, double distance, double width, Mixing.Rule rule) {}

    private Transitions() {}

    /**
     * @param own   biome at the position (null = take it from the detector)
     * @param fluid fluid channel (separate width/intensity) instead of the vegetation channel
     */
    public static List<Influence> at(ChunkGenerator gen, RandomState rs, long seed, BlockPos pos, Holder<Biome> own, boolean fluid) {
        BtgConfig cfg = BtgConfig.get();
        var dist = cfg.mixing.distribution;
        var zone = BoundaryDetector.zoneMap(gen, rs, seed, pos);
        var ns = zone.neighbours(pos.getX(), pos.getZ());
        if (ns.length == 0) return List.of();
        if (own == null) own = zone.own(pos.getX(), pos.getZ());
        double noise = dist.noiseInfluence > 0 ? BoundaryDetector.noise(rs, seed, pos.getX(), pos.getZ(), dist.noiseScale) : 0;
        double jitter = TransitionMath.hash01(seed, pos.getX(), 0, pos.getZ(), 0x7A11) * 2 - 1;
        double intensity = fluid ? cfg.advanced.fluid.intensity : dist.intensity;
        List<Influence> out = new ArrayList<>(2);
        double total = 0;
        for (var n : ns) {
            Mixing.Rule rule = Mixing.between(own, n.biome(), fluid);
            if (rule == null) continue;
            double d = n.distance(dist.shape);
            double share = TransitionMath.foreignShare(d, rule.width(), dist.falloff, rule.curve(), dist.customCurve,
                    dist.smoothness, dist.randomVariation, jitter, dist.noiseInfluence, noise,
                    intensity, dist.ownInfluence, dist.neighbourInfluence);
            if (share > 0) {
                out.add(new Influence(n.biome(), share, d, rule.width(), rule));
                total += share;
            }
        }
        if (out.isEmpty()) return out;
        if (cfg.mixing.intersectionPolicy == IntersectionPolicy.DO_NOT_BLEND && out.size() >= 2) return List.of();
        // A small biome without features of its own is filled entirely by its neighbours.
        boolean fill = BiomeReplacer.featuresSuppressed(gen.getBiomeSource(), rs, pos.getX(), pos.getZ(), own);
        // Several neighbours together may not push the own biome below zero.
        final double sum = total;
        if (sum > 1 || fill) out.replaceAll(i -> new Influence(i.foreign, i.share / sum, i.distance, i.width, i.rule));
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
