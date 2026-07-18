package org.ex.exceptionalvision.world.lod;

import java.util.List;

/**
 * Mutable quadtree node built while downsampling a region (stage 2). Every node, at
 * every level, holds its own {@link ColumnGrid} and its own greedy-meshed quads —
 * {@link LodBuilder} builds the whole test-region pyramid up front so every level can
 * be verified independently (see the stage-2 readiness criterion in
 * {@code 08_roadmap_milestones.md}: "корректное дерево узлов с ожидаемым количеством
 * квадов на каждом уровне").
 * <p>
 * This intentionally does not chase the {@code children}-nullability streaming model
 * hinted at in {@code 03_data_formats.md} ("children — null для листа с построенными
 * данными"), where only currently-needed levels would hold live quad data and unneeded
 * ones get unloaded — that demand-driven behavior belongs to the GPU streaming system
 * (stage 4/5), once there's an actual distance-based level-selection policy to drive
 * it. This class doesn't even keep {@code children} references, since stage 2 has no
 * consumer for them yet; {@link LodBuilder} discards each level's node grid once the
 * next level up has been downsampled from it.
 */
public final class LodNode {

    public final int level;
    public final int chunkSpan;
    public final int originChunkX;
    public final int originChunkZ;

    ColumnGrid grid;
    List<PackedQuad> quads = List.of();

    /**
     * True if this node was built from at least one chunk that wasn't fully generated
     * yet ({@code ChunkStatus != full}, see {@code ChunkHeightData#complete()}), or from
     * a quadrant with no data at all. Such nodes are candidates for rebuilding once more
     * of the surrounding world has generated; actually re-triggering that rebuild is a
     * stage 3/5 concern (e.g. via {@code LodCacheWriter#markRegionProcessed} /
     * {@code needsRecompute}), not implemented by this class.
     */
    boolean dirty;

    LodNode(int level, int chunkSpan, int originChunkX, int originChunkZ) {
        this.level = level;
        this.chunkSpan = chunkSpan;
        this.originChunkX = originChunkX;
        this.originChunkZ = originChunkZ;
    }

    public boolean isDirty() {
        return dirty;
    }

    public float minWorldX() {
        return originChunkX * 16f;
    }

    public float minWorldZ() {
        return originChunkZ * 16f;
    }

    public float maxWorldX() {
        return minWorldX() + chunkSpan * 16f;
    }

    public float maxWorldZ() {
        return minWorldZ() + chunkSpan * 16f;
    }
}
