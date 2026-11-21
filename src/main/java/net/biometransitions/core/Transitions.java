package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.IntersectionPolicy;
import net.biometransitions.core.Mixing.Channel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.List;

/**
 * How strongly each neighbouring biome reaches a given position: Biome Mixing rules decide whether and how
 * far, the mixing distribution decides how the share falls off. Both biomes of a boundary evaluate the same
 * curve over the whole transition (see {@link TransitionMath#share}), so their mixes always fit together.
 */
public final class Transitions {
    public record Influence(Holder<Biome> foreign, double share, double distance, double width, Mixing.Rule rule) {}

    private Transitions() {}

    /**
     * @param own     biome at the position (null = take it from the detector)
     * @param channel what is mixed: features, trees, water features or surface blocks
     */
    public static List<Influence> at(ChunkGenerator gen, RandomState rs, long seed, BlockPos pos, Holder<Biome> own, Channel channel) {
        BtgConfig cfg = BtgConfig.get();
        var dist = cfg.mixing.distribution;
        var zone = BoundaryDetector.zoneMap(gen, rs, seed, pos);
        var ns = zone.neighbours(pos.getX(), pos.getZ());
        if (ns.length == 0) return List.of();
        if (own == null) own = zone.own(pos.getX(), pos.getZ());
        double noise = dist.noiseInfluence > 0 ? BoundaryDetector.noise(rs, seed, pos.getX(), pos.getZ(), dist.noiseScale) : 0;
        double jitter = TransitionMath.hash01(seed, pos.getX(), 0, pos.getZ(), 0x7A11) * 2 - 1;
        double intensity = channel == Channel.FLUID ? cfg.advanced.fluid.intensity : dist.intensity;
        List<Influence> out = new ArrayList<>(2);
        List<Holder<Biome>> seen = new ArrayList<>(ns.length);
        double total = 0;
        for (var n : ns) {
            // Small areas under Feature Suppression spread no features (their ground still counts).
            if (channel.features() && n.featureless()) continue;
            if (seen.contains(n.biome())) continue; // a nearer cell of this biome was already used
            seen.add(n.biome());
            Mixing.Rule rule = Mixing.between(own, n.biome(), channel);
            if (rule == null) continue;
            Mixing.Rule back = Mixing.between(n.biome(), own, channel);
            double d = n.distance(dist.shape);
            // Coherent noise must shift the shared curve the same way seen from both biomes: opposite signs.
            double warp = orientation(own, n.biome()) * dist.noiseInfluence * noise * (rule.curve() == TransitionMath.Curve.NOISE ? 2 : 1)
                    + dist.randomVariation * jitter;
            double share = TransitionMath.share(d, rule.width(), back == null ? 0 : back.width(), dist.falloff, rule.curve(),
                    dist.customCurve, dist.smoothness, warp, intensity, dist.ownInfluence, dist.neighbourInfluence);
            out.add(new Influence(n.biome(), share, d, rule.width(), rule));
            total += share;
        }
        // A small biome without features of its own is filled entirely by its neighbours, however far they reach.
        boolean fill = channel.features() && !out.isEmpty()
                && BiomeReplacer.featuresSuppressed(gen.getBiomeSource(), rs, pos.getX(), pos.getZ(), own);
        if (fill && total <= 0) {
            total = 0;
            for (int i = 0; i < out.size(); i++) {
                var in = out.get(i);
                double w = 1 / (1 + in.distance);
                out.set(i, new Influence(in.foreign, w, in.distance, in.width, in.rule));
                total += w;
            }
        }
        out.removeIf(i -> i.share <= 0);
        if (out.isEmpty()) return out;
        if (cfg.mixing.intersectionPolicy == IntersectionPolicy.DO_NOT_BLEND && out.size() >= 2) return List.of();
        // Several neighbours together may not push the own biome below zero.
        final double sum = total;
        if (sum > 1 || fill) out.replaceAll(i -> new Influence(i.foreign, i.share / sum, i.distance, i.width, i.rule));
        return out;
    }

    /** +1 or -1, opposite for the two biomes of a pair. */
    private static int orientation(Holder<Biome> own, Holder<Biome> foreign) {
        int c = BiomeClassifier.id(own).compareTo(BiomeClassifier.id(foreign));
        if (c == 0) c = Integer.compare(System.identityHashCode(own.value()), System.identityHashCode(foreign.value()));
        return c < 0 ? 1 : -1;
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
