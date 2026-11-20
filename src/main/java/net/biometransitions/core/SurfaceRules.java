package net.biometransitions.core;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.FeatureSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/** Terrain and surface compatibility of one placement. */
public final class SurfaceRules {
    private SurfaceRules() {}

    /** Reason the feature may not go at pos, or null. */
    public static String check(WorldGenLevel level, BlockPos pos, FeatureCatalog.Entry e, BtgConfig.Fluid fluid, RandomSource random) {
        FeatureSettings s = e.settings();
        if (pos.getY() < s.minY || pos.getY() > s.maxY) return "height out of range";
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ());
        boolean underground = pos.getY() < surface - 6;
        if (s.layer == BtgConfig.Layer.SURFACE_ONLY && underground) return "underground";
        if (s.layer == BtgConfig.Layer.UNDERGROUND_ONLY && !underground) return "above ground";

        FluidState here = level.getFluidState(pos);
        switch (e.category()) {
            case "aquatic_vegetation" -> {
                if (here.isEmpty()) return "aquatic feature on dry land";
                if (!fluidAllowed(here, fluid.allowedFluids)) return "fluid not allowed";
            }
            case "fluid" -> {
                FluidState placed = placedFluid(e, random, pos);
                if (placed == null || !fluidAllowed(placed, fluid.allowedFluids)) return "fluid not allowed";
            }
            default -> {
                if (!here.isEmpty() && !fluid.allowLandFeaturesInFluid) return "land feature in fluid";
            }
        }

        if (!s.allowedSurfaceBlocks.isEmpty() && !blockAllowed(level.getBlockState(pos.below()), s.allowedSurfaceBlocks))
            return "surface block not allowed";
        for (int i = 0; i < s.requiredAirAbove; i++)
            if (!level.isEmptyBlock(pos.above(i))) return "no air space";
        if (s.maxSlope < 16 && slope(level, pos) > s.maxSlope) return "slope too steep";
        return null;
    }

    /** Land features listed as shore categories get the shore multiplier next to water. */
    public static boolean nearWater(WorldGenLevel level, BlockPos pos, int radius) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int dy = -1; dy <= 0; dy++)
                    if (level.getFluidState(m.setWithOffset(pos, dx, dy, dz)).is(Fluids.WATER)) return true;
        return false;
    }

    private static int slope(WorldGenLevel level, BlockPos pos) {
        int h = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ()), max = 0;
        for (Direction d : Direction.Plane.HORIZONTAL)
            max = Math.max(max, Math.abs(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX() + d.getStepX(), pos.getZ() + d.getStepZ()) - h));
        return max;
    }

    @SuppressWarnings("deprecation")
    private static FluidState placedFluid(FeatureCatalog.Entry e, RandomSource random, BlockPos pos) {
        Object config = e.feature().value().feature().value().config();
        if (config instanceof LakeFeature.Configuration lake) return lake.fluid().getState(random, pos).getFluidState();
        if (config instanceof SpringConfiguration spring) return spring.state;
        return null; // unknown modded fluid feature: refuse rather than flood
    }

    public static boolean fluidAllowed(FluidState f, List<String> allowed) {
        String id = String.valueOf(BuiltInRegistries.FLUID.getKey(f.getType()));
        for (String a : allowed) {
            if (a.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(a.substring(1));
                if (rl != null && f.is(TagKey.create(Registries.FLUID, rl))) return true;
            } else if (a.equals(id)) return true;
        }
        return false;
    }

    public static boolean blockAllowed(BlockState state, List<String> allowed) {
        String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        for (String a : allowed) {
            if (a.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(a.substring(1));
                if (rl != null && state.is(TagKey.create(Registries.BLOCK, rl))) return true;
            } else if (a.equals(id)) return true;
        }
        return false;
    }
}
