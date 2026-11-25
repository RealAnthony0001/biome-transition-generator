package net.biometransitions.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.biometransitions.BiomeTransitions;
import net.biometransitions.core.TransitionMath.Curve;
import net.biometransitions.core.TransitionMath.Shape;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole mod configuration, stored as one JSON file. The GUI edits it through reflection:
 * every numeric field needs a {@link Range} so it gets a slider with min/max/unit.
 * Default values shown in the GUI are read from a freshly constructed object, so they never go stale.
 * Labels and tooltips come from the language files: {@code biometransitions.config.<class>.<field>} and
 * {@code ...tooltip}, enum constants {@code biometransitions.config.enum.<enum>.<constant>}.
 */
public final class BtgConfig {
    /**
     * Slider range. {@code unit}, {@code minLabel} and {@code maxLabel} are translation key suffixes
     * ({@code biometransitions.config.unit.*}, {@code biometransitions.config.special.*}); a labelled end is a
     * special value shown by name instead of as a number.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Range {
        double min();
        double max();
        String unit() default "";
        String minLabel() default "";
        String maxLabel() default "";
    }

    /** Nullable setting: null = take the global value. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Inherit {}

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve(BiomeTransitions.MODID + ".json");
    private static volatile BtgConfig current = new BtgConfig();

    /** Current file format. Older files from {@link #MIGRATABLE} on are migrated, anything older is replaced (kept as .bak). */
    public static final int VERSION = 5;
    private static final int MIGRATABLE = 4;
    public int configVersion = 0; // missing in old files = 0; set on save

    // ---- sections (same order as the GUI) ----
    public General general = new General();
    public Mixing mixing = new Mixing();
    /** Rules for concrete pairs of biome groups; unlisted pairs use {@link #mixing}. */
    public List<PairRule> pairs = defaultPairs();
    public Replacing replacing = new Replacing();
    public Colors colors = new Colors();
    public Classification classification = new Classification();
    /** Default per-feature settings, including surface compatibility rules. */
    public FeatureSettings featureDefaults = new FeatureSettings();
    /** Partial {@link FeatureSettings} per feature category, e.g. trees need dirt below. */
    public Map<String, JsonObject> categorySettings = defaultCategorySettings();
    /** Partial {@link FeatureSettings} per placed feature id. */
    public Map<String, JsonObject> featureOverrides = new LinkedHashMap<>();
    public FeatureRules features = new FeatureRules();
    public Performance performance = new Performance();
    public Advanced advanced = new Advanced();
    public Debug debug = new Debug();

    public static class General {
        public boolean enabled = true;
        @Range(min = 0, max = 128, unit = "blocks") public int mixingDistance = 64;
        public boolean surfaceMixing = true;
        public boolean biomeReplacing = true;
        @Range(min = 0, max = 64, unit = "blocks", minLabel = "vanilla") public int colorBlendDistance = 32;
        public List<String> dimensions = new ArrayList<>();
        public boolean mixSurfaceAndUnderground = false;
    }

    public enum DensityMode {
        AVERAGE,
        GRADUAL
    }

    public enum VarietyBalancing {
        DISABLED,
        ENABLED,
        FOLIAGE_ONLY
    }

    public enum IntersectionPolicy {
        RESPECT_RULES,
        DO_NOT_BLEND
    }

    public enum MixMode {
        DO_NOT_BLEND,
        A_INTO_B,
        B_INTO_A,
        BOTH
    }

    /** Direction of surface block blending in a pair, independent of the feature direction. */
    public enum BlockBlend {
        BLEND_BOTH,
        A_INTO_B,
        B_INTO_A,
        NO_BLENDING
    }

    public static class Density {
        public DensityMode treesAndFoliage = DensityMode.GRADUAL;
        public DensityMode other = DensityMode.GRADUAL;
    }

    public static class Mixing {
        public Density density = new Density();
        public Distribution distribution = new Distribution();
        public VarietyBalancing varietyBalancing = VarietyBalancing.FOLIAGE_ONLY;
        @Range(min = 0.25, max = 4) public double similarDistanceMultiplier = 1.5;
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "same_as_mixing") public int treeMixingDistance = -1;
        public IntersectionPolicy intersectionPolicy = IntersectionPolicy.RESPECT_RULES;
        public List<String> noBlend = new ArrayList<>(List.of("ocean", "river", "beach"));
        public List<String> blendOnlyWithSelf = new ArrayList<>(List.of("desert+badlands"));
        public List<String> noSurfaceBlend = new ArrayList<>();
        public List<String> barriers = new ArrayList<>(List.of("river"));
        public Surface surface = new Surface();
    }

    /** Feature mixing between two biome groups. */
    public static class PairRule {
        public String a = "";
        public String b = "";
        public MixMode mode = MixMode.BOTH;
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "global") public int distance = -1;
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "global") public int treeDistance = -1;
        @Inherit public DensityMode treesAndFoliageDensity;
        @Inherit public DensityMode otherDensity;
        @Inherit public Curve curve;
        @Inherit public VarietyBalancing varietyBalancing;
        public BlockBlend blockDirection = BlockBlend.BLEND_BOTH;
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "global") public int blockDistance = -1;

        public PairRule() {}

        PairRule(String a, String b, MixMode mode, int distance) {
            this.a = a;
            this.b = b;
            this.mode = mode;
            this.distance = distance;
        }
    }

    /** Realistic neighbours only; extreme pairs (desert/snow) are not listed and stay narrow through climate adaptation. */
    public static List<PairRule> defaultPairs() {
        return new ArrayList<>(List.of(
                new PairRule("forest", "plains", MixMode.A_INTO_B, -1),
                new PairRule("swamp", "forest", MixMode.A_INTO_B, -1),
                new PairRule("swamp", "plains", MixMode.A_INTO_B, -1),
                new PairRule("forest", "taiga", MixMode.BOTH, 80),
                new PairRule("forest", "jungle", MixMode.BOTH, -1),
                new PairRule("jungle", "plains", MixMode.BOTH, 48),
                new PairRule("forest", "meadow", MixMode.BOTH, -1),
                new PairRule("plains", "meadow", MixMode.BOTH, -1),
                new PairRule("plains", "savanna", MixMode.BOTH, -1),
                new PairRule("taiga", "snowy", MixMode.BOTH, 48),
                new PairRule("plains", "snowy", MixMode.BOTH, 32),
                new PairRule("taiga", "mountain", MixMode.BOTH, 32)));
    }

    public enum ReplaceMode {
        NEAREST_SAME_GROUP,
        NEAREST_SIMILAR_GROUP,
        CUSTOM
    }

    public static class Replacing {
        public boolean smallBiomeDetection = true;
        @Range(min = 0.1, max = 2) public double overlapMultiplier = 0.4;
        public List<String> replaceBiomes = new ArrayList<>(List.of("ocean"));
        @Range(min = -1, max = 4096, unit = "quarts", minLabel = "overlapping") public int replaceMinSize = 306;
        public ReplaceMode changeInto = ReplaceMode.CUSTOM;
        public String customMapping = "ocean-river";
        public List<String> suppressFeatureBiomes = new ArrayList<>();
        @Range(min = -1, max = 4096, unit = "quarts", minLabel = "overlapping") public int suppressMinSize = -1;
        public List<String> neverReplace = new ArrayList<>();
    }

    public static class Colors {
        public boolean sameAsMixingDistance = true;
        public List<String> reducedBiomes = new ArrayList<>();
        @Range(min = 0, max = 64, unit = "blocks") public int reducedDistance = 4;
    }

    public static class Advanced {
        public boolean preventFeatureOverlap = false;
        @Range(min = 0, max = 1) public double climateAdaptation = 0.3;
        @Range(min = 1, max = 8) public int maxNeighbours = 3;
        public boolean vegetationTransitions = true;
        public boolean fluidTransitions = true;
        public boolean undergroundTransitions = true;
        public Selection selection = new Selection();
        public Fluid fluid = new Fluid();
    }

    public static class Distribution {
        public Curve curve = Curve.EASE_OUT;
        /** Control points over 0..1 used by the CUSTOM curve. */
        public List<Double> customCurve = new ArrayList<>(List.of(0.0, 0.1, 0.35, 0.75, 1.0));
        @Range(min = 0, max = 2) public double intensity = 1;
        @Range(min = 0.1, max = 4) public double falloff = 1;
        public Shape shape = Shape.ROUND;
        @Range(min = 0, max = 4) public double ownInfluence = 1;
        @Range(min = 0, max = 4) public double neighbourInfluence = 1;
        @Range(min = 0, max = 1) public double smoothness = 0.2;
        @Range(min = 0, max = 1) public double randomVariation = 0.1;
        @Range(min = 0, max = 1) public double noiseInfluence = 0.3;
        @Range(min = 4, max = 256, unit = "blocks") public double noiseScale = 32;
        public boolean suppressNative = true;
    }

    public static class Selection {
        @Range(min = 0, max = 4) public double frequency = 1.5;
        public List<String> allowedCategories = new ArrayList<>(List.of(
                "trees", "large_vegetation", "small_vegetation", "flowers", "grass", "bushes",
                "mushrooms", "ground_cover", "rocks", "terrain_decorations", "aquatic_vegetation", "fluid", "other"));
        /** Frequency multiplier per feature category, missing = 1. Small plants (cactus, sugar cane...) are rare per chunk, so they get more tries. */
        public Map<String, Double> categoryFrequency = new LinkedHashMap<>(Map.of("small_vegetation", 2.5));
        public List<String> excludedFeatures = new ArrayList<>();
    }

    public static class Fluid {
        @Range(min = 0, max = 64, unit = "blocks") public int width = 8;
        @Range(min = 0, max = 2) public double intensity = 1;
        @Range(min = 0, max = 4) public double frequency = 1;
        public List<String> allowedFluids = new ArrayList<>(List.of("#minecraft:water"));
        public boolean allowFluidBodies = false;
        public boolean allowLandFeaturesInFluid = false;
        @Range(min = 0, max = 8, unit = "blocks") public int shoreRadius = 3;
        @Range(min = 0, max = 4) public double shoreFrequency = 1.5;
        public List<String> shoreCategories = new ArrayList<>(List.of("grass", "flowers", "small_vegetation", "bushes"));
    }

    /**
     * Terrain blending: the neighbour's ground spreads in natural patches (sand into a jungle, grass into a
     * desert), so the neighbour's plants have something to grow on. On/off is {@link General#surfaceMixing}.
     */
    public static class Surface {
        @Range(min = 0, max = 2) public double intensity = 1;
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "same_as_mixing") public int blockBlendingDistance = -1;
        @Range(min = 2, max = 64, unit = "blocks") public double patchScale = 10;
        @Range(min = 1, max = 6, unit = "blocks") public int depth = 3;
        public boolean snowLayers = true;
        /** Biome selector -> [top block, block below]. First match of the neighbour wins; unmatched = no blending. */
        public Map<String, List<String>> blocks = defaultSurfaceBlocks();
        public List<String> replaceable = new ArrayList<>(List.of("#minecraft:dirt", "#minecraft:sand",
                "minecraft:sandstone", "minecraft:red_sandstone", "#minecraft:terracotta", "minecraft:gravel"));
    }

    private static Map<String, List<String>> defaultSurfaceBlocks() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("desert", List.of("minecraft:sand", "minecraft:sandstone"));
        m.put("beach", List.of("minecraft:sand", "minecraft:sandstone"));
        m.put("badlands", List.of("minecraft:red_sand", "minecraft:terracotta"));
        for (String c : List.of("forest", "dense_forest", "sparse_forest", "plains", "meadow", "jungle", "taiga", "swamp", "hills", "savanna"))
            m.put(c, List.of("minecraft:grass_block", "minecraft:dirt"));
        return m;
    }

    public static class TagRule {
        /** All tags must match. */
        public List<String> tags = new ArrayList<>();
        public List<String> categories = new ArrayList<>();

        TagRule() {}

        TagRule(String tags, String categories) {
            this.tags = new ArrayList<>(List.of(tags.split(",")));
            this.categories = new ArrayList<>(List.of(categories.split(",")));
        }
    }

    public static class Classification {
        /** Biome id -> categories (groups); replaces the tag rules for that biome. Special groups: all, surface, underground. */
        public Map<String, List<String>> overrides = new LinkedHashMap<>(Map.of(
                "minecraft:meadow", List.of("meadow"),
                "minecraft:cherry_grove", List.of("meadow", "forest"),
                "minecraft:dark_forest", List.of("dense_forest", "forest"),
                "minecraft:stony_shore", List.of("beach"),
                "minecraft:mushroom_fields", List.of("other")));
        public List<TagRule> tagRules = new ArrayList<>(List.of(
                new TagRule("minecraft:is_deep_ocean", "deep_ocean,ocean"),
                new TagRule("minecraft:is_ocean", "ocean"),
                new TagRule("minecraft:is_river", "river"),
                new TagRule("minecraft:is_beach", "beach"),
                new TagRule("c:is_swamp", "swamp"),
                new TagRule("minecraft:is_jungle", "jungle"),
                new TagRule("minecraft:is_taiga", "taiga"),
                new TagRule("minecraft:is_badlands", "badlands"),
                new TagRule("c:is_desert", "desert"),
                new TagRule("c:is_snowy", "snowy"),
                new TagRule("minecraft:is_mountain", "mountain"),
                new TagRule("c:is_mountain", "mountain"),
                new TagRule("minecraft:is_hill", "hills"),
                new TagRule("minecraft:is_forest,c:is_dense_vegetation", "dense_forest"),
                new TagRule("minecraft:is_forest,c:is_sparse_vegetation", "sparse_forest"),
                new TagRule("minecraft:is_forest", "forest"),
                new TagRule("c:is_plains", "plains"),
                new TagRule("c:is_savanna", "savanna"),
                new TagRule("c:is_cave", "underground"),
                new TagRule("c:is_underground", "underground")));
    }

    public static class FeatureRules {
        public List<String> enabledSteps = new ArrayList<>(List.of("LAKES", "LOCAL_MODIFICATIONS", "FLUID_SPRINGS", "VEGETAL_DECORATION"));
        public List<String> excludedFeatureTypes = new ArrayList<>(List.of(
                "minecraft:freeze_top_layer", "minecraft:monster_room", "minecraft:fossil", "minecraft:geode",
                "minecraft:ore", "minecraft:scattered_ore", "minecraft:replace_single_block", "minecraft:desert_well",
                "minecraft:bonus_chest", "minecraft:void_start_platform", "minecraft:end_spike", "minecraft:end_gateway",
                "minecraft:end_island", "minecraft:end_platform", "minecraft:chorus_plant"));
        /** Feature type id -> category. */
        public Map<String, String> typeCategories = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("minecraft:tree", "trees"), Map.entry("minecraft:fallen_tree", "trees"),
                Map.entry("minecraft:huge_brown_mushroom", "mushrooms"), Map.entry("minecraft:huge_red_mushroom", "mushrooms"),
                Map.entry("minecraft:huge_fungus", "large_vegetation"), Map.entry("minecraft:bamboo", "large_vegetation"),
                Map.entry("minecraft:seagrass", "aquatic_vegetation"), Map.entry("minecraft:kelp", "aquatic_vegetation"),
                Map.entry("minecraft:coral_tree", "aquatic_vegetation"), Map.entry("minecraft:coral_claw", "aquatic_vegetation"),
                Map.entry("minecraft:coral_mushroom", "aquatic_vegetation"), Map.entry("minecraft:sea_pickle", "aquatic_vegetation"),
                Map.entry("minecraft:lake", "fluid"), Map.entry("minecraft:spring_feature", "fluid"),
                Map.entry("minecraft:disk", "ground_cover"), Map.entry("minecraft:vegetation_patch", "ground_cover"),
                Map.entry("minecraft:waterlogged_vegetation_patch", "ground_cover"),
                Map.entry("minecraft:forest_rock", "rocks"),
                Map.entry("minecraft:ice_spike", "terrain_decorations"), Map.entry("minecraft:iceberg", "terrain_decorations"),
                Map.entry("minecraft:blue_ice", "terrain_decorations"), Map.entry("minecraft:basalt_columns", "terrain_decorations"),
                Map.entry("minecraft:pointed_dripstone", "terrain_decorations"),
                Map.entry("minecraft:flower", "flowers"), Map.entry("minecraft:block_column", "small_vegetation")));
        /** Keyword in the placed feature id -> category, checked in order when the type is generic. */
        public Map<String, String> keywordCategories = defaultKeywords();
    }

    private static Map<String, String> defaultKeywords() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("seagrass", "kelp", "coral", "sea_pickle", "lily_pad")) m.put(k, "aquatic_vegetation");
        m.put("lake", "fluid");
        m.put("spring", "fluid");
        m.put("flower", "flowers");
        m.put("mushroom", "mushrooms");
        m.put("bush", "bushes");
        m.put("berry", "bushes");
        m.put("grass", "grass");
        m.put("fern", "grass");
        m.put("bamboo", "large_vegetation");
        m.put("tree", "trees");
        for (String k : List.of("cactus", "sugar_cane", "pumpkin", "melon", "vine", "glow_lichen")) m.put(k, "small_vegetation");
        m.put("rock", "rocks");
        m.put("boulder", "rocks");
        m.put("disk", "ground_cover");
        m.put("moss", "ground_cover");
        m.put("ice", "terrain_decorations");
        return m;
    }

    public enum Layer {
        ANY,
        SURFACE_ONLY,
        UNDERGROUND_ONLY
    }

    /** Per-feature settings. Selection weight = relative chance to win a contested spot; frequency = density. */
    public static class FeatureSettings {
        public boolean enabled = true;
        @Range(min = 0, max = 4) public double frequency = 1;
        @Range(min = 0.01, max = 10) public double weight = 1;
        @Range(min = 1, max = 64) public int maxAttempts = 16;
        @Range(min = 0, max = 32, unit = "blocks") public int minSpacing = 0;
        @Range(min = 0, max = 64, unit = "blocks") public int maxSpacing = 0;
        public List<String> allowedSurfaceBlocks = new ArrayList<>();
        public List<String> allowedBiomes = new ArrayList<>();
        @Range(min = -64, max = 320, unit = "y") public int minY = -64;
        @Range(min = -64, max = 320, unit = "y") public int maxY = 320;
        @Range(min = 0, max = 16, unit = "blocks") public int maxSlope = 16;
        @Range(min = 0, max = 32, unit = "blocks") public int requiredAirAbove = 0;
        public Layer layer = Layer.ANY;
        public String category = "";
    }

    private static Map<String, JsonObject> defaultCategorySettings() {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        m.put("trees", JsonParser.parseString("{\"allowedSurfaceBlocks\":[\"#minecraft:dirt\"],\"requiredAirAbove\":4,\"maxSlope\":4,\"minSpacing\":2}").getAsJsonObject());
        m.put("large_vegetation", JsonParser.parseString("{\"requiredAirAbove\":3,\"maxSlope\":4}").getAsJsonObject());
        m.put("rocks", JsonParser.parseString("{\"maxSlope\":6}").getAsJsonObject());
        return m;
    }

    public static class Performance {
        @Range(min = 256, max = 65536, unit = "entries") public int cacheSize = 8192;
        @Range(min = 16, max = 4096) public int maxCandidatesPerChunk = 1024;
        @Range(min = 1, max = 1024) public int maxPlacementsPerChunk = 160;
        @Range(min = 1, max = 8, unit = "sections") public int undergroundSectionStride = 2;
    }

    public static class Debug {
        public boolean extremePreset = false;
        public boolean collectStats = true;
        public boolean logRejections = false;
    }

    // ---- loading ----

    public static BtgConfig get() { return current; }

    public static synchronized void load() {
        BtgConfig cfg = new BtgConfig();
        try {
            if (Files.exists(FILE)) {
                JsonElement tree = JsonParser.parseString(Files.readString(FILE));
                if (tree != null && tree.isJsonObject()) {
                    JsonObject root = tree.getAsJsonObject();
                    int version = root.has("configVersion") ? root.get("configVersion").getAsInt() : 0;
                    if (version >= MIGRATABLE) {
                        ConfigMigration.migrate(root);
                        BtgConfig read = GSON.fromJson(root, BtgConfig.class);
                        if (read != null) cfg = read;
                    } else {
                        Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        BiomeTransitions.LOGGER.warn("Old {} replaced with new defaults; previous file saved as .bak", FILE);
                    }
                }
            }
        } catch (Exception e) {
            BiomeTransitions.LOGGER.error("Invalid {}, using defaults (file left untouched)", FILE, e);
            set(cfg);
            return;
        }
        set(cfg);
        save(cfg);
    }

    /** Replace the live config (worldgen threads read it) and drop every derived cache. */
    public static synchronized void set(BtgConfig cfg) {
        current = cfg;
        BiomeTransitions.invalidateCaches();
    }

    public static synchronized void save(BtgConfig cfg) {
        try {
            cfg.configVersion = VERSION;
            Files.writeString(FILE, GSON.toJson(cfg));
        } catch (IOException e) {
            BiomeTransitions.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    public BtgConfig copy() { return GSON.fromJson(GSON.toJsonTree(this), BtgConfig.class); }
}
