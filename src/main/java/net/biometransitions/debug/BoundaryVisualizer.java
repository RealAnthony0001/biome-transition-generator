package net.biometransitions.debug;

import net.biometransitions.core.BiomeClassifier;
import net.biometransitions.core.BoundaryDetector;
import net.biometransitions.core.TransitionMath;
import net.biometransitions.core.Transitions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /btg show: red walls = boundary, green to yellow = vegetation zone (yellow = more neighbour features),
 * blue = fluid zone only. The action bar names the nearest boundary.
 */
public final class BoundaryVisualizer {
    private static final Map<UUID, Integer> RADIUS = new ConcurrentHashMap<>();

    private static final DustParticleOptions BORDER = new DustParticleOptions(new Vector3f(1, 0.05f, 0.05f), 4.0f);
    private static final DustParticleOptions FLUID = new DustParticleOptions(new Vector3f(0.2f, 0.5f, 1), 2.5f);

    private BoundaryVisualizer() {}

    private static void actionBar(ServerPlayer player, ServerLevel level, net.minecraft.world.level.chunk.ChunkGenerator gen,
                                  net.minecraft.world.level.levelgen.RandomState rs, long seed, BlockPos pos) {
        var own = level.getBiome(pos);
        var ns = BoundaryDetector.zoneMap(gen, rs, seed, pos).neighbours(pos.getX(), pos.getZ());
        String text;
        if (ns.length == 0) text = "§7" + BiomeClassifier.id(own) + " — no boundary nearby";
        else {
            double share = Transitions.shareOf(Transitions.at(gen, rs, seed, pos, own, false), ns[0].biome());
            text = String.format("§a%s §f→ §e%s §f%.1f blocks §7| neighbour features §e%.0f%%",
                    BiomeClassifier.id(own), BiomeClassifier.id(ns[0].biome()), ns[0].distance(TransitionMath.Shape.ROUND), share * 100);
        }
        player.displayClientMessage(Component.literal(text), true);
    }

    /** @return true if now on */
    public static boolean toggle(ServerPlayer player, int radius) {
        if (RADIUS.remove(player.getUUID()) != null) return false;
        RADIUS.put(player.getUUID(), radius);
        return true;
    }

    public static void tick(ServerTickEvent.Post event) {
        if (RADIUS.isEmpty() || event.getServer().getTickCount() % 5 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Integer r = RADIUS.get(player.getUUID());
            if (r != null) show(player, r);
        }
    }

    private static void show(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        long seed = level.getSeed();
        BlockPos c = player.blockPosition();
        actionBar(player, level, gen, rs, seed, c);
        for (int x = c.getX() - radius; x <= c.getX() + radius; x += 2)
            for (int z = c.getZ() - radius; z <= c.getZ() + radius; z += 2) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                var ns = BoundaryDetector.zoneMap(gen, rs, seed, pos).neighbours(x, z);
                if (ns.length == 0) continue;
                double x0 = x + 0.5, z0 = z + 0.5;
                if (ns[0].distance(TransitionMath.Shape.ROUND) < 1.5) {
                    // a wall of large red dust, 3 blocks high; one packet with a vertical spread
                    level.sendParticles(player, BORDER, true, x0, pos.getY() + 1.5, z0, 10, 0.15, 0.9, 0.15, 0);
                    continue;
                }
                double veg = Transitions.at(gen, rs, seed, pos, null, false).stream().mapToDouble(Transitions.Influence::share).sum();
                DustParticleOptions dust;
                if (veg > 0) {
                    float t = (float) Math.min(1, veg * 2); // 0 at zone edge, 1 at the boundary (50%)
                    dust = new DustParticleOptions(new Vector3f(0.2f + 0.8f * t, 1, 0.1f), 1.5f + 1.5f * t);
                } else if (!Transitions.at(gen, rs, seed, pos, null, true).isEmpty()) dust = FLUID;
                else continue;
                level.sendParticles(player, dust, true, x0, pos.getY() + 0.6, z0, 2, 0.1, 0.3, 0.1, 0);
            }
    }
}
