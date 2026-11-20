package net.biometransitions.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 */
public final class BtgConfig {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Range { double min(); double max(); String unit() default ""; }

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve(BiomeTransitions.MODID + ".json");
    private static volatile BtgConfig current = new BtgConfig();

    /** Bumped when defaults change enough that old files should be replaced (old file is kept as .bak). */
    public static final int VERSION = 3;
    public int configVersion = 0; // missing in old files = 0; set on save

    // ---- sections ----
    public General general = new General();
    public Detection detection = new Detection();
    /** One universal set of transition settings for every biome pair. */
    public TransitionSettings transition = new TransitionSettings();
    public Classification classification = new Classification();
    public FeatureRules features = new FeatureRules();
    /** Default per-feature settings, including surface compatibility rules. */
    public FeatureSettings featureDefaults = new FeatureSettings();
    /** Partial {@link FeatureSettings} per feature category, e.g. trees need dirt below. */
    public Map<String, JsonObject> categorySettings = defaultCategorySettings();
    /** Partial {@link FeatureSettings} per placed feature id. */
    public Map<String, JsonObject> featureOverrides = new LinkedHashMap<>();
    public Performance performance = new Performance();
    public Debug debug = new Debug();

    public static class General {
        public boolean enabled = true;
        public boolean vegetationTransitions = true;
        public boolean fluidTransitions = true;
        public boolean undergroundTransitions = true;
        /** Dimension ids; empty = all dimensions. */
        public List<String> dimensions = new ArrayList<>();
    }

    public enum MultiBiomeMode { NEAREST_PAIR, ALL_NEIGHBOURS }

    public static class Detection {
        @Range(min = 8, max = 128, unit = "blocks") public int maxDetectionDistance = 48;
        @Range(min = 1, max = 8) public int maxNeighbours = 3;
        public MultiBiomeMode multiBiomeMode = MultiBiomeMode.ALL_NEIGHBOURS;
    }

    public static class TransitionSettings {
        public Zone zone = new Zone();
        public Distribution distribution = new Distribution();
        public Selection selection = new Selection();
        public Fluid fluid = new Fluid();
        public Surface surface = new Surface();
    }

    public static class Zone {
        /** How far a neighbour's features reach into a biome. */
        @Range(min = 0, max = 128, unit = "blocks") public int width = 64;
        /** 0 = same width everywhere; 1 = very different climates (desert/snow) get a much narrower zone. */
        @Range(min = 0, max = 1) public double climateAdaptation = 0.3;
        @Range(min = 0, max = 2) public double intensity = 1;
        @Range(min = 0.1, max = 4) public double falloff = 1;
        public Shape shape = Shape.ROUND;
    }

    public static class Distribution {
        @Range(min = 0, max = 4) public double ownInfluence = 1;
        @Range(min = 0, max = 4) public double neighbourInfluence = 1;
        public Curve curve = Curve.EASE_OUT;
        /** Control points over 0..1 used by the CUSTOM curve. */
        public List<Double> customCurve = new ArrayList<>(List.of(0.0, 0.1, 0.35, 0.75, 1.0));
        @Range(min = 0, max = 1) public double smoothness = 0.2;
        @Range(min = 0, max = 1) public double randomVariation = 0.1;
        @Range(min = 0, max = 1) public double noiseInfluence = 0.3;
        @Range(min = 4, max = 256, unit = "blocks") public double noiseScale = 32;
        /** Thin out the biome's own features as the foreign ones come in. */
        public boolean suppressNative = true;
    }

    public static class Selection {
        /** Above 1 the zone gets denser than the source biome itself. */
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
        /** Fluid ids or #tags that aquatic and fluid features may use. */
        public List<String> allowedFluids = new ArrayList<>(List.of("#minecraft:water"));
        /** Allow lakes and springs to be carried over. Off by default: no new water bodies. */
        public boolean allowFluidBodies = false;
        public boolean allowLandFeaturesInFluid = false;
        @Range(min = 0, max = 8, unit = "blocks") public int shoreRadius = 3;
        @Range(min = 0, max = 4) public double shoreFrequency = 1.5;
        public List<String> shoreCategories = new ArrayList<>(List.of("grass", "flowers", "small_vegetation", "bushes"));
    }

    /**
     * Terrain blending: the neighbour's ground spreads in natural patches (sand into a jungle, grass into a
     * desert), so the neighbour's plants have something to grow on. Separate from vegetation and fluids.
     */
    public static class Surface {
        public boolean enabled = true;
        @Range(min = 0, max = 2) public double intensity = 1;
        /** Size of the patches. */
        @Range(min = 2, max = 64, unit = "blocks") public double patchScale = 10;
        @Range(min = 1, max = 6, unit = "blocks") public int depth = 3;
        /** Biome id or #category -> [top block, block below]. First match of the neighbour wins; unmatched = no blending. */
        public Map<String, List<String>> blocks = defaultSurfaceBlocks();
        /** Only these blocks are ever replaced (ids or #tags), so stone, ores and structures stay untouched. */
        public List<String> replaceable = new ArrayList<>(List.of("#minecraft:dirt", "#minecraft:sand",
                "minecraft:sandstone", "minecraft:red_sandstone", "#minecraft:terracotta", "minecraft:gravel"));
    }

    private static Map<String, List<String>> defaultSurfaceBlocks() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("#desert", List.of("minecraft:sand", "minecraft:sandstone"));
        m.put("#beach", List.of("minecraft:sand", "minecraft:sandstone"));
        m.put("#badlands", List.of("minecraft:red_sand", "minecraft:terracotta"));
        for (String c : List.of("forest", "dense_forest", "sparse_forest", "plains", "meadow", "jungle", "taiga", "swamp", "hills"))
            m.put("#" + c, List.of("minecraft:grass_block", "minecraft:dirt"));
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
        /** Biome id -> categories; replaces the tag rules for that biome. */
        public Map<String, List<String>> overrides = new LinkedHashMap<>(Map.of(
                "minecraft:meadow", List.of("meadow"),
                "minecraft:cherry_grove", List.of("meadow", "forest"),
                "minecraft:dark_forest", List.of("dense_forest", "forest"),
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
                new TagRule("c:is_cave", "underground"),
                new TagRule("c:is_underground", "underground")));
    }

    public static class FeatureRules {
        /** Decoration steps whose features may be carried across a boundary. */
        public List<String> enabledSteps = new ArrayList<>(List.of("LAKES", "LOCAL_MODIFICATIONS", "FLUID_SPRINGS", "VEGETAL_DECORATION"));
        /** Feature types never carried over: order-dependent or structure-like. */
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

    public enum Layer { ANY, SURFACE_ONLY, UNDERGROUND_ONLY }

    /** Per-feature settings. Selection weight = relative chance to win a contested spot; frequency = density. */
    public static class FeatureSettings {
        public boolean enabled = true;
        @Range(min = 0, max = 4) public double frequency = 1;
        @Range(min = 0.01, max = 10) public double weight = 1;
        @Range(min = 1, max = 64) public int maxAttempts = 16;
        @Range(min = 0, max = 32, unit = "blocks") public int minSpacing = 0;
        /** 0 = off; otherwise each placement must be within this distance of an earlier one (clustering). */
        @Range(min = 0, max = 64, unit = "blocks") public int maxSpacing = 0;
        /** Block ids or #tags allowed directly below; empty = any. */
        public List<String> allowedSurfaceBlocks = new ArrayList<>();
        /** Target biome ids or #categories; empty = any. */
        public List<String> allowedBiomes = new ArrayList<>();
        @Range(min = -64, max = 320, unit = "y") public int minY = -64;
        @Range(min = -64, max = 320, unit = "y") public int maxY = 320;
        @Range(min = 0, max = 16, unit = "blocks") public int maxSlope = 16;
        @Range(min = 0, max = 32, unit = "blocks") public int requiredAirAbove = 0;
        public Layer layer = Layer.ANY;
        /** Category override; empty = automatic. */
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
        /** Check every n-th section below the surface for underground boundaries. */
        @Range(min = 1, max = 8, unit = "sections") public int undergroundSectionStride = 2;
    }

    public static class Debug {
        public boolean collectStats = true;
        public boolean logRejections = false;
    }

    // ---- loading ----

    public static BtgConfig get() { return current; }

    public static synchronized void load() {
        BtgConfig cfg = new BtgConfig();
        try {
            if (Files.exists(FILE)) {
                BtgConfig read = GSON.fromJson(Files.readString(FILE), BtgConfig.class);
                if (read != null && read.configVersion >= VERSION) cfg = read;
                else if (read != null) {
                    Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    BiomeTransitions.LOGGER.warn("Old {} replaced with new defaults; previous file saved as .bak", FILE);
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
