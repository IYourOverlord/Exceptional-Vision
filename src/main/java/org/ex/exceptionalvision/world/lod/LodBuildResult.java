package org.ex.exceptionalvision.world.lod;

import org.ex.exceptionalvision.world.RegionCoordinate;

import java.util.List;

/**
 * Result of {@link LodBuilder#buildRegion}: the whole quadtree for one region,
 * flattened into the same shape {@code LodCacheWriter#writeFull} (stage 3) expects —
 * a {@link NodeData} list with {@code quadOffset}s already resolved against the
 * parallel {@link PackedQuad} list — plus per-level statistics for verifying the
 * build against the stage-2 readiness criterion.
 */
public record LodBuildResult(
        RegionCoordinate coordinate,
        List<NodeData> nodes,
        List<PackedQuad> quads,
        List<LevelStats> statsByLevel) {
}
