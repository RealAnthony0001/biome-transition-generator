package net.biometransitions.core;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expected number of placements per chunk of a biome's features, estimated from their placement modifiers
 * (count, rarity, noise counts). Independent of the world, so worldgen stays deterministic.
 */
public final class Density {
    public static final Set<String> FOLIAGE = Set.of("trees", "large_vegetation", "small_vegetation", "flowers",
            "grass", "bushes", "mushrooms", "aquatic_vegetation");

    /** @param expected placements per chunk, @param kinds number of different features */
    public record Stats(double expected, int kinds) {}

    private record Key(Holder<Biome> biome, Decoration step, boolean foliage) {}

    private static final Map<Key, Stats> CACHE = new ConcurrentHashMap<>();

    private Density() {}

    public static void clear() { CACHE.clear(); }

    public static boolean foliage(String category) { return FOLIAGE.contains(category); }

    public static Stats of(Holder<Biome> biome, Decoration step, boolean foliage) {
        return CACHE.computeIfAbsent(new Key(biome, step, foliage), k -> {
            double sum = 0;
            int kinds = 0;
            for (var e : FeatureCatalog.all(k.biome, k.step)) {
                if (!e.hasBiomeFilter() || e.rejectReason() != null || foliage(e.category()) != k.foliage) continue;
                double n = 1;
                for (PlacementModifier m : e.feature().value().placement()) {
                    var json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, m).result().orElse(null);
                    if (json != null && json.isJsonObject()) n *= Expect.modifier(json.getAsJsonObject());
                }
                sum += n;
                kinds++;
            }
            return new Stats(sum, kinds);
        });
    }
}
