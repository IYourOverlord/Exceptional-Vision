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
 * Reclaims quads orphaned by repeated {@link LodCacheWriter#appendQuads} calls.
 * <p>
 * Every {@code NodeData} record in {@code nodes.bin} always points at that node's
 * *current* quad range — when a node is recomputed, {@link LodCacheWriter#patchNode}
 * repoints it at a freshly appended range and the old range becomes dead weight in
 * {@code quads.bin} (never read again, but still taking up disk space). Compaction walks
 * {@code nodes.bin} — which is therefore exactly the "live set" — copies only the quads
 * each node still references into a fresh {@code quads.bin}, and patches each node's
 * {@code quadOffset} to match. Node *indices* (and therefore any external references to
 * node {@code i}, e.g. from the GPU streaming system) are left untouched — only the quad
 * ranges they point at move. See "Инкрементальное обновление", step 3, in
 * {@code 06_disk_cache_format.md}.
 * <p>
 * Intended to run periodically (e.g. every few minutes of play, or on world unload), not
 * every frame — it does a full read of {@code quads.bin}.
 */
public final class CacheCompactor {

    /** @return bytes reclaimed (old quads.bin size minus new quads.bin size), or 0 if there was nothing to compact. */
    public long compact(Path worldDir, String dimensionId) throws IOException {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path quadsFile = LodCacheFormat.quadsFile(dimDir);

        if (!Files.isRegularFile(nodesFile) || !Files.isRegularFile(quadsFile)) {
            return 0L; // nothing built yet
        }

        long oldQuadsBytes = Files.size(quadsFile);

        List<NodeData> nodes = readAllNodes(nodesFile);
        ByteBuffer quads = CacheIo.mapReadOnly(quadsFile).order(ByteOrder.LITTLE_ENDIAN);
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
        ExceptionalVision.LOGGER.info("Compacted LOD cache for {}: {} -> {} quads ({} bytes reclaimed)",
                dimensionId, liveQuadCount, compactedQuads.size(), reclaimed);
        return Math.max(reclaimed, 0L);
    }

    private List<NodeData> readAllNodes(Path nodesFile) throws IOException {
        ByteBuffer buffer = CacheIo.mapReadOnly(nodesFile).order(ByteOrder.LITTLE_ENDIAN);
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
