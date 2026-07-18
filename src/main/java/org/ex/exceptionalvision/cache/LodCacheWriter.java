package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.world.RegionCoordinate;
import org.ex.exceptionalvision.world.lod.NodeData;
import org.ex.exceptionalvision.world.lod.PackedQuad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Writes/updates a dimension's LOD disk cache. See {@code 06_disk_cache_format.md}.
 * <p>
 * Write paths, matching the roadmap for stage 3:
 * <ul>
 *   <li>{@link #writeFull} — (re)builds {@code nodes.bin}/{@code quads.bin}/{@code index.json}
 *       from scratch, e.g. after a format-version bump.</li>
 *   <li>{@link #appendRegion} — a region's first time being cached (or a reprocess where
 *       its node *count* changed, e.g. previously-ungenerated chunks inside it finally
 *       generated): appends the region's whole {@link org.ex.exceptionalvision.world.lod.LodBuildResult}
 *       as one new contiguous block at the end of {@code nodes.bin}/{@code quads.bin}.</li>
 *   <li>{@link #patchRegion} — reprocessing a region whose node count is unchanged (the
 *       common case: same chunks, different content): overwrites its existing node
 *       records in place, appending fresh quads for them (old quads become orphaned;
 *       {@link CacheCompactor} reclaims them periodically).</li>
 *   <li>{@link #appendQuads} + {@link #patchNode} — the low-level primitives the two
 *       methods above are built on; still usable directly for finer-grained updates.</li>
 * </ul>
 * <b>Known gap:</b> only {@code quads.bin} is compacted ({@link CacheCompactor}) —
 * {@code nodes.bin} has no equivalent yet, so a region reprocessed via
 * {@link #appendRegion} (node count changed) leaves its old node records as dead weight.
 * Rare in practice (only happens when a region's chunk topology itself changes, not just
 * block content) but worth knowing before relying on this for very long-running worlds.
 */
public final class LodCacheWriter {

    /** Bytes read at a time while hashing a region file; keeps memory use flat regardless of file size. */
    private static final int HASH_CHUNK_BYTES = 1 << 16;

    /**
     * Writes a brand-new cache for a dimension, discarding whatever was there before.
     * Used for the first-ever build and for full-world (re)imports.
     */
    public void writeFull(Path worldDir, String dimensionId, String modVersion,
                           List<NodeData> nodes, List<PackedQuad> quads, List<RegionCacheEntry> regionsProcessed) throws IOException {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);

        CacheIo.writeAtomic(LodCacheFormat.nodesFile(dimDir), channel -> writeNodes(channel, nodes));
        CacheIo.writeAtomic(LodCacheFormat.quadsFile(dimDir), channel -> writeQuads(channel, quads));

        CacheIndex index = new CacheIndex(LodCacheFormat.CURRENT_FORMAT_VERSION, modVersion, dimensionId, regionsProcessed);
        index.writeTo(LodCacheFormat.indexFile(dimDir));

        ExceptionalVision.LOGGER.info("Wrote LOD cache for {}: {} nodes, {} quads, {} regions",
                dimensionId, nodes.size(), quads.size(), regionsProcessed.size());
    }

    /**
     * Appends {@code newQuads} to the end of {@code quads.bin} (creating it if absent) and
     * returns the offset (in quad records, not bytes) at which they were written — the
     * caller uses this as the new {@code quadOffset} for whichever {@link NodeData} owns
     * them, then calls {@link #patchNode}.
     */
    public long appendQuads(Path worldDir, String dimensionId, List<PackedQuad> newQuads) throws IOException {
        if (newQuads.isEmpty()) {
            return currentQuadCount(worldDir, dimensionId);
        }
        Path quadsFile = LodCacheFormat.quadsFile(LodCacheFormat.dimensionCacheDir(worldDir, dimensionId));
        ensureQuadsFileExists(quadsFile);

        try (FileChannel channel = CacheIo.openReadWrite(quadsFile)) {
            long existingCount = readQuadCount(channel);
            long appendOffset = existingCount;

            ByteBuffer payload = ByteBuffer.allocate(newQuads.size() * PackedQuad.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (PackedQuad quad : newQuads) {
                payload.putInt(quad.packed0());
                payload.putInt(quad.packed1());
            }
            payload.flip();
            channel.write(payload, LodCacheFormat.QUADS_HEADER_BYTES + appendOffset * PackedQuad.BYTES);

            writeQuadCount(channel, existingCount + newQuads.size());
            channel.force(true);
            return appendOffset;
        }
    }

    /**
     * Overwrites the {@code nodeIndex}-th record of {@code nodes.bin} in place (or appends
     * it as a brand-new node if {@code nodeIndex} is one past the current end), growing the
     * file's header count as needed. Returns the index the node now lives at (equal to
     * {@code nodeIndex} unless it was out of range, in which case it is clamped to "append").
     */
    public int patchNode(Path worldDir, String dimensionId, int nodeIndex, NodeData node) throws IOException {
        Path nodesFile = LodCacheFormat.nodesFile(LodCacheFormat.dimensionCacheDir(worldDir, dimensionId));
        ensureNodesFileExists(nodesFile);

        try (FileChannel channel = CacheIo.openReadWrite(nodesFile)) {
            int existingCount = readNodeCount(channel);
            int targetIndex = (nodeIndex < 0 || nodeIndex > existingCount) ? existingCount : nodeIndex;

            ByteBuffer record = ByteBuffer.allocate(NodeData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            writeNodeRecord(record, node);
            record.flip();
            channel.write(record, LodCacheFormat.NODES_HEADER_BYTES + (long) targetIndex * NodeData.BYTES);

            if (targetIndex == existingCount) {
                writeNodeCount(channel, existingCount + 1);
            }
            channel.force(true);
            return targetIndex;
        }
    }

    /**
     * Appends a freshly-built region's whole contribution to the cache as one new
     * contiguous block, and records it as processed with the node range it now owns.
     * Use this the first time a region is processed, or when reprocessing it and its
     * node count changed since last time; use {@link #patchRegion} instead when the
     * count is unchanged, to avoid leaving the old records as dead weight.
     * <p>
     * {@code nodes}' {@code quadOffset}s are expected to be region-local, 0-based
     * against {@code quads} (exactly what {@code LodBuildResult} produces) — this method
     * shifts them to their real position in the dimension-wide {@code quads.bin} as it
     * appends.
     */
    public void appendRegion(Path worldDir, String dimensionId, String modVersion,
                              RegionCoordinate regionCoordinate, long mtimeMs, String sourceHash,
                              List<NodeData> nodes, List<PackedQuad> quads) throws IOException {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path quadsFile = LodCacheFormat.quadsFile(dimDir);
        ensureNodesFileExists(nodesFile);
        ensureQuadsFileExists(quadsFile);

        long quadBaseOffset = bulkAppendQuads(quadsFile, quads);
        int nodeBaseIndex = bulkAppendNodes(nodesFile, nodes, quadBaseOffset);

        RegionCacheEntry entry = new RegionCacheEntry(
                regionCoordinate.x(), regionCoordinate.z(), mtimeMs, sourceHash, nodeBaseIndex, nodes.size());
        mergeIndexEntry(dimDir, dimensionId, modVersion, entry);

        ExceptionalVision.LOGGER.info("Appended region {} to LOD cache for {}: {} nodes @ {}, {} quads @ {}",
                regionCoordinate, dimensionId, nodes.size(), nodeBaseIndex, quads.size(), quadBaseOffset);
    }

    /**
     * Reprocesses a region whose node count hasn't changed since {@code previousEntry}
     * was recorded — overwrites its existing node records in place with fresh data,
     * appending new quads for them (the old quad range they used to point at becomes
     * orphaned; {@link CacheCompactor} reclaims it).
     *
     * @throws IllegalArgumentException if {@code nodes.size()} doesn't match
     *         {@code previousEntry.nodeCount()} — callers must use {@link #appendRegion}
     *         instead in that case
     */
    public void patchRegion(Path worldDir, String dimensionId, String modVersion,
                             RegionCoordinate regionCoordinate, long mtimeMs, String sourceHash,
                             RegionCacheEntry previousEntry, List<NodeData> nodes, List<PackedQuad> quads) throws IOException {
        if (nodes.size() != previousEntry.nodeCount()) {
            throw new IllegalArgumentException("patchRegion requires an unchanged node count (was "
                    + previousEntry.nodeCount() + ", now " + nodes.size() + "); use appendRegion instead");
        }

        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path quadsFile = LodCacheFormat.quadsFile(dimDir);
        ensureNodesFileExists(nodesFile);
        ensureQuadsFileExists(quadsFile);

        long quadBaseOffset = bulkAppendQuads(quadsFile, quads);

        try (FileChannel channel = CacheIo.openReadWrite(nodesFile)) {
            for (int i = 0; i < nodes.size(); i++) {
                NodeData shifted = nodes.get(i).withQuadRange(
                        (int) (quadBaseOffset + nodes.get(i).quadOffset()), nodes.get(i).quadCount());
                ByteBuffer record = ByteBuffer.allocate(NodeData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                writeNodeRecord(record, shifted);
                record.flip();
                long position = LodCacheFormat.NODES_HEADER_BYTES
                        + (long) (previousEntry.nodeIndexStart() + i) * NodeData.BYTES;
                channel.write(record, position);
            }
            channel.force(true);
        }

        RegionCacheEntry updated = new RegionCacheEntry(regionCoordinate.x(), regionCoordinate.z(), mtimeMs, sourceHash,
                previousEntry.nodeIndexStart(), previousEntry.nodeCount());
        mergeIndexEntry(dimDir, dimensionId, modVersion, updated);

        ExceptionalVision.LOGGER.info("Patched region {} in LOD cache for {}: {} nodes @ {} (unchanged), {} fresh quads @ {}",
                regionCoordinate, dimensionId, nodes.size(), previousEntry.nodeIndexStart(), quads.size(), quadBaseOffset);
    }

    private void mergeIndexEntry(Path dimDir, String dimensionId, String modVersion, RegionCacheEntry entry) throws IOException {
        Path indexFile = LodCacheFormat.indexFile(dimDir);
        CacheIndex index = Files.isRegularFile(indexFile)
                ? CacheIndex.readFrom(indexFile)
                : CacheIndex.empty(modVersion, dimensionId);
        index.withRegion(entry).writeTo(indexFile);
    }

    /** Bulk-appends every quad in one write, returning the (pre-append) quad count they now start at. */
    private long bulkAppendQuads(Path quadsFile, List<PackedQuad> quads) throws IOException {
        try (FileChannel channel = CacheIo.openReadWrite(quadsFile)) {
            long baseOffset = readQuadCount(channel);
            if (!quads.isEmpty()) {
                ByteBuffer payload = ByteBuffer.allocate(quads.size() * PackedQuad.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (PackedQuad quad : quads) {
                    payload.putInt(quad.packed0());
                    payload.putInt(quad.packed1());
                }
                payload.flip();
                channel.write(payload, LodCacheFormat.QUADS_HEADER_BYTES + baseOffset * PackedQuad.BYTES);
                writeQuadCount(channel, baseOffset + quads.size());
                channel.force(true);
            }
            return baseOffset;
        }
    }

    /** Bulk-appends every node in one write (shifting each's quadOffset by {@code quadBaseOffset}), returning the base node index. */
    private int bulkAppendNodes(Path nodesFile, List<NodeData> nodes, long quadBaseOffset) throws IOException {
        try (FileChannel channel = CacheIo.openReadWrite(nodesFile)) {
            int baseIndex = readNodeCount(channel);
            if (!nodes.isEmpty()) {
                ByteBuffer payload = ByteBuffer.allocate(nodes.size() * NodeData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (NodeData node : nodes) {
                    NodeData shifted = node.withQuadRange((int) (quadBaseOffset + node.quadOffset()), node.quadCount());
                    writeNodeRecord(payload, shifted);
                }
                payload.flip();
                channel.write(payload, LodCacheFormat.NODES_HEADER_BYTES + (long) baseIndex * NodeData.BYTES);
                writeNodeCount(channel, baseIndex + nodes.size());
                channel.force(true);
            }
            return baseIndex;
        }
    }

    /**
     * Looks up the existing cache entry for a region, if any — e.g. to decide between
     * {@link #appendRegion} (no entry yet, or node count changed) and {@link #patchRegion}
     * (entry exists with the same node count).
     */
    public java.util.Optional<RegionCacheEntry> findRegionEntry(Path worldDir, String dimensionId, int regionX, int regionZ) throws IOException {
        Path indexFile = LodCacheFormat.indexFile(LodCacheFormat.dimensionCacheDir(worldDir, dimensionId));
        if (!Files.isRegularFile(indexFile)) {
            return java.util.Optional.empty();
        }
        return CacheIndex.readFrom(indexFile).find(regionX, regionZ);
    }

    /**
     * Whether {@code regionFile} needs (re)computation: {@code true} if it has never been
     * processed, or if its mtime/content hash no longer match the last recorded entry
     * (e.g. Chunky generated new chunks into it since the last run).
     */
    public boolean needsRecompute(Path worldDir, String dimensionId, Path regionFile) throws IOException {
        RegionCoordinate coordinate = RegionCoordinate.fromFileName(regionFile.getFileName().toString())
                .orElseThrow(() -> new IOException("Not a region file name: " + regionFile));

        Path indexFile = LodCacheFormat.indexFile(LodCacheFormat.dimensionCacheDir(worldDir, dimensionId));
        if (!Files.isRegularFile(indexFile)) {
            return true;
        }
        CacheIndex index = CacheIndex.readFrom(indexFile);
        if (index.formatVersion() != LodCacheFormat.CURRENT_FORMAT_VERSION) {
            return true;
        }
        java.util.Optional<RegionCacheEntry> existing = index.find(coordinate.x(), coordinate.z());
        if (existing.isEmpty()) {
            return true;
        }
        RegionCacheEntry entry = existing.get();

        long currentMtimeMs = Files.getLastModifiedTime(regionFile).toMillis();
        if (entry.mtimeMs() == currentMtimeMs) {
            return false; // fast path: mtime unchanged, skip the (relatively expensive) content hash
        }
        // mtimeMs disagrees (e.g. the world was copied to another machine and lost sub-second
        // precision, or the file was merely re-saved with identical content) — fall back to the hash.
        return !entry.sourceHash().equals(computeSourceHash(regionFile));
    }

    /** CRC32 of the region file's bytes. Fast and dependency-free; a fallback signal behind mtimeMs, not a security hash. */
    public String computeSourceHash(Path regionFile) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[HASH_CHUNK_BYTES];
        try (InputStream in = Files.newInputStream(regionFile, StandardOpenOption.READ)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        return Long.toHexString(crc.getValue());
    }

    // ---- binary layout helpers --------------------------------------------------------

    private void writeNodes(FileChannel channel, List<NodeData> nodes) {
        try {
            ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.NODES_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(nodes.size());
            header.flip();
            channel.write(header);

            ByteBuffer record = ByteBuffer.allocate(NodeData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (NodeData node : nodes) {
                record.clear();
                writeNodeRecord(record, node);
                record.flip();
                channel.write(record);
            }
        } catch (IOException e) {
            throw new CacheIo.UncheckedIoException(e);
        }
    }

    private void writeQuads(FileChannel channel, List<PackedQuad> quads) {
        try {
            ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.QUADS_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(quads.size());
            header.flip();
            channel.write(header);

            ByteBuffer record = ByteBuffer.allocate(PackedQuad.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (PackedQuad quad : quads) {
                record.clear();
                record.putInt(quad.packed0());
                record.putInt(quad.packed1());
                record.flip();
                channel.write(record);
            }
        } catch (IOException e) {
            throw new CacheIo.UncheckedIoException(e);
        }
    }

    private static void writeNodeRecord(ByteBuffer buffer, NodeData node) {
        buffer.putFloat(node.minX());
        buffer.putFloat(node.minY());
        buffer.putFloat(node.minZ());
        buffer.putFloat((float) node.lodLevel()); // vec4 aabbMin.w = lodLevel, per 03_data_formats.md
        buffer.putFloat(node.maxX());
        buffer.putFloat(node.maxY());
        buffer.putFloat(node.maxZ());
        buffer.putFloat(0f); // vec4 aabbMax.w unused
        buffer.putInt(node.quadOffset());
        buffer.putInt(node.quadCount());
        buffer.putInt(node.lodLevel());
        buffer.putInt(0); // _pad
    }

    private void ensureQuadsFileExists(Path quadsFile) throws IOException {
        if (!Files.isRegularFile(quadsFile)) {
            CacheIo.writeAtomic(quadsFile, channel -> writeQuads(channel, List.of()));
        }
    }

    private void ensureNodesFileExists(Path nodesFile) throws IOException {
        if (!Files.isRegularFile(nodesFile)) {
            CacheIo.writeAtomic(nodesFile, channel -> writeNodes(channel, List.of()));
        }
    }

    private long currentQuadCount(Path worldDir, String dimensionId) throws IOException {
        Path quadsFile = LodCacheFormat.quadsFile(LodCacheFormat.dimensionCacheDir(worldDir, dimensionId));
        if (!Files.isRegularFile(quadsFile)) {
            return 0L;
        }
        try (FileChannel channel = FileChannel.open(quadsFile, StandardOpenOption.READ)) {
            return readQuadCount(channel);
        }
    }

    private static long readQuadCount(FileChannel channel) throws IOException {
        if (channel.size() < LodCacheFormat.QUADS_HEADER_BYTES) {
            return 0L;
        }
        ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.QUADS_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(header, 0);
        header.flip();
        return header.getLong();
    }

    private static void writeQuadCount(FileChannel channel, long count) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.QUADS_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(count);
        header.flip();
        channel.write(header, 0);
    }

    private static int readNodeCount(FileChannel channel) throws IOException {
        if (channel.size() < LodCacheFormat.NODES_HEADER_BYTES) {
            return 0;
        }
        ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.NODES_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(header, 0);
        header.flip();
        return header.getInt();
    }

    private static void writeNodeCount(FileChannel channel, int count) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.NODES_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(count);
        header.flip();
        channel.write(header, 0);
    }
}
