package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.DensityMode;
import net.biometransitions.config.BtgConfig.PairRule;
import net.biometransitions.config.BtgConfig.VarietyBalancing;
import net.biometransitions.core.TransitionMath.Curve;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether something of a neighbouring biome may spread into a biome, and with which settings:
 * global lists first, then the first matching Biome Boundary Mixing pair, then the global defaults.
 */
public final class Mixing {
    /** What crosses the boundary. Each channel has its own distance; surface blocks also their own direction. */
    public enum Channel {
        /** Land features except trees. */
        FEATURES,
        /** Trees ({@code trees} category): Tree Mixing Distance. */
        TREES,
        /** Water features: the separate, narrower fluid zone. */
        FLUID,
        /** Surface blocks and snow layers: Block Blending Distance and Block Blending Direction. */
        SURFACE;

        public static Channel of(FeatureCatalog.Entry e) {
            return e.fluidChannel() ? FLUID : e.category().equals("trees") ? TREES : FEATURES;
        }

        public boolean features() { return this != SURFACE; }
    }

    /** Effective settings for one neighbour spreading into one biome. */
    public record Rule(double width, DensityMode treesAndFoliage, DensityMode other, Curve curve, VarietyBalancing variety) {}

    private record Key(Holder<Biome> own, Holder<Biome> foreign, Channel channel) {}

    private static final Map<Key, Optional<Rule>> CACHE = new ConcurrentHashMap<>();

    private Mixing() {}

    public static void clear() { CACHE.clear(); }

    /** Rule for {@code foreign} spreading into {@code own} on this channel, or null when it must not. */
    public static Rule between(Holder<Biome> own, Holder<Biome> foreign, Channel channel) {
        return CACHE.computeIfAbsent(new Key(own, foreign, channel), k -> Optional.ofNullable(compute(k))).orElse(null);
    }

    /** True when the neighbour may spread anything at all (any channel) into {@code own}. */
    public static boolean any(Holder<Biome> own, Holder<Biome> foreign) {
        for (Channel c : Channel.values()) if (between(own, foreign, c) != null) return true;
        return false;
    }

    private static Rule compute(Key k) {
        BtgConfig cfg = BtgConfig.get();
        var m = cfg.mixing;
        Holder<Biome> own = k.own, foreign = k.foreign;
        boolean surface = k.channel == Channel.SURFACE;
        if (own.equals(foreign)) return null;
        boolean ownUnder = BiomeClassifier.categories(own).contains("underground");
        if (!cfg.general.mixSurfaceAndUnderground && ownUnder != BiomeClassifier.categories(foreign).contains("underground")) return null;
        // No Blend: nothing crosses, whichever side is listed and whatever the channel.
        if (BiomeClassifier.listed(m.noBlend, own) || BiomeClassifier.listed(m.noBlend, foreign)) return null;
        for (String group : m.blendOnlyWithSelf)
            if ((BiomeClassifier.match(group, own) >= 0) != (BiomeClassifier.match(group, foreign) >= 0)) return null;
        if (surface && (!cfg.general.surfaceMixing
                || BiomeClassifier.listed(m.noSurfaceBlend, own) || BiomeClassifier.listed(m.noSurfaceBlend, foreign))) return null;

        PairRule pair = null;
        for (PairRule p : cfg.pairs) {
            boolean forward = is(p.a, foreign) && is(p.b, own), backward = is(p.b, foreign) && is(p.a, own);
            if (!forward && !backward) continue;
            boolean allowed = surface ? switch (p.blockDirection == null ? BtgConfig.BlockBlend.BLEND_BOTH : p.blockDirection) {
                case NO_BLENDING -> false;
                case A_INTO_B -> forward;
                case B_INTO_A -> backward;
                case BLEND_BOTH -> true;
            } : switch (p.mode == null ? BtgConfig.MixMode.BOTH : p.mode) {
                case DO_NOT_BLEND -> false;
                case A_INTO_B -> forward;
                case B_INTO_A -> backward;
                case BOTH -> true;
            };
            if (!allowed) return null;
            pair = p;
            break;
        }

        boolean similar = BiomeClassifier.shareGroup(own, foreign);
        double width = switch (k.channel) {
            case FLUID -> cfg.advanced.fluid.width;
            case FEATURES -> featureWidth(cfg, pair, similar);
            case TREES -> pair != null && pair.treeDistance >= 0 ? pair.treeDistance
                    : m.treeMixingDistance >= 0 ? scaled(cfg, m.treeMixingDistance, similar) : featureWidth(cfg, pair, similar);
            case SURFACE -> pair != null && pair.blockDistance >= 0 ? pair.blockDistance
                    : m.surface.blockBlendingDistance >= 0 ? scaled(cfg, m.surface.blockBlendingDistance, similar) : featureWidth(cfg, pair, similar);
        };
        width *= Transitions.climateFactor(own, foreign, cfg.advanced.climateAdaptation);
        if (width <= 0) return null;

        return new Rule(width,
                pair != null && pair.treesAndFoliageDensity != null ? pair.treesAndFoliageDensity : m.density.treesAndFoliage,
                pair != null && pair.otherDensity != null ? pair.otherDensity : m.density.other,
                pair != null && pair.curve != null ? pair.curve : m.distribution.curve,
                pair != null && pair.varietyBalancing != null ? pair.varietyBalancing : m.varietyBalancing);
    }

    private static double featureWidth(BtgConfig cfg, PairRule pair, boolean similar) {
        return pair != null && pair.distance >= 0 ? pair.distance : scaled(cfg, cfg.general.mixingDistance, similar);
    }

    /** Global distances are widened for biomes of a similar group; a pair's own distances are taken as they are. */
    private static double scaled(BtgConfig cfg, double distance, boolean similar) {
        return distance * (similar ? cfg.mixing.similarDistanceMultiplier : 1);
    }

    /** Empty selectors in a pair match nothing (a freshly added pair does not grab every biome). */
    private static boolean is(String selector, Holder<Biome> biome) {
        return selector != null && !selector.isBlank() && BiomeClassifier.match(selector, biome) >= 0;
    }

    /** Widest zone any rule can produce; the boundary search must look at least this far. */
    public static int maxWidth() {
        BtgConfig cfg = BtgConfig.get();
        double similar = Math.max(1, cfg.mixing.similarDistanceMultiplier);
        double w = Math.max(cfg.advanced.fluid.width, cfg.general.mixingDistance * similar);
        w = Math.max(w, cfg.mixing.treeMixingDistance * similar);
        w = Math.max(w, cfg.mixing.surface.blockBlendingDistance * similar);
        for (PairRule p : cfg.pairs) w = Math.max(w, Math.max(p.distance, Math.max(p.treeDistance, p.blockDistance)));
        return (int) Math.ceil(w);
    }

    /**
     * Density multiplier for a feature of {@code source} mixed into {@code target}: Feature Density
     * (average vs gradual) and Feature Variety Balancing. 1 = the source biome's own rate.
     */
    public static double featureFactor(FeatureCatalog.Entry e, Decoration step, Holder<Biome> source, Holder<Biome> target, Rule rule) {
        boolean foliage = Density.foliage(e.category());
        double f = 1;
        if ((foliage ? rule.treesAndFoliage() : rule.other()) == DensityMode.AVERAGE) {
            double ds = Density.of(source, step, foliage).expected(), dt = Density.of(target, step, foliage).expected();
            // Zone total = average of both; this side's share of it comes from the source biome's features.
            if (ds > 0) f = Math.min(4, (ds + dt) / 2 / ds);
        }
        if (rule.variety() == VarietyBalancing.ENABLED || rule.variety() == VarietyBalancing.FOLIAGE_ONLY && foliage) {
            int ks = Density.of(source, step, foliage).kinds(), kt = Density.of(target, step, foliage).kinds();
            if (ks > 0) f *= Math.min(1, Math.max(1, kt) / (double) ks);
        }
        return f;
    }

    /** Share of the biome's own features to keep at density AVERAGE (1 = all), relative to the gradual mix. */
    public static double nativeKeepFactor(FeatureCatalog.Entry e, Decoration step, Holder<Biome> own, Holder<Biome> foreign, Rule rule) {
        boolean foliage = Density.foliage(e.category());
        if ((foliage ? rule.treesAndFoliage() : rule.other()) != DensityMode.AVERAGE) return 1;
        double dOwn = Density.of(own, step, foliage).expected(), dForeign = Density.of(foreign, step, foliage).expected();
        return dOwn <= 0 ? 1 : Math.min(1, (dOwn + dForeign) / 2 / dOwn);
    }
}
