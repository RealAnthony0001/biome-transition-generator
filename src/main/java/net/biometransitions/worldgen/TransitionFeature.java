package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.FeatureCatalog;
import net.biometransitions.core.Mixing;
import net.biometransitions.core.SurfaceRules;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.Transitions;
import net.biometransitions.debug.Stats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runs once per chunk per decoration step. Takes the placed features of every foreign biome near the
 * chunk, runs their own placement rules (count, rarity, heightmap...) with the biome filter swapped for
 * the transition share, then picks among the candidates by selection weight. Each chunk only handles
 * its own placement origins, so neighbouring chunks never place the same thing twice.
 */
public class TransitionFeature extends Feature<StepConfig> {
    private record Candidate(FeatureCatalog.Entry entry, BlockPos pos, double key, BtgConfig.Fluid fluid) {}

    public TransitionFeature() { super(StepConfig.CODEC); }

    @Override
    public boolean place(FeaturePlaceContext<StepConfig> ctx) {
        BtgConfig cfg = BtgConfig.get();
        WorldGenLevel level = ctx.level();
        ServerLevel server = level.getLevel();
        Decoration step = ctx.config().step();
        if (!cfg.general.enabled) return false;
        if (!cfg.general.dimensions.isEmpty() && !cfg.general.dimensions.contains(server.dimension().location().toString())) return false;

        ChunkGenerator gen = ctx.chunkGenerator();
        RandomState rs = server.getChunkSource().randomState();
        long seed = level.getSeed();
        BlockPos origin = ctx.origin();
        ChunkPos cp = new ChunkPos(origin);
        // Ground first (first decoration step), so every later feature sees the blended surface.
        if (step == Decoration.RAW_GENERATION) return SurfaceBlending.apply(level, gen, rs, seed, cp) > 0;
        boolean any = false;
        if (cfg.features.enabledSteps.contains(step.name())) {
            // Small areas under Feature Suppression spread no features, so they are no source here.
            Set<Holder<Biome>> foreign = new LinkedHashSet<>(), owns = new HashSet<>();
            for (int sy : sections(level, cp, cfg)) {
                var zm = BoundaryDetector.zoneMap(gen, rs, seed, cp.x, sy, cp.z);
                for (var ns : zm.neighbours())
                    for (var n : ns) if (!n.featureless()) foreign.add(n.biome());
                owns.addAll(java.util.Arrays.asList(zm.own()));
            }
            if (!foreign.isEmpty()) {
                List<Candidate> candidates = collect(level, gen, rs, seed, origin, step, foreign, owns, cfg);
                candidates.sort(Comparator.comparingDouble(Candidate::key).reversed());
                any = placeAll(level, gen, seed, candidates, cfg) > 0;
            }
        }
        // Snow last, on top of everything placed (vanilla snow runs in this step as well).
        if (step == Decoration.TOP_LAYER_MODIFICATION) any |= SurfaceBlending.snow(level, gen, rs, seed, cp) > 0;
        return any;
    }

    /** Sections to look for boundaries in: the surface ones, plus every n-th one below for cave biomes. */
    private static Set<Integer> sections(WorldGenLevel level, ChunkPos cp, BtgConfig cfg) {
        Set<Integer> out = new TreeSet<>();
        int top = Integer.MIN_VALUE;
        for (int[] o : new int[][]{{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) {
            int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cp.getMinBlockX() + o[0], cp.getMinBlockZ() + o[1]);
            out.add(h >> 4);
            out.add((h - 1) >> 4);
            top = Math.max(top, h >> 4);
        }
        if (cfg.advanced.undergroundTransitions)
            for (int s = level.getMinSection(); s < top; s += cfg.performance.undergroundSectionStride) out.add(s);
        return out;
    }

    private static List<Candidate> collect(WorldGenLevel level, ChunkGenerator gen, RandomState rs, long seed, BlockPos origin,
                                           Decoration step, Set<Holder<Biome>> foreign, Set<Holder<Biome>> owns, BtgConfig cfg) {
        WorldgenRandom rand = new WorldgenRandom(new XoroshiroRandomSource(seed));
        long decorationSeed = rand.setDecorationSeed(seed, origin.getX(), origin.getZ());
        List<Candidate> out = new ArrayList<>();
        var fluid = cfg.advanced.fluid;
        for (Holder<Biome> source : foreign) {
            for (FeatureCatalog.Entry e : FeatureCatalog.all(source, step)) {
                // Cheap rejections first: running the placement pipeline is the expensive part.
                if (FeatureCatalog.rejectFor(e, step, null) != null) continue;
                Mixing.Channel channel = Mixing.Channel.of(e);
                // Density/variety factor per biome of this chunk the feature may flow into; none = skip the feature.
                Map<Holder<Biome>, Double> factors = new HashMap<>();
                for (Holder<Biome> own : owns) {
                    var rule = Mixing.between(own, source, channel);
                    // A biome that has the feature itself only takes it where its own copy is suppressed (small areas).
                    if (rule != null && (!own.value().getGenerationSettings().hasFeature(e.feature().value())
                            || BiomeClassifier.listed(cfg.replacing.suppressFeatureBiomes, own)))
                        factors.put(own, Mixing.featureFactor(e, step, source, own, rule));
                }
                double maxFactor = factors.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                if (maxFactor <= 0) continue;
                // Frequency above 1 runs the feature's whole placement pipeline several times, so a zone can be
                // denser than the source biome while every position still obeys the feature's own rules.
                double shoreMax = !e.fluidChannel() && fluid.shoreCategories.contains(e.category()) ? Math.max(1, fluid.shoreFrequency) : 1;
                int passes = Math.max(1, (int) Math.ceil(FeatureCatalog.frequency(e) * maxFactor * shoreMax));
                for (int pass = 0; pass < passes; pass++) {
                    rand.setFeatureSeed(decorationSeed, e.id().hashCode() * 31 + source.hashCode() + pass * 0x9E3779B9, step.ordinal());
                    Iterator<BlockPos> it = positions(e, level, gen, rand, origin).limit(e.settings().maxAttempts).iterator();
                    while (it.hasNext()) {
                        BlockPos pos = it.next().immutable();
                        Stats.inc("attempts");
                        var influences = Transitions.at(gen, rs, seed, pos, null, channel);
                        var in = influences.stream().filter(i -> i.foreign().equals(source)).findFirst().orElse(null);
                        if (in == null) continue; // outside the zone: the biome filter would have said no as well
                        Holder<Biome> target = BoundaryDetector.zoneMap(gen, rs, seed, pos).own(pos.getX(), pos.getZ());
                        String why = FeatureCatalog.rejectFor(e, step, target);
                        if (FeatureCatalog.TARGET_HAS_IT.equals(why)
                                && Suppression.ownCopySuppressed(gen.getBiomeSource(), rs, pos, target, e.feature().value(), e.type())) why = null;
                        if (why != null) {
                            Stats.reject(e.id(), why);
                            continue;
                        }
                        Double factor = factors.get(target);
                        if (factor == null) factor = Mixing.featureFactor(e, step, source, target, in.rule());
                        double p = in.share() * FeatureCatalog.frequency(e) * factor / passes;
                        if (!e.fluidChannel() && fluid.shoreRadius > 0 && fluid.shoreCategories.contains(e.category())
                                && SurfaceRules.nearWater(level, pos, fluid.shoreRadius)) p *= fluid.shoreFrequency;
                        if (rand.nextDouble() >= p) continue;
                        out.add(new Candidate(e, pos, TransitionMath.weightedKey(rand.nextDouble(), e.settings().weight), fluid));
                        if (out.size() >= cfg.performance.maxCandidatesPerChunk) return out;
                    }
                }
            }
        }
        return out;
    }

    /** The feature's own placement pipeline, minus the biome filter (the transition share replaces it). */
    private static Stream<BlockPos> positions(FeatureCatalog.Entry e, WorldGenLevel level, ChunkGenerator gen, WorldgenRandom rand, BlockPos origin) {
        PlacementContext pctx = new PlacementContext(level, gen, Optional.of(e.feature().value()));
        Stream<BlockPos> s = Stream.of(origin);
        for (PlacementModifier m : e.feature().value().placement())
            if (!(m instanceof BiomeFilter)) s = s.flatMap(p -> m.getPositions(pctx, rand, p));
        return s;
    }

    private static int placeAll(WorldGenLevel level, ChunkGenerator gen, long seed, List<Candidate> candidates, BtgConfig cfg) {
        Map<String, List<BlockPos>> byFeature = new HashMap<>();
        Set<String> done = new HashSet<>();
        int placed = 0;
        for (Candidate c : candidates) {
            String id = c.entry.id();
            if (placed >= cfg.performance.maxPlacementsPerChunk) {
                Stats.reject(id, "chunk placement limit");
                continue;
            }
            if (!done.add(id + "@" + c.pos.asLong())) {
                Stats.reject(id, "duplicate");
                continue;
            }
            List<BlockPos> prev = byFeature.computeIfAbsent(id, k -> new ArrayList<>());
            String why = spacing(c, prev);
            WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(seed ^ c.pos.asLong() * 0x9E3779B97F4A7C15L ^ id.hashCode()));
            if (why == null) why = SurfaceRules.check(level, c.pos, c.entry, c.fluid, r);
            if (why == null && cfg.advanced.preventFeatureOverlap && SurfaceRules.overlaps(level, c.pos)) why = "overlaps another feature";
            if (why != null) {
                Stats.reject(id, why);
                continue;
            }
            if (c.entry.feature().value().feature().value().place(level, gen, r, c.pos)) {
                placed++;
                prev.add(c.pos);
                Stats.inc("placed");
                Stats.inc("placed: " + c.entry.category());
            } else Stats.reject(id + " at " + c.pos.toShortString(), "feature declined the spot");
        }
        return placed;
    }

    // shortcut: spacing is checked within this chunk only; cross-chunk spacing needs neighbour chunk state.
    private static String spacing(Candidate c, List<BlockPos> prev) {
        int min = c.entry.settings().minSpacing, max = c.entry.settings().maxSpacing;
        boolean near = prev.isEmpty();
        for (BlockPos p : prev) {
            double d = Math.hypot(p.getX() - c.pos.getX(), p.getZ() - c.pos.getZ());
            if (d < min) return "closer than minimum spacing";
            if (d <= max) near = true;
        }
        return max > 0 && !near ? "farther than maximum spacing" : null;
    }
}
