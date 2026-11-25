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
        return byTags(biome, c);
    }

    /** Groups the tag rules of {@code c} give the biome (ignoring overrides); "other" when none match. */
    public static List<String> byTags(Holder<Biome> biome, BtgConfig.Classification c) {
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

    /**
     * Selector -> specificity, or -1 for no match. Accepts:
     * {@code all}/{@code *} (0), group names like {@code forest} or legacy {@code #forest} (1),
     * {@code surface}/{@code underground} (1), biome tags {@code #minecraft:is_ocean} (1), biome ids (2),
     * and unions {@code desert+badlands} (best part).
     */
    public static int match(String selector, Holder<Biome> biome) {
        if (selector == null) return 0;
        selector = selector.trim();
        if (selector.isEmpty() || selector.equals("*") || selector.equals("all")) return 0;
        if (selector.contains("+")) {
            int best = -1;
            for (String part : selector.split("\\+")) if (!part.isBlank()) best = Math.max(best, match(part, biome));
            return best;
        }
        if (selector.equals("surface")) return categories(biome).contains("underground") ? -1 : 1;
        boolean tag = selector.startsWith("#");
        String name = tag ? selector.substring(1) : selector;
        if (name.contains(":")) {
            if (tag) return hasTag(biome, name) ? 1 : -1;
            return name.equals(id(biome)) ? 2 : -1;
        }
        return categories(biome).contains(name) ? 1 : -1;
    }

    public static boolean matchesAny(List<String> selectors, Holder<Biome> biome) {
        return selectors.isEmpty() || selectors.stream().anyMatch(s -> match(s, biome) >= 0);
    }

    /** Like {@link #matchesAny}, but an empty list matches nothing. */
    public static boolean listed(List<String> selectors, Holder<Biome> biome) {
        return !selectors.isEmpty() && matchesAny(selectors, biome);
    }

    /** The first selector of the list that matches, or null. */
    public static String firstMatch(List<String> selectors, Holder<Biome> biome) {
        for (String s : selectors) if (match(s, biome) >= 0) return s;
        return null;
    }

    public static boolean shareGroup(Holder<Biome> a, Holder<Biome> b) {
        for (String c : categories(a)) if (!c.equals("other") && categories(b).contains(c)) return true;
        return false;
    }
}
