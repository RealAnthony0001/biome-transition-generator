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
 */
public final class BtgConfig {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Range { double min(); double max(); String unit() default ""; String minLabel() default ""; }

    /** Tooltip text for the GUI, on fields and on enum constants. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    public @interface Tip { String value(); }

    /** GUI label when the field name alone does not read well. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Label { String value(); }

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
        @Tip("Master switch. Off = the mod does nothing in newly generated chunks\n(colour blending falls back to vanilla as well).")
        public boolean enabled = true;
        @Label("Mixing Distance")
        @Tip("How far features of a neighbouring biome reach into a biome, measured from the boundary.\n"
                + "0 = no feature mixing, 128 = very wide, soft transitions.\n"
                + "Example: 64 = forest trees still appear (rarely) 64 blocks deep into the plains.\n"
                + "The same setting is shown in General and in Biome Mixing; both edit one value.\n"
                + "Tree Mixing Distance, Block Blending Distance and pairs in Biome Boundary Mixing can override it.")
        @Range(min = 0, max = 128, unit = "blocks") public int mixingDistance = 64;
        @Tip("Spread the neighbour's ground (sand, grass, snow...) into the transition in natural patches,\nso its plants have something to grow on. Details in Biome Mixing → Surface block blending.")
        public boolean surfaceMixing = true;
        @Tip("Replace too small biome areas (see Biome Replacing), e.g. tiny oceans in the middle of a river system.")
        public boolean biomeReplacing = true;
        @Label("Max Biome Color Blending")
        @Tip("Largest distance over which grass, foliage and water colours blend between biomes (client side).\n"
                + "0 = vanilla colour blending (Options → Video Settings → Biome Blend).\n"
                + "Example: 32 = the grass colour changes gradually over about 64 blocks across a boundary.\n"
                + "Same value as in Biome Color Blending.")
        @Range(min = 0, max = 64, unit = "blocks", minLabel = "Vanilla") public int colorBlendDistance = 32;
        @Tip("Dimension ids where the mod generates transitions, e.g. minecraft:overworld. Empty = all dimensions.")
        public List<String> dimensions = new ArrayList<>();
        @Tip("Allow surface biomes and underground (cave) biomes to mix their features.\nOff = lush caves and the forest above them stay separate.")
        public boolean mixSurfaceAndUnderground = false;
    }

    @Tip("How the feature count in a transition is decided.")
    public enum DensityMode {
        @Tip("The whole transition gets the average density of both biomes.\nExample: forest 100 trees, plains 40 trees -> 70 trees everywhere in the zone.")
        AVERAGE,
        @Tip("Density changes smoothly with the distance from the boundary.\nExample: 100 trees deep in the forest, falling to 40 towards the plains.")
        GRADUAL
    }

    public enum VarietyBalancing {
        @Tip("No balancing: a biome with many kinds of features shows all of them at full rate.") DISABLED,
        @Tip("A neighbour with more kinds of features than this biome has each kind thinned, so it cannot dominate the mix.") ENABLED,
        @Tip("Balance only trees and foliage; rocks and other features keep full rate.") FOLIAGE_ONLY
    }

    public enum IntersectionPolicy {
        @Tip("Every neighbour mixes in by its own Biome Boundary Mixing rules.") RESPECT_RULES,
        @Tip("Where three or more biomes meet (two or more neighbours reach a spot), nothing is mixed.") DO_NOT_BLEND
    }

    public enum MixMode {
        @Tip("The two groups never mix their features.") DO_NOT_BLEND,
        @Tip("Features of group A spread into group B; B stays out of A.") A_INTO_B,
        @Tip("Features of group B spread into group A; A stays out of B.") B_INTO_A,
        @Tip("Both groups spread their features into each other.") BOTH
    }

    /** Direction of surface block blending in a pair, independent of the feature direction. */
    public enum BlockBlend {
        @Tip("Ground of both biomes spreads into the opposite biome.") BLEND_BOTH("Blend Both"),
        @Tip("Ground of group A (e.g. sand) can appear in group B; B's ground stays out of A.") A_INTO_B("Blend A into B"),
        @Tip("Ground of group B can appear in group A; A's ground stays out of B.") B_INTO_A("Blend B into A"),
        @Tip("Surface blocks are not mixed between the two groups.") NO_BLENDING("No Blending");

        private final String label;

        BlockBlend(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public static class Density {
        @Tip("Density mode for trees, bushes, grass, flowers and other plants.")
        public DensityMode treesAndFoliage = DensityMode.GRADUAL;
        @Tip("Density mode for everything else: rocks, ground patches, terrain decorations...")
        public DensityMode other = DensityMode.GRADUAL;
    }

    public static class Mixing {
        public Density density = new Density();
        public Distribution distribution = new Distribution();
        @Tip("Keeps a biome with many kinds of features from flooding the transition with them.\nExample: a jungle (many plants) next to plains (few) has each jungle plant thinned.")
        public VarietyBalancing varietyBalancing = VarietyBalancing.FOLIAGE_ONLY;
        @Tip("Mixing distance multiplier for biomes of a similar group (both forests, both taigas...).\n"
                + "1 = same as other pairs, 2 = twice as wide.\n"
                + "Applies to the global Mixing, Tree and Block Blending distances, not to pairs with their own distance.")
        @Range(min = 0.25, max = 4) public double similarDistanceMultiplier = 1.5;
        @Tip("How far trees of a neighbouring biome reach into a biome, separately from the other features.\n"
                + "Left end = same as Mixing Distance. Rocks, plants and other vegetation keep using Mixing Distance.\n"
                + "Example: Mixing Distance 48 and Tree Mixing Distance 96 = flowers and grass mix over 48 blocks,\n"
                + "while single trees of the neighbour still appear up to 96 blocks deep.\n"
                + "Pairs in Biome Boundary Mixing can override it with their own Tree Distance.")
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "= Mixing Distance") public int treeMixingDistance = -1;
        @Tip("What happens where three or more biomes meet.")
        public IntersectionPolicy intersectionPolicy = IntersectionPolicy.RESPECT_RULES;
        @Tip("Biomes that do not blend with anything: no features, no surface blocks and no snow cross their boundary,\n"
                + "in either direction. Wins over every pair in Biome Boundary Mixing.\n"
                + "Accepts biome ids (minecraft:river), tags (#minecraft:is_ocean) and group names (ocean).")
        public List<String> noBlend = new ArrayList<>(List.of("ocean", "river", "beach"));
        @Tip("Biomes that blend only with their own group. Join groups with + to make one group:\ndesert+badlands = desert and badlands blend with each other, but with nothing else.")
        public List<String> blendOnlyWithSelf = new ArrayList<>(List.of("desert+badlands"));
        @Tip("Biomes whose ground never spreads and never receives foreign ground (or snow). Their features still mix.")
        public List<String> noSurfaceBlend = new ArrayList<>();
        @Tip("Biomes that act as a wall: features do not mix between biomes on opposite sides of them.\nExample: river = forest trees do not jump over a river into the plains.")
        public List<String> barriers = new ArrayList<>(List.of("river"));
        public Surface surface = new Surface();
    }

    /** Feature mixing between two biome groups. */
    public static class PairRule {
        @Tip("Group A: biome id (minecraft:forest), tag (#minecraft:is_forest) or group name (forest).")
        public String a = "";
        @Tip("Group B: biome id (minecraft:plains), tag (#c:is_plains) or group name (plains).")
        public String b = "";
        @Label("Feature Direction")
        @Tip("Which direction features (trees, plants, rocks...) spread in. Surface blocks have their own direction below.")
        public MixMode mode = MixMode.BOTH;
        @Label("Mixing Distance")
        @Tip("How far features reach across the boundary for this pair.\nGlobal = Mixing Distance from General (times the similar-group multiplier).")
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "Global") public int distance = -1;
        @Label("Tree Distance")
        @Tip("How far trees reach across the boundary for this pair.\nGlobal = Tree Mixing Distance from Biome Mixing; when that is \"= Mixing Distance\", this pair's Mixing Distance.")
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "Global") public int treeDistance = -1;
        @Tip("Density mode for trees and foliage. Global = setting from Biome Mixing.")
        @Inherit public DensityMode treesAndFoliageDensity;
        @Tip("Density mode for other features. Global = setting from Biome Mixing.")
        @Inherit public DensityMode otherDensity;
        @Tip("Mixing distribution curve for this pair (features and surface blocks). Global = setting from Biome Mixing.")
        @Inherit public Curve curve;
        @Tip("Feature variety balancing. Global = setting from Biome Mixing.")
        @Inherit public VarietyBalancing varietyBalancing;
        @Label("Block Blending Direction")
        @Tip("Which direction surface blocks (sand, grass, snow...) spread in, independently of the Feature Direction.\n"
                + "Example: desert + jungle with Feature Direction BOTH and Block Blending A into B = sand spreads into the jungle,\n"
                + "jungle grass never appears in the desert, while plants of both still mix.\n"
                + "Needs Surface Mixing on in General; No Blend and No Surface Blend lists always win.")
        public BlockBlend blockDirection = BlockBlend.BLEND_BOTH;
        @Label("Block Blending Distance from Boundary")
        @Tip("How far surface blocks of the other biome may appear from the boundary for this pair.\nGlobal = Block Blending Distance from Boundary in Biome Mixing.")
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "Global") public int blockDistance = -1;

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
        @Tip("Nearest biome of the same group (a small birch forest becomes the surrounding forest).") NEAREST_SAME_GROUP,
        @Tip("Nearest biome with the most similar climate (temperature and rainfall).") NEAREST_SIMILAR_GROUP,
        @Tip("By the Custom Mapping, e.g. ocean-river turns small oceans into the nearest river.") CUSTOM
    }

    public static class Replacing {
        @Tip("Measure the size of biome areas in quarts (4x4 block biome cells, the resolution Minecraft samples biomes at).\n"
                + "Needed by everything in this section: off = no biome is ever replaced and no feature is suppressed.")
        public boolean smallBiomeDetection = true;
        @Tip("Used by Overlapping Detection of small biomes: a biome counts as small when the mixing zones of its neighbours\n"
                + "(Mixing Distance x this value) cover all of it.\n"
                + "1.0 = full mixing distance; lower = only very narrow biomes count as small.\n"
                + "Example: Mixing Distance 64 and 0.4 = areas no more than about 25 blocks from their edge anywhere are small.\n"
                + "Does not change how far features mix.")
        @Range(min = 0.1, max = 2) public double overlapMultiplier = 0.4;
        @Tip("Small Biomes with Biome ID Replacement: biomes whose small areas are replaced by another biome\n(biome ids, tags, group names). Example: ocean = tiny ocean pools become river.")
        public List<String> replaceBiomes = new ArrayList<>(List.of("ocean"));
        @Tip("Areas smaller than this (in quarts = 4x4 blocks) are replaced. 306 quarts = about 70 x 70 blocks.\n-1 = Overlapping Detection: small = fully covered by the neighbours' mixing zones.")
        @Range(min = -1, max = 4096, unit = "quarts", minLabel = "Overlapping Detection") public int replaceMinSize = 306;
        @Tip("What a small biome turns into.")
        public ReplaceMode changeInto = ReplaceMode.CUSTOM;
        @Tip("Custom replacement pairs, source-target, separated by commas: ocean-river,forest-plains.\nThe nearest biome matching the target (also further away) is used; if none is found, nothing is replaced.")
        public String customMapping = "ocean-river";
        @Tip("Small Biomes with Feature Suppression: biomes that keep their id when small, but generate none of their own features.\n"
                + "The surrounding biomes' features grow there instead (by the mixing rules), and the small area\n"
                + "spreads none of its features into its neighbours. Features the surrounding biomes have as well\n"
                + "(ores, common grass...) and excluded feature types keep generating, so no holes appear.\n"
                + "Example: minecraft:birch_forest = small birch patches inside a forest grow oak forest instead.")
        public List<String> suppressFeatureBiomes = new ArrayList<>();
        @Tip("Areas smaller than this (in quarts) lose their own features. -1 = Overlapping Detection.")
        @Range(min = -1, max = 4096, unit = "quarts", minLabel = "Overlapping Detection") public int suppressMinSize = -1;
        @Tip("Biomes that are never replaced. Highest priority: wins over Small Biomes with Biome ID Replacement.")
        public List<String> neverReplace = new ArrayList<>();
    }

    public static class Colors {
        @Tip("On: colours blend as far as features mix (Mixing Distance), capped by Max Biome Color Blending.\nOff: colours always use Max Biome Color Blending.")
        public boolean sameAsMixingDistance = true;
        @Tip("Biomes that use the shorter Reduced Distance below (e.g. swamps, so their dark water stays crisp).")
        public List<String> reducedBiomes = new ArrayList<>();
        @Tip("Colour blending distance for Reduced Biomes.")
        @Range(min = 0, max = 64, unit = "blocks") public int reducedDistance = 4;
    }

    public static class Advanced {
        @Tip("Extra check that a mixed-in feature does not land directly on another feature's blocks (a rock on a rock, a plant on leaves).\nTouching and overlapping crowns stay allowed. Off = no extra checks.")
        public boolean preventFeatureOverlap = false;
        @Tip("0 = same mixing distance everywhere; 1 = very different climates get a much narrower zone.\nExample: at 0.3 a desert/snow transition is about 22% narrower than a forest/forest one.")
        @Range(min = 0, max = 1) public double climateAdaptation = 0.3;
        @Tip("How many different neighbouring biomes one spot can mix in.")
        @Range(min = 1, max = 8) public int maxNeighbours = 3;
        @Tip("Mix land features (trees, plants, rocks...).")
        public boolean vegetationTransitions = true;
        @Tip("Mix water features (seagrass, kelp...) in a separate, narrower zone.")
        public boolean fluidTransitions = true;
        @Tip("Also look for boundaries of underground biomes.")
        public boolean undergroundTransitions = true;
        public Selection selection = new Selection();
        public Fluid fluid = new Fluid();
    }

    public static class Distribution {
        @Tip("Shape of the transition: how the neighbour's share of the features falls with the distance from the boundary.\n"
                + "One curve spans the whole transition, from the zone edge in one biome across the boundary to the zone edge\n"
                + "in the other, so both sides always agree: 50/50 at the boundary when both biomes spread into each other,\n"
                + "100% neighbour at the boundary when only the neighbour spreads (A into B pairs).\n"
                + "The curve never changes how far the zone reaches; that is Mixing Distance.")
        public Curve curve = Curve.EASE_OUT;
        /** Control points over 0..1 used by the CUSTOM curve. */
        @Tip("Points of the CUSTOM curve, evenly spaced from the zone edge (first) to the boundary (last), 0..1.\n"
                + "The last point counts as the balanced mix at the boundary; the other side of the boundary mirrors the curve.\n"
                + "Example: 0, 0.1, 0.35, 0.75, 1 = the neighbour stays rare in the outer half and grows quickly near the boundary.")
        public List<Double> customCurve = new ArrayList<>(List.of(0.0, 0.1, 0.35, 0.75, 1.0));
        @Tip("Overall strength of the mix. 1 = 50/50 at the boundary.\n"
                + "Below 1 each biome keeps more of its own features (less mixing on both sides);\nabove 1 the neighbour dominates on both sides near the boundary.\n"
                + "Example: 0.5 = at most 25% neighbour features at the boundary.")
        @Range(min = 0, max = 2) public double intensity = 1;
        @Tip("Exponent on the distance inside each biome. 1 = the curve as chosen.\n"
                + "Below 1 the neighbour reaches further at high strength; above 1 it fades sooner.\n"
                + "Example: 2 = at half the distance the neighbour has about half the share it has at 1.")
        @Range(min = 0.1, max = 4) public double falloff = 1;
        @Tip("How the distance to the boundary is measured.")
        public Shape shape = Shape.ROUND;
        @Tip("Weight of the biome's own features. Higher = the neighbour comes in less.\nExample: own 3, neighbour 1 = 25% neighbour features at the boundary instead of 50%.")
        @Range(min = 0, max = 4) public double ownInfluence = 1;
        @Tip("Weight of the neighbour's features. Higher = the neighbour comes in more.\nExample: own 1, neighbour 3 = 75% neighbour features at the boundary instead of 50%.")
        @Range(min = 0, max = 4) public double neighbourInfluence = 1;
        @Tip("Pull the curve towards a smooth S shape. 0 = curve as chosen, 1 = fully smoothed.\nUseful to soften the sharp corners of CUSTOM curves.")
        @Range(min = 0, max = 1) public double smoothness = 0.2;
        @Tip("Per-column randomness of the mix inside the transition. 0 = perfectly regular.\nNever moves the zone edges, so features never reach further than the configured distance.")
        @Range(min = 0, max = 1) public double randomVariation = 0.1;
        @Tip("How much large-scale noise bends the transition, so the balanced 50/50 line wanders instead of following\n"
                + "the biome boundary exactly. 0 = straight. The NOISE curve doubles this.\nThe zone edges stay at the configured distance.")
        @Range(min = 0, max = 1) public double noiseInfluence = 0.3;
        @Tip("Size of the noise waves. Small = ragged, large = long gentle waves.")
        @Range(min = 4, max = 256, unit = "blocks") public double noiseScale = 32;
        @Tip("Thin out the biome's own features as the neighbour's come in, so the mix really shifts from A to B.\nOff = the neighbour's features are only added on top.")
        public boolean suppressNative = true;
    }

    public static class Selection {
        @Tip("Density multiplier of mixed-in features. Above 1 the zone can be denser than the source biome.")
        @Range(min = 0, max = 4) public double frequency = 1.5;
        @Tip("Feature categories that may be mixed.")
        public List<String> allowedCategories = new ArrayList<>(List.of(
                "trees", "large_vegetation", "small_vegetation", "flowers", "grass", "bushes",
                "mushrooms", "ground_cover", "rocks", "terrain_decorations", "aquatic_vegetation", "fluid", "other"));
        /** Frequency multiplier per feature category, missing = 1. Small plants (cactus, sugar cane...) are rare per chunk, so they get more tries. */
        @Tip("Density multiplier per feature category, e.g. {\"trees\": 0.5}. Missing = 1.")
        public Map<String, Double> categoryFrequency = new LinkedHashMap<>(Map.of("small_vegetation", 2.5));
        @Tip("Placed feature ids that are never mixed.")
        public List<String> excludedFeatures = new ArrayList<>();
    }

    public static class Fluid {
        @Tip("Width of the water feature zone.")
        @Range(min = 0, max = 64, unit = "blocks") public int width = 8;
        @Tip("Strength of water feature mixing.")
        @Range(min = 0, max = 2) public double intensity = 1;
        @Tip("Density multiplier of mixed-in water features.")
        @Range(min = 0, max = 4) public double frequency = 1;
        @Tip("Fluid ids or #tags that water features may use.")
        public List<String> allowedFluids = new ArrayList<>(List.of("#minecraft:water"));
        @Tip("Allow lakes and springs to be carried over. Off = no new water bodies.")
        public boolean allowFluidBodies = false;
        @Tip("Allow land features to be placed in water.")
        public boolean allowLandFeaturesInFluid = false;
        @Tip("Shore plants within this distance of water get the shore frequency.")
        @Range(min = 0, max = 8, unit = "blocks") public int shoreRadius = 3;
        @Tip("Density multiplier for shore plants next to water.")
        @Range(min = 0, max = 4) public double shoreFrequency = 1.5;
        @Tip("Categories treated as shore plants.")
        public List<String> shoreCategories = new ArrayList<>(List.of("grass", "flowers", "small_vegetation", "bushes"));
    }

    /**
     * Terrain blending: the neighbour's ground spreads in natural patches (sand into a jungle, grass into a
     * desert), so the neighbour's plants have something to grow on. On/off is {@link General#surfaceMixing}.
     */
    public static class Surface {
        @Tip("How much of the zone gets the neighbour's ground. 0 = none, 1 = about the neighbour's share, 2 = most of it.")
        @Range(min = 0, max = 2) public double intensity = 1;
        @Label("Block Blending Distance from Boundary")
        @Tip("How far from a biome boundary surface blocks (and snow) of the neighbouring biome may appear,\n"
                + "independently of how far features mix. Left end = same as the feature Mixing Distance.\n"
                + "Example: Mixing Distance 64 and 16 here = trees mix over 64 blocks, sand patches stay within 16 blocks.\n"
                + "Pairs in Biome Boundary Mixing can override it.")
        @Range(min = -1, max = 128, unit = "blocks", minLabel = "= Mixing Distance") public int blockBlendingDistance = -1;
        @Tip("Size of the ground patches. Small = many small spots, large = big blobs.")
        @Range(min = 2, max = 64, unit = "blocks") public double patchScale = 10;
        @Tip("How many blocks deep the ground is replaced.")
        @Range(min = 1, max = 6, unit = "blocks") public int depth = 3;
        @Tip("Carry snow layers of cold neighbours into the transition, in the same patches as their ground.\n"
                + "Snow is only placed where the neighbour would snow itself (cold enough at that height, open sky,\n"
                + "a solid or leafy block below, never on ice or water).")
        public boolean snowLayers = true;
        /** Biome selector -> [top block, block below]. First match of the neighbour wins; unmatched = no blending. */
        @Tip("Biome or group -> [top block, block below]. First match of the neighbour wins; biomes without an entry spread no ground.")
        public Map<String, List<String>> blocks = defaultSurfaceBlocks();
        @Tip("Only these blocks are ever replaced (ids or #tags), so stone, ores and structures stay untouched.")
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
        @Tip("Biome id -> groups. Replaces the tag rules for that biome.")
        public Map<String, List<String>> overrides = new LinkedHashMap<>(Map.of(
                "minecraft:meadow", List.of("meadow"),
                "minecraft:cherry_grove", List.of("meadow", "forest"),
                "minecraft:dark_forest", List.of("dense_forest", "forest"),
                "minecraft:stony_shore", List.of("beach"),
                "minecraft:mushroom_fields", List.of("other")));
        @Tip("Rules {tags: [...], categories: [...]}: a biome with all the tags gets the groups.")
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
        @Tip("Decoration steps whose features may be mixed.")
        public List<String> enabledSteps = new ArrayList<>(List.of("LAKES", "LOCAL_MODIFICATIONS", "FLUID_SPRINGS", "VEGETAL_DECORATION"));
        @Tip("Feature types never mixed, never thinned and never suppressed: ores, structures and order-dependent features.")
        public List<String> excludedFeatureTypes = new ArrayList<>(List.of(
                "minecraft:freeze_top_layer", "minecraft:monster_room", "minecraft:fossil", "minecraft:geode",
                "minecraft:ore", "minecraft:scattered_ore", "minecraft:replace_single_block", "minecraft:desert_well",
                "minecraft:bonus_chest", "minecraft:void_start_platform", "minecraft:end_spike", "minecraft:end_gateway",
                "minecraft:end_island", "minecraft:end_platform", "minecraft:chorus_plant"));
        /** Feature type id -> category. */
        @Tip("Feature type id -> category.")
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
        @Tip("Keyword in the placed feature id -> category, first match wins.")
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
        @Tip("Anywhere.") ANY,
        @Tip("Only at the surface, not in caves.") SURFACE_ONLY,
        @Tip("Only underground (more than 6 blocks below the surface).") UNDERGROUND_ONLY
    }

    /** Per-feature settings. Selection weight = relative chance to win a contested spot; frequency = density. */
    public static class FeatureSettings {
        @Tip("Allow features to be mixed at all.")
        public boolean enabled = true;
        @Tip("Density multiplier per feature (on top of the category and global multipliers).")
        @Range(min = 0, max = 4) public double frequency = 1;
        @Tip("Chance to win a spot several features compete for. 0.01 = almost never, 10 = almost always.")
        @Range(min = 0.01, max = 10) public double weight = 1;
        @Tip("Placement positions tried per feature and chunk.")
        @Range(min = 1, max = 64) public int maxAttempts = 16;
        @Tip("Minimum distance between two placements of the same feature (within a chunk). 0 = off.")
        @Range(min = 0, max = 32, unit = "blocks") public int minSpacing = 0;
        @Tip("0 = off; otherwise each placement must be within this distance of an earlier one (clusters).")
        @Range(min = 0, max = 64, unit = "blocks") public int maxSpacing = 0;
        @Tip("Block ids or #tags allowed directly below the feature. Empty = any.\nExample: #minecraft:dirt = only on grass, dirt, podzol...")
        public List<String> allowedSurfaceBlocks = new ArrayList<>();
        @Tip("Biomes the feature may be mixed into (ids, tags, group names). Empty = any.")
        public List<String> allowedBiomes = new ArrayList<>();
        @Tip("Lowest Y the feature may be placed at.")
        @Range(min = -64, max = 320, unit = "y") public int minY = -64;
        @Tip("Highest Y the feature may be placed at.")
        @Range(min = -64, max = 320, unit = "y") public int maxY = 320;
        @Tip("Largest height difference to the neighbouring columns. 16 = no limit.")
        @Range(min = 0, max = 16, unit = "blocks") public int maxSlope = 16;
        @Tip("Free blocks required above the spot (e.g. 4 for trees).")
        @Range(min = 0, max = 32, unit = "blocks") public int requiredAirAbove = 0;
        @Tip("Where the feature may be placed.")
        public Layer layer = Layer.ANY;
        @Tip("Category override; empty = automatic.")
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
        @Tip("Boundary results kept in memory. More = less recomputation, more RAM.")
        @Range(min = 256, max = 65536, unit = "entries") public int cacheSize = 8192;
        @Tip("Upper limit of mixed-in feature candidates per chunk and step.")
        @Range(min = 16, max = 4096) public int maxCandidatesPerChunk = 1024;
        @Tip("Upper limit of mixed-in features actually placed per chunk and step.")
        @Range(min = 1, max = 1024) public int maxPlacementsPerChunk = 160;
        @Tip("Check every n-th 16-block section below the surface for underground boundaries. 1 = every section (slowest).")
        @Range(min = 1, max = 8, unit = "sections") public int undergroundSectionStride = 2;
    }

    public static class Debug {
        @Tip("Testing only: biomes generate no features of their own outside transitions,\nso the transition zones stand out clearly. Never use in a real world.")
        public boolean extremePreset = false;
        @Tip("Count placements and rejections for /btg stats.")
        public boolean collectStats = true;
        @Tip("Write every rejected placement to the log (very verbose).")
        public boolean logRejections = false;
    }

    /** Tooltip of an enum constant declared outside this class (curves, shapes), or null. */
    public static String externalTip(Object constant) {
        if (constant instanceof Curve c) return switch (c) {
            case LINEAR -> "The neighbour's share falls at a constant rate from the boundary to the zone edge.";
            case SMOOTHSTEP -> "One S curve over the whole transition: the mix changes fastest at the boundary and eases out softly\ntowards both zone edges.";
            case EASE_IN -> "The neighbour's share drops quickly near the boundary, then thins out slowly towards the zone edge.";
            case EASE_OUT -> "The neighbour stays strong for a long way from the boundary, then fades quickly near the zone edge.";
            case EASE_IN_OUT -> "A steeper S curve over the whole transition: a sharp change at the boundary, long soft tails.";
            case LOGARITHMIC -> "The neighbour's share drops very fast right next to the boundary, then decreases ever more slowly,\n"
                    + "leaving a long thin tail of rare neighbour features up to the zone edge.\n"
                    + "Example: at half the distance roughly 10% neighbour features are left (LINEAR: about 25%).";
            case CUSTOM -> "Your own shape from the Custom Curve points.";
            case NOISE -> "Like LINEAR, with twice the Noise Influence for a strongly irregular transition.";
        };
        if (constant instanceof Shape s) return switch (s) {
            case ROUND -> "Circular distance: zones follow the boundary smoothly.";
            case SQUARE -> "Chessboard distance: zones are wider diagonally.";
            case DIAMOND -> "Manhattan distance: zones are narrower diagonally.";
        };
        return null;
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
