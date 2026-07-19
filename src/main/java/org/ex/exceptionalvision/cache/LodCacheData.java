package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.world.lod.NodeData;
import org.ex.exceptionalvision.world.lod.PackedQuad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

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

        int physicalNodeCount = rawNodes.getInt(0);
        long quadCount = rawQuads.getLong(0);

        ByteBuffer physicalNodeRecords = sliceRecords(rawNodes, LodCacheFormat.NODES_HEADER_BYTES,
                (long) physicalNodeCount * NodeData.BYTES);
        ByteBuffer quadRecords = sliceRecords(rawQuads, LodCacheFormat.QUADS_HEADER_BYTES,
                quadCount * (long) PackedQuad.BYTES);

        // FIX: nodes.bin has no compactor yet (see LodCacheWriter's class javadoc) - a
        // region reprocessed via appendRegion (its node count changed, e.g. more chunks
        // finished generating in the background between two passes) leaves its OLD node
        // block sitting in nodes.bin, physically intact, right alongside the new one.
        // index.regionsProcessed() only ever keeps the latest RegionCacheEntry per region
        // coordinate (mergeIndexEntry replaces, not appends) - so it's exactly the set of
        // node ranges that are still "live". Reading physicalNodeCount records straight off
        // the file header, as this used to do, uploaded the orphaned blocks too: stale
        // geometry (built from an earlier, sometimes still-mid-worldgen state - hence odd
        // colors) rendered overlapping/duplicated with the current one. Filter down to just
        // the live ranges here so the GPU never sees the orphans, rather than trying to
        // physically compact nodes.bin (a bigger change, still not done - see known gaps).
        ByteBuffer nodeRecords = filterToLiveRanges(physicalNodeRecords, index);

        return new LodCacheData(index, nodeRecords.remaining() / NodeData.BYTES, quadCount, nodeRecords, quadRecords);
    }

    /**
     * Copies out only the node records covered by one of {@code index}'s (already
     * deduplicated-per-region) entries, in increasing {@code nodeIndexStart} order,
     * skipping any gaps - i.e. exactly the orphaned ranges left behind by
     * {@code appendRegion} reprocessing a region. Defensive against an out-of-range or
     * corrupt entry (clamps to what's actually in {@code physicalNodeRecords} rather than
     * throwing - a stale/bad index entry should degrade to "missing some geometry", not
     * crash the world load).
     */
    private static ByteBuffer filterToLiveRanges(ByteBuffer physicalNodeRecords, CacheIndex index) {
        int physicalCount = physicalNodeRecords.remaining() / NodeData.BYTES;

        List<RegionCacheEntry> ranges = new ArrayList<>(index.regionsProcessed());
        ranges.sort(java.util.Comparator.comparingInt(RegionCacheEntry::nodeIndexStart));

        ByteBuffer live = ByteBuffer.allocateDirect(physicalNodeRecords.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer source = physicalNodeRecords.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (RegionCacheEntry entry : ranges) {
            int start = Math.max(0, entry.nodeIndexStart());
            int end = Math.min(physicalCount, entry.nodeIndexStart() + entry.nodeCount());
            if (end <= start) {
                continue; // empty, or entirely out of range against what's physically on disk
            }
            ByteBuffer rangeView = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            rangeView.position(start * NodeData.BYTES);
            rangeView.limit(end * NodeData.BYTES);
            live.put(rangeView);
        }
        live.flip();
        return live.asReadOnlyBuffer();
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