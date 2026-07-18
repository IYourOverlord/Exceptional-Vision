package org.ex.exceptionalvision.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Downsampled per-column data for a single 16x16 chunk, extracted directly from
 * a chunk's NBT without going through {@code ChunkAccess}/{@code ServerLevel}.
 * <p>
 * Column index for local (x, z) in 0..15 is {@code x + z * 16}, matching the
 * iteration order used throughout {@code 03_data_formats.md} greedy meshing.
 * <p>
 * This is intentionally a thin, allocation-light carrier: the {@code LodBuilder}
 * (stage 2) consumes this to perform quadtree downsampling and greedy meshing.
 *
 * @param chunkX       chunk X in chunk-space (world blockX = chunkX * 16)
 * @param chunkZ       chunk Z in chunk-space (world blockZ = chunkZ * 16)
 * @param topY         top opaque block Y per column (256 entries); {@link Integer#MIN_VALUE}
 *                     if the column contains no opaque block in the loaded sections
 * @param topState     top opaque {@link BlockState} per column (256 entries); {@code null}
 *                     if the column contains no opaque block in the loaded sections
 * @param complete     {@code false} if the chunk's status was below {@code full} and/or some
 *                     sections were missing — such chunks should be treated as provisional
 *                     and re-queued once the world generator (or Chunky) finishes them
 */
public record ChunkHeightData(int chunkX, int chunkZ, int[] topY, BlockState[] topState, boolean complete) {

    public static final int COLUMNS_PER_CHUNK = 16 * 16;

    public ChunkHeightData {
        if (topY.length != COLUMNS_PER_CHUNK || topState.length != COLUMNS_PER_CHUNK) {
            throw new IllegalArgumentException("topY/topState must have exactly " + COLUMNS_PER_CHUNK + " entries");
        }
    }

    public static int columnIndex(int localX, int localZ) {
        return (localX & 15) + (localZ & 15) * 16;
    }

    public int topY(int localX, int localZ) {
        return topY[columnIndex(localX, localZ)];
    }

    public BlockState topState(int localX, int localZ) {
        return topState[columnIndex(localX, localZ)];
    }
}
