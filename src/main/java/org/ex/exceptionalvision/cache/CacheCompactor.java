package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.world.lod.NodeData;
import org.ex.exceptionalvision.world.lod.PackedQuad;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reclaims disk space orphaned by repeated {@link LodCacheWriter} updates — both dead
 * quads ({@link LodCacheWriter#appendQuads}/{@link LodCacheWriter#patchRegion}) and dead
 * node records ({@link LodCacheWriter#appendRegion} on a region whose node count changed).
 * <p>
 * <b>Quads:</b> every {@code NodeData} record in {@code nodes.bin} always points at that
 * node's *current* quad range — when a node is recomputed, the old range it used to point
 * at becomes dead weight in {@code quads.bin} (never read again, but still taking up disk
 * space). {@link #compactQuads} walks {@code nodes.bin} — which is exactly the "live set"
 * of quad ranges — copies only the quads each node still references into a fresh
 * {@code quads.bin}, and patches each node's {@code quadOffset} to match. Node *indices*
 * are left untouched by this step — only the quad ranges they point at move.
 * <p>
 * <b>Nodes:</b> {@code index.json}'s {@code regionsProcessed} entries are the analogous
 * "live set" for {@code nodes.bin} — each entry's {@code nodeIndexStart}/{@code nodeCount}
 * names the one contiguous block of node records that region currently owns. When
 * {@link LodCacheWriter#appendRegion} reprocesses a region whose node count changed, it
 * appends a brand-new block and overwrites that region's index entry to point at it — the
 * region's *previous* block is no longer referenced by anything and becomes dead weight in
 * {@code nodes.bin}, exactly mirroring how old quad ranges go dead. {@link #compactNodes}
 * walks {@code regionsProcessed}, copies only the node records each entry still references
 * into a fresh {@code nodes.bin} (ordered by ascending old {@code nodeIndexStart}, so a
 * region's nodes stay contiguous), and rewrites {@code index.json} with the new offsets.
 * <p>
 * Unlike the quad step, node compaction necessarily <em>renumbers</em> node indices (dead
 * blocks in the middle must be dropped, closing the gap). That's safe here because nothing
 * in this codebase holds a node index across a compaction boundary: the GPU streaming
 * system (stage 4) only ever consumes a full {@code LodCacheData} snapshot
 * ({@code LodPipeline#cachedAtStartup()}/{@code reloadCache()}) and re-uploads it whole, it
 * never persists "node 42" across separate cache reads. If a future stage introduces
 * long-lived external references to node indices (e.g. incremental GPU buffer patching in
 * stage 5), this class's node-renumbering behavior must be revisited first.
 * <p>
 * {@link #compact} runs both steps in the correct order: nodes first (so the node list
 * {@link #compactQuads} walks is already the true live set with no dead blocks), then
 * quads. Intended to run periodically (e.g. every few minutes of play, or on world/dimension
 * unload), not every frame — both steps do a full read of their respective file.
 */
public final class CacheCompactor {

    /** Bytes reclaimed by one {@link #compact} run. */
    public record Result(long nodeBytesReclaimed, long quadBytesReclaimed) {
        public long totalBytesReclaimed() {
            return nodeBytesReclaimed + quadBytesReclaimed;
        }
    }

    /**
     * Runs {@link #compactNodes} followed by {@link #compactQuads} — the order matters,
     * see the class javadoc.
     */
    public Result compact(Path worldDir, String dimensionId) throws IOException {
        long nodeBytes = compactNodes(worldDir, dimensionId);
        long quadBytes = compactQuads(worldDir, dimensionId);
        return new Result(nodeBytes, quadBytes);
    }

    /**
     * Drops node records no longer referenced by any entry in {@code index.json}'s
     * {@code regionsProcessed} (left behind when {@link LodCacheWriter#appendRegion}
     * reprocesses a region whose node count changed), and rewrites {@code index.json}
     * with the new (now-contiguous, gap-free) offsets.
     *
     * @return bytes reclaimed (old nodes.bin size minus new nodes.bin size), or 0 if there was nothing to compact.
     */
    public long compactNodes(Path worldDir, String dimensionId) throws IOException {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path indexFile = LodCacheFormat.indexFile(dimDir);

        if (!Files.isRegularFile(nodesFile) || !Files.isRegularFile(indexFile)) {
            return 0L; // nothing built yet
        }

        long oldNodesBytes = Files.size(nodesFile);

        List<NodeData> allNodes = readAllNodes(nodesFile);
        CacheIndex index = CacheIndex.readFrom(indexFile);

        // Walk live regions in ascending old-offset order so each region's nodes stay
        // contiguous in the compacted file, then rewrite each entry's nodeIndexStart to
        // where its block actually landed.
        List<RegionCacheEntry> orderedEntries = new ArrayList<>(index.regionsProcessed());
        orderedEntries.sort(java.util.Comparator.comparingInt(RegionCacheEntry::nodeIndexStart));

        List<NodeData> liveNodes = new ArrayList<>(allNodes.size());
        List<RegionCacheEntry> relocatedEntries = new ArrayList<>(orderedEntries.size());

        for (RegionCacheEntry entry : orderedEntries) {
            int newStart = liveNodes.size();
            for (int i = 0; i < entry.nodeCount(); i++) {
                int sourceIndex = entry.nodeIndexStart() + i;
                if (sourceIndex < 0 || sourceIndex >= allNodes.size()) {
                    // Defensive: a corrupt/truncated file or a pre-nodeIndexStart legacy
                    // entry (defaults to 0/0, see RegionCacheEntry javadoc) shouldn't crash
                    // compaction — just stop copying this region's block early.
                    ExceptionalVision.LOGGER.warn(
                            "LOD cache for {}: region ({},{}) node index {} out of range (nodeCount={}) while compacting, dropping",
                            dimensionId, entry.x(), entry.z(), sourceIndex, allNodes.size());
                    break;
                }
                liveNodes.add(allNodes.get(sourceIndex));
            }
            relocatedEntries.add(entry.withNodeRange(newStart, liveNodes.size() - newStart));
        }

        writeCompactedNodes(nodesFile, liveNodes);

        CacheIndex compactedIndex = new CacheIndex(index.formatVersion(), index.modVersion(), index.dimension(), relocatedEntries);
        compactedIndex.writeTo(indexFile);

        long newNodesBytes = Files.size(nodesFile);
        long reclaimed = oldNodesBytes - newNodesBytes;
        ExceptionalVision.LOGGER.info("Compacted LOD node cache for {}: {} -> {} nodes ({} bytes reclaimed)",
                dimensionId, allNodes.size(), liveNodes.size(), reclaimed);
        return Math.max(reclaimed, 0L);
    }

    /** @return bytes reclaimed (old quads.bin size minus new quads.bin size), or 0 if there was nothing to compact. */
    public long compactQuads(Path worldDir, String dimensionId) throws IOException {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path quadsFile = LodCacheFormat.quadsFile(dimDir);

        if (!Files.isRegularFile(nodesFile) || !Files.isRegularFile(quadsFile)) {
            return 0L; // nothing built yet
        }

        long oldQuadsBytes = Files.size(quadsFile);

        List<NodeData> nodes = readAllNodes(nodesFile);
        // Same reasoning as readAllNodes above: not mapReadOnly, because writeCompactedQuads
        // below atomically replaces this exact file — a lingering mapping would make that
        // Files.move fail with AccessDeniedException on Windows.
        ByteBuffer quads = ByteBuffer.wrap(Files.readAllBytes(quadsFile)).order(ByteOrder.LITTLE_ENDIAN);
        long liveQuadCount = quads.getLong(0);

        List<PackedQuad> compactedQuads = new ArrayList<>();
        List<NodeData> relocatedNodes = new ArrayList<>(nodes.size());

        for (NodeData node : nodes) {
            int newOffset = compactedQuads.size();
            for (int i = 0; i < node.quadCount(); i++) {
                long sourceIndex = node.quadOffset() + i;
                if (sourceIndex >= liveQuadCount) {
                    // Defensive: a corrupt/truncated file shouldn't crash compaction, just drop the tail.
                    ExceptionalVision.LOGGER.warn("LOD cache for {}: quad index {} out of range (quadCount={}) while compacting, dropping",
                            dimensionId, sourceIndex, liveQuadCount);
                    break;
                }
                long byteOffset = LodCacheFormat.QUADS_HEADER_BYTES + sourceIndex * PackedQuad.BYTES;
                int packed0 = quads.getInt((int) byteOffset);
                int packed1 = quads.getInt((int) byteOffset + 4);
                compactedQuads.add(new PackedQuad(packed0, packed1));
            }
            relocatedNodes.add(node.withQuadRange(newOffset, compactedQuads.size() - newOffset));
        }

        writeCompactedQuads(quadsFile, compactedQuads);
        writeCompactedNodes(nodesFile, relocatedNodes);

        long newQuadsBytes = Files.size(quadsFile);
        long reclaimed = oldQuadsBytes - newQuadsBytes;
        ExceptionalVision.LOGGER.info("Compacted LOD quad cache for {}: {} -> {} quads ({} bytes reclaimed)",
                dimensionId, liveQuadCount, compactedQuads.size(), reclaimed);
        return Math.max(reclaimed, 0L);
    }

    private List<NodeData> readAllNodes(Path nodesFile) throws IOException {
        // Deliberately NOT CacheIo.mapReadOnly here (unlike LodCacheLoader, which keeps its
        // mapping alive for the GPU upload) — this method's caller (compactNodes) turns
        // around and atomically replaces this exact file a few lines later. On Windows, a
        // MappedByteBuffer keeps the underlying file locked until the JVM actually unmaps it
        // (there is no explicit unmap in the public API — it happens whenever GC reclaims the
        // buffer, which is not guaranteed to happen in time), so the subsequent
        // Files.move(..., REPLACE_EXISTING) reliably throws AccessDeniedException before ever
        // touching the file. A plain full read avoids holding any OS-level handle/mapping past
        // this method returning. Confirmed via a player's real (Windows) log: compaction failed
        // with exactly this AccessDeniedException on every attempt before this fix.
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(nodesFile)).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt(0);
        List<NodeData> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base = LodCacheFormat.NODES_HEADER_BYTES + i * NodeData.BYTES;
            float minX = buffer.getFloat(base);
            float minY = buffer.getFloat(base + 4);
            float minZ = buffer.getFloat(base + 8);
            // base + 12 is aabbMin.w (lodLevel as float) — kept in sync with the int lodLevel field below, not re-read here.
            float maxX = buffer.getFloat(base + 16);
            float maxY = buffer.getFloat(base + 20);
            float maxZ = buffer.getFloat(base + 24);
            // base + 28 is aabbMax.w (unused).
            int quadOffset = buffer.getInt(base + 32);
            int quadCount = buffer.getInt(base + 36);
            int lodLevel = buffer.getInt(base + 40);
            nodes.add(new NodeData(minX, minY, minZ, maxX, maxY, maxZ, quadOffset, quadCount, lodLevel));
        }
        return nodes;
    }

    private void writeCompactedQuads(Path quadsFile, List<PackedQuad> quads) throws IOException {
        CacheIo.writeAtomic(quadsFile, channel -> {
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
        });
    }

    private void writeCompactedNodes(Path nodesFile, List<NodeData> nodes) throws IOException {
        CacheIo.writeAtomic(nodesFile, channel -> {
            try {
                ByteBuffer header = ByteBuffer.allocate(LodCacheFormat.NODES_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                header.putInt(nodes.size());
                header.flip();
                channel.write(header);

                ByteBuffer record = ByteBuffer.allocate(NodeData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (NodeData node : nodes) {
                    record.clear();
                    record.putFloat(node.minX());
                    record.putFloat(node.minY());
                    record.putFloat(node.minZ());
                    record.putFloat((float) node.lodLevel());
                    record.putFloat(node.maxX());
                    record.putFloat(node.maxY());
                    record.putFloat(node.maxZ());
                    record.putFloat(0f);
                    record.putInt(node.quadOffset());
                    record.putInt(node.quadCount());
                    record.putInt(node.lodLevel());
                    record.putInt(0);
                    record.flip();
                    channel.write(record);
                }
            } catch (IOException e) {
                throw new CacheIo.UncheckedIoException(e);
            }
        });
    }
}