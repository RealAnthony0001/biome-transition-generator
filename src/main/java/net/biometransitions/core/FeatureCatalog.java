package net.biometransitions.core;

import com.google.gson.JsonObject;
import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.FeatureSettings;
import net.biometransitions.worldgen.TransitionFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Collects a biome's placed features, classifies them and decides which may cross a boundary. */
public final class FeatureCatalog {
    public record Entry(Holder<PlacedFeature> feature, String id, String type, String category, FeatureSettings settings,
                        boolean hasBiomeFilter, String rejectReason) {
        public boolean fluidChannel() { return category.equals("aquatic_vegetation") || category.equals("fluid"); }
    }

    private record Key(Holder<Biome> biome, Decoration step) {}

    private static final Map<Key, List<Entry>> BY_BIOME = new ConcurrentHashMap<>();
    private static final Map<PlacedFeature, Entry> BY_FEATURE = new ConcurrentHashMap<>();

    /** {@link #rejectFor} reason when the target biome generates the feature itself. */
    public static final String TARGET_HAS_IT = "target biome already has it";

    private FeatureCatalog() {}

    public static void clear() {
        BY_BIOME.clear();
        BY_FEATURE.clear();
        STEPS.clear();
    }

    /** All features of a biome in a step, eligible or not (rejected ones carry a reason). */
    public static List<Entry> all(Holder<Biome> biome, Decoration step) {
        return BY_BIOME.computeIfAbsent(new Key(biome, step), k -> {
            var lists = k.biome.value().getGenerationSettings().features();
            if (k.step.ordinal() >= lists.size()) return List.of();
            List<Entry> out = new ArrayList<>();
            for (Holder<PlacedFeature> f : lists.get(k.step.ordinal())) {
                Entry e = entry(f);
                if (e != null) out.add(e);
            }
            return List.copyOf(out);
        });
    }

    /** Null for this mod's own features. */
    public static Entry entry(Holder<PlacedFeature> f) {
        if (f.value().feature().value().feature() instanceof TransitionFeature) return null;
        return BY_FEATURE.computeIfAbsent(f.value(), pf -> build(f));
    }

    private static Entry build(Holder<PlacedFeature> f) {
        BtgConfig cfg = BtgConfig.get();
        String id = f.unwrapKey().map(k -> k.location().toString()).orElse("inline");
        var featureType = f.value().feature().value().feature();
        String type = String.valueOf(BuiltInRegistries.FEATURE.getKey(featureType));

        JsonObject override = cfg.featureOverrides.get(id);
        String category = override != null && override.has("category") ? override.get("category").getAsString() : "";
        if (category.isEmpty()) category = cfg.features.typeCategories.getOrDefault(type, "");
        if (category.isEmpty()) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            for (var kw : cfg.features.keywordCategories.entrySet())
                if (path.contains(kw.getKey())) { category = kw.getValue(); break; }
        }
        if (category.isEmpty()) category = "other";

        JsonObject merged = BtgConfig.GSON.toJsonTree(cfg.featureDefaults).getAsJsonObject();
        merged = Json.merge(merged, cfg.categorySettings.get(category));
        merged = Json.merge(merged, override);
        FeatureSettings settings = BtgConfig.GSON.fromJson(merged, FeatureSettings.class);

        boolean biomeFilter = f.value().placement().stream().anyMatch(m -> m instanceof BiomeFilter);
        String reject = null;
        if (cfg.features.excludedFeatureTypes.contains(type)) reject = "excluded feature type " + type;
        else if (!settings.enabled) reject = "disabled in feature settings";
        return new Entry(f, id, type, category, settings, biomeFilter, reject);
    }

    private static final Map<PlacedFeature, Decoration> STEPS = new ConcurrentHashMap<>();

    /** The decoration step a biome runs the feature in, or null. */
    public static Decoration stepOf(Holder<Biome> biome, PlacedFeature pf) {
        Decoration cached = STEPS.get(pf);
        if (cached != null) return cached;
        var lists = biome.value().getGenerationSettings().features();
        for (int i = 0; i < lists.size(); i++)
            if (lists.get(i).stream().anyMatch(h -> h.value() == pf)) {
                STEPS.put(pf, Decoration.values()[i]);
                return Decoration.values()[i];
            }
        return null;
    }

    /** Reason the entry may not cross this particular boundary (step null = any step), or null. */
    public static String rejectFor(Entry e, Decoration step, Holder<Biome> target) {
        BtgConfig cfg = BtgConfig.get();
        var a = cfg.advanced;
        if (e.rejectReason != null) return e.rejectReason;
        if (step != null && !cfg.features.enabledSteps.contains(step.name())) return "step " + step.name() + " disabled";
        if (e.fluidChannel() ? !a.fluidTransitions : !a.vegetationTransitions) return "channel disabled";
        if (!a.selection.allowedCategories.contains(e.category)) return "category " + e.category + " not allowed";
        if (a.selection.excludedFeatures.contains(e.id)) return "excluded in selection settings";
        if (e.category.equals("fluid") && !a.fluid.allowFluidBodies) return "fluid bodies disabled";
        if (target != null && !BiomeClassifier.matchesAny(e.settings.allowedBiomes, target)) return "target biome not allowed";
        if (target != null && target.value().getGenerationSettings().hasFeature(e.feature.value())) return TARGET_HAS_IT;
        return null;
    }

    /** Density multiplier from the transition settings (selection + category + feature + fluid channel). */
    public static double frequency(Entry e) {
        var a = BtgConfig.get().advanced;
        double f = a.selection.frequency * a.selection.categoryFrequency.getOrDefault(e.category, 1.0) * e.settings.frequency;
        return e.fluidChannel() ? f * a.fluid.frequency : f;
    }
}
