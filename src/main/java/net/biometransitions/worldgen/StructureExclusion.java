package net.biometransitions.worldgen;

import net.biometransitions.config.BtgConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure-Aware Feature Generation: no trees, plants or rocks inside structures (village houses, paths...)
 * or within the exclusion distance around them.
 * <p>
 * Works on the real pieces of each structure, not on the structure's overall box, so the empty land between
 * the houses of a village keeps its features. Feature generation of a chunk may read the structure starts of
 * every chunk up to 8 chunks away (vanilla needs them to place structures), so a structure is seen from every
 * chunk it reaches into, across chunk boundaries, without loading anything extra.
 * <p>
 * The piece boxes near a chunk are collected once, when the chunk is decorated, and cached; each feature
 * placement afterwards only tests its position against that short list.
 */
public final class StructureExclusion {
    /** How far around the decorated chunk pieces are collected; features may start a little outside it. */
    private static final int MARGIN = 16;
    /** Extra reach below a piece: a tree whose trunk starts a few blocks under a house would grow into it. */
    private static final int BELOW = 8;
    /** Structure starts are readable this many chunks around a chunk in feature generation. */
    private static final int START_RADIUS = 8;

    private record Key(ResourceKey<Level> dimension, long chunk) {}

    private static final Map<Key, BoundingBox[]> AREAS = new ConcurrentHashMap<>();
    private static final Map<Structure, Boolean> IGNORED = new ConcurrentHashMap<>();
    private static final BoundingBox[] NONE = new BoundingBox[0];

    private StructureExclusion() {}

    public static void clear() {
        AREAS.clear();
        IGNORED.clear();
    }

    /** True when a feature of this category must not be placed at {@code pos}. */
    public static boolean excluded(WorldGenLevel level, BlockPos pos, String category) {
        var s = BtgConfig.get().structures;
        if (!s.enabled || !s.categories.contains(category)) return false;
        // Only during chunk generation: elsewhere (e.g. /place) structures are already built into the world.
        if (!(level instanceof WorldGenRegion region)) return false;
        for (BoundingBox b : areas(region, s)) if (b.isInside(pos)) return true;
        return false;
    }

    /** Occupied areas (piece boxes plus the exclusion distance) around the chunk being decorated. */
    private static BoundingBox[] areas(WorldGenRegion region, BtgConfig.Structures s) {
        var center = region.getCenter();
        Key key = new Key(region.getLevel().dimension(), center.toLong());
        BoundingBox[] cached = AREAS.get(key);
        if (cached != null) return cached;

        int d = s.exclusionDistance;
        BoundingBox around = new BoundingBox(center.getMinBlockX() - MARGIN, region.getMinBuildHeight(), center.getMinBlockZ() - MARGIN,
                center.getMaxBlockX() + MARGIN, region.getMaxBuildHeight(), center.getMaxBlockZ() + MARGIN);
        List<BoundingBox> out = new ArrayList<>();
        for (int x = center.x - START_RADIUS; x <= center.x + START_RADIUS; x++)
            for (int z = center.z - START_RADIUS; z <= center.z + START_RADIUS; z++) {
                if (!region.hasChunk(x, z)) continue;
                ChunkAccess chunk = region.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, false);
                if (chunk == null) continue;
                for (var e : chunk.getAllStarts().entrySet()) {
                    StructureStart start = e.getValue();
                    if (!start.isValid() || ignored(region, e.getKey(), s)) continue;
                    // whole structure out of reach: skip its pieces
                    if (!start.getBoundingBox().inflatedBy(d + BELOW).intersects(around)) continue;
                    for (StructurePiece piece : start.getPieces()) {
                        BoundingBox b = piece.getBoundingBox();
                        BoundingBox area = new BoundingBox(b.minX() - d, b.minY() - d - BELOW, b.minZ() - d,
                                b.maxX() + d, b.maxY() + d, b.maxZ() + d);
                        if (area.intersects(around)) out.add(area);
                    }
                }
            }
        BoundingBox[] result = out.isEmpty() ? NONE : out.toArray(BoundingBox[]::new);
        if (AREAS.size() >= s.cacheSize) AREAS.clear(); // shortcut: whole-cache clear; entries are only hot while a chunk decorates
        AREAS.put(key, result);
        return result;
    }

    /** Structures from the ignore list (ids or #tags) never block features. */
    private static boolean ignored(WorldGenRegion region, Structure structure, BtgConfig.Structures s) {
        if (s.ignoredStructures.isEmpty()) return false;
        return IGNORED.computeIfAbsent(structure, st -> {
            var holder = region.registryAccess().registryOrThrow(Registries.STRUCTURE).wrapAsHolder(st);
            String id = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
            for (String sel : s.ignoredStructures) {
                sel = sel.trim();
                if (sel.startsWith("#")) {
                    ResourceLocation tag = ResourceLocation.tryParse(sel.substring(1));
                    if (tag != null && holder.is(TagKey.create(Registries.STRUCTURE, tag))) return true;
                } else if (sel.equals(id)) return true;
            }
            return false;
        });
    }
}
