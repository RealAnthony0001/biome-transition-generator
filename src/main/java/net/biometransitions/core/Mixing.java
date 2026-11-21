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
 * Decides whether features of a neighbouring biome may spread into a biome, and with which settings:
 * global lists first, then the first matching Biome Boundary Mixing pair, then the global defaults.
 */
public final class Mixing {
    /** Effective settings for features of one neighbour flowing into one biome. */
    public record Rule(double width, DensityMode treesAndFoliage, DensityMode other, Curve curve,
                       VarietyBalancing variety, boolean surface) {}

    private record Key(Holder<Biome> own, Holder<Biome> foreign, boolean fluid) {}

    private static final Map<Key, Optional<Rule>> CACHE = new ConcurrentHashMap<>();

    private Mixing() {}

    public static void clear() { CACHE.clear(); }

    /** Rule for features of {@code foreign} spreading into {@code own}, or null when they must not. */
    public static Rule between(Holder<Biome> own, Holder<Biome> foreign, boolean fluid) {
        return CACHE.computeIfAbsent(new Key(own, foreign, fluid), k -> Optional.ofNullable(compute(k))).orElse(null);
    }

    private static Rule compute(Key k) {
        BtgConfig cfg = BtgConfig.get();
        var m = cfg.mixing;
        Holder<Biome> own = k.own, foreign = k.foreign;
        if (own.equals(foreign)) return null;
        boolean ownUnder = BiomeClassifier.categories(own).contains("underground");
        if (!cfg.general.mixSurfaceAndUnderground && ownUnder != BiomeClassifier.categories(foreign).contains("underground")) return null;
        if (BiomeClassifier.listed(m.noBlend, own) || BiomeClassifier.listed(m.noBlend, foreign)) return null;
        for (String group : m.blendOnlyWithSelf)
            if ((BiomeClassifier.match(group, own) >= 0) != (BiomeClassifier.match(group, foreign) >= 0)) return null;

        PairRule pair = null;
        for (PairRule p : cfg.pairs) {
            boolean forward = is(p.a, foreign) && is(p.b, own), backward = is(p.b, foreign) && is(p.a, own);
            if (!forward && !backward) continue;
            boolean allowed = switch (p.mode) {
                case DO_NOT_BLEND -> false;
                case A_INTO_B -> forward;
                case B_INTO_A -> backward;
                case BOTH -> true;
            };
            if (!allowed) return null;
            pair = p;
            break;
        }

        double width;
        if (k.fluid) width = cfg.advanced.fluid.width;
        else if (pair != null && pair.distance >= 0) width = pair.distance;
        else width = cfg.general.mixingDistance * (BiomeClassifier.shareGroup(own, foreign) ? m.similarDistanceMultiplier : 1);
        width *= Transitions.climateFactor(own, foreign, cfg.advanced.climateAdaptation);
        if (width <= 0) return null;

        boolean surface = cfg.general.surfaceMixing && (pair == null || pair.surfaceMixing)
                && !BiomeClassifier.listed(m.noSurfaceBlend, own) && !BiomeClassifier.listed(m.noSurfaceBlend, foreign);
        return new Rule(width,
                pair != null && pair.treesAndFoliageDensity != null ? pair.treesAndFoliageDensity : m.density.treesAndFoliage,
                pair != null && pair.otherDensity != null ? pair.otherDensity : m.density.other,
                pair != null && pair.curve != null ? pair.curve : m.distribution.curve,
                pair != null && pair.varietyBalancing != null ? pair.varietyBalancing : m.varietyBalancing,
                surface);
    }

    /** Empty selectors in a pair match nothing (a freshly added pair does not grab every biome). */
    private static boolean is(String selector, Holder<Biome> biome) {
        return selector != null && !selector.isBlank() && BiomeClassifier.match(selector, biome) >= 0;
    }

    /** Widest zone any rule can produce; the boundary search must look at least this far. */
    public static int maxWidth() {
        BtgConfig cfg = BtgConfig.get();
        double w = Math.max(cfg.advanced.fluid.width, cfg.general.mixingDistance * Math.max(1, cfg.mixing.similarDistanceMultiplier));
        for (PairRule p : cfg.pairs) w = Math.max(w, p.distance);
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
