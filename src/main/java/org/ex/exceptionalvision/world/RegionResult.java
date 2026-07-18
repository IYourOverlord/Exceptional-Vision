package org.ex.exceptionalvision.world;

import java.util.List;

/**
 * Result of reading one region file: its coordinate plus the height data of
 * every generated chunk found inside it. Feeds into the LOD Builder (stage 2).
 */
public record RegionResult(RegionCoordinate coordinate, List<ChunkHeightData> chunks) {
}
