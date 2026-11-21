package net.biometransitions.client;

import net.biometransitions.config.BtgConfig;
import net.biometransitions.core.BiomeClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;

/**
 * Averages biome colours over a square with a tent weight (closer = stronger). Samples on a coarse grid
 * (at most 13 x 13 points) so even a 64-block radius costs less than vanilla's widest blend setting.
 */
public final class ColorBlending {
    private ColorBlending() {}

    /** Blended colour, or null to let vanilla do it (feature off or radius 0). */
    public static Integer tint(Level level, BlockPos pos, ColorResolver resolver) {
        BtgConfig cfg = BtgConfig.get();
        int r = cfg.general.colorBlendDistance;
        if (!cfg.general.enabled || r <= 0) return null;
        if (cfg.colors.sameAsMixingDistance) r = Math.min(r, cfg.general.mixingDistance);
        if (!cfg.colors.reducedBiomes.isEmpty() && BiomeClassifier.listed(cfg.colors.reducedBiomes, level.getBiome(pos)))
            r = Math.min(r, cfg.colors.reducedDistance);
        if (r <= 0) return null;
        int step = Math.max(1, (r + 5) / 6);
        double red = 0, green = 0, blue = 0, total = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx += step)
            for (int dz = -r; dz <= r; dz += step) {
                double w = (r + 1 - Math.abs(dx)) * (double) (r + 1 - Math.abs(dz));
                m.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                int c = resolver.getColor(level.getBiome(m).value(), m.getX(), m.getZ());
                red += w * (c >> 16 & 255);
                green += w * (c >> 8 & 255);
                blue += w * (c & 255);
                total += w;
            }
        return (int) (red / total) << 16 | (int) (green / total) << 8 | (int) (blue / total);
    }
}
