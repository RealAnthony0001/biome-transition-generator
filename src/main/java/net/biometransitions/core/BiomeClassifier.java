package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Biome -> categories (first = primary), from user overrides, then tag rules. */
public final class BiomeClassifier {
    private static final Map<Holder<Biome>, List<String>> CACHE = new ConcurrentHashMap<>();

    private BiomeClassifier() {}

    public static void clear() { CACHE.clear(); }

    public static String id(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("unregistered");
    }

    public static List<String> categories(Holder<Biome> biome) {
        return CACHE.computeIfAbsent(biome, BiomeClassifier::classify);
    }

    private static List<String> classify(Holder<Biome> biome) {
        var c = BtgConfig.get().classification;
        List<String> override = c.overrides.get(id(biome));
        if (override != null) return List.copyOf(override);
        List<String> out = new ArrayList<>();
        for (var rule : c.tagRules) {
            if (rule.tags.isEmpty() || !rule.tags.stream().allMatch(t -> hasTag(biome, t))) continue;
            for (String cat : rule.categories) if (!out.contains(cat)) out.add(cat);
        }
        if (out.isEmpty()) out.add("other");
        return List.copyOf(out);
    }

    private static boolean hasTag(Holder<Biome> biome, String tag) {
        ResourceLocation rl = ResourceLocation.tryParse(tag.startsWith("#") ? tag.substring(1) : tag);
        return rl != null && biome.is(TagKey.create(Registries.BIOME, rl));
    }

    /** Selector: "*" = 0, "#category" = 1, biome id = 2, no match = -1. */
    public static int match(String selector, Holder<Biome> biome) {
        if (selector == null || selector.isEmpty() || selector.equals("*")) return 0;
        if (selector.startsWith("#")) return categories(biome).contains(selector.substring(1)) ? 1 : -1;
        return selector.equals(id(biome)) ? 2 : -1;
    }

    public static boolean matchesAny(List<String> selectors, Holder<Biome> biome) {
        return selectors.isEmpty() || selectors.stream().anyMatch(s -> match(s, biome) >= 0);
    }
}
