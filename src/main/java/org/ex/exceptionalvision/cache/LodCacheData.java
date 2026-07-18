package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.world.lod.NodeData;
import org.ex.exceptionalvision.world.lod.PackedQuad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Result of {@link LodCacheLoader#load}. When the cache exists and matches the current
 * format version, {@link #nodesBuffer()}/{@link #quadsBuffer()} are read-only views
 * positioned right after their respective headers, containing exactly {@code nodeCount}
 * {@code NodeData} records / {@code quadCount} {@code PackedQuad} records back-to-back —
 * i.e. ready to hand straight to the GPU Data Manager (stage 4) for an SSBO upload with
 * no further parsing, per {@code 06_disk_cache_format.md}.
 * <p>
 * When the cache is missing/stale, {@link #isEmpty()} is {@code true} and the buffers
 * are {@code null}; the LOD builder (stage 2) is expected to recompute everything and
 * hand the result to {@link LodCacheWriter} instead of reading from here.
 */
public final class LodCacheData {

    private final CacheIndex index;
    private final int nodeCount;
    private final long quadCount;
    private final ByteBuffer nodes;
    private final ByteBuffer quads;

    private LodCacheData(CacheIndex index, int nodeCount, long quadCount, ByteBuffer nodes, ByteBuffer quads) {
        this.index = index;
        this.nodeCount = nodeCount;
        this.quadCount = quadCount;
        this.nodes = nodes;
        this.quads = quads;
    }

    static LodCacheData empty(String modVersion, String dimensionId) {
        return new LodCacheData(CacheIndex.empty(modVersion, dimensionId), 0, 0L, null, null);
    }

    static LodCacheData of(CacheIndex index, ByteBuffer rawNodes, ByteBuffer rawQuads) {
        rawNodes.order(ByteOrder.LITTLE_ENDIAN);
        rawQuads.order(ByteOrder.LITTLE_ENDIAN);

        int nodeCount = rawNodes.getInt(0);
        long quadCount = rawQuads.getLong(0);

        ByteBuffer nodeRecords = sliceRecords(rawNodes, LodCacheFormat.NODES_HEADER_BYTES,
                (long) nodeCount * NodeData.BYTES);
        ByteBuffer quadRecords = sliceRecords(rawQuads, LodCacheFormat.QUADS_HEADER_BYTES,
                quadCount * (long) PackedQuad.BYTES);

        return new LodCacheData(index, nodeCount, quadCount, nodeRecords, quadRecords);
    }

    private static ByteBuffer sliceRecords(ByteBuffer raw, int headerBytes, long expectedBytes) {
        int available = raw.capacity() - headerBytes;
        int usable = (int) Math.min(available, expectedBytes);
        ByteBuffer slice = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(headerBytes);
        slice.limit(headerBytes + Math.max(usable, 0));
        return slice.slice().order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer();
    }

    public boolean isEmpty() {
        return nodes == null;
    }

    public CacheIndex index() {
        return index;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public long quadCount() {
        return quadCount;
    }

    /** Read-only, positioned at the first {@code NodeData} record. A fresh duplicate on every call. Always direct (even when empty), so it's always safe to pass straight to an LWJGL GL call. */
    public ByteBuffer nodesBuffer() {
        return nodes == null ? ByteBuffer.allocateDirect(0) : nodes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Read-only, positioned at the first {@code PackedQuad} record. A fresh duplicate on every call. Always direct (even when empty), so it's always safe to pass straight to an LWJGL GL call. */
    public ByteBuffer quadsBuffer() {
        return quads == null ? ByteBuffer.allocateDirect(0) : quads.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }
}
