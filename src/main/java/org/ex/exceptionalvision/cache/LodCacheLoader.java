package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.ExceptionalVision;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a dimension's LOD disk cache at world start. See {@code 06_disk_cache_format.md}
 * ("Загрузка при старте мира").
 * <p>
 * Never throws for an ordinary "no cache yet" or "stale cache" situation — those are
 * expected, common states and simply resolve to {@link LodCacheData#isEmpty()}, so the
 * LOD builder (stage 2) recomputes from region files as if this were a fresh world.
 */
public final class LodCacheLoader {

    /**
     * @param worldDir    the save's root directory (parent of {@code region/}, {@code level.dat}, ...)
     * @param dimensionId e.g. {@code "minecraft:overworld"}
     * @param modVersion  current mod version, recorded if the cache needs to be (re)created later;
     *                    not itself part of the format-version compatibility check
     */
    public LodCacheData load(Path worldDir, String dimensionId, String modVersion) {
        Path dimDir = LodCacheFormat.dimensionCacheDir(worldDir, dimensionId);
        Path indexFile = LodCacheFormat.indexFile(dimDir);

        if (!Files.isRegularFile(indexFile)) {
            return LodCacheData.empty(modVersion, dimensionId); // no cache yet, everything will be built from scratch
        }

        CacheIndex index;
        try {
            index = CacheIndex.readFrom(indexFile);
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("LOD cache index for {} is unreadable ({}); cache will be rebuilt", dimensionId, e.toString());
            return LodCacheData.empty(modVersion, dimensionId);
        }

        if (index.formatVersion() != LodCacheFormat.CURRENT_FORMAT_VERSION) {
            ExceptionalVision.LOGGER.info("LOD cache for {} was built with format version {} (current is {}); cache will be rebuilt",
                    dimensionId, index.formatVersion(), LodCacheFormat.CURRENT_FORMAT_VERSION);
            return LodCacheData.empty(modVersion, dimensionId);
        }

        Path nodesFile = LodCacheFormat.nodesFile(dimDir);
        Path quadsFile = LodCacheFormat.quadsFile(dimDir);
        if (!Files.isRegularFile(nodesFile) || !Files.isRegularFile(quadsFile)) {
            ExceptionalVision.LOGGER.warn("LOD cache for {} has an index.json but is missing nodes.bin/quads.bin; cache will be rebuilt", dimensionId);
            return LodCacheData.empty(modVersion, dimensionId);
        }

        try {
            MappedByteBuffer mappedNodes = CacheIo.mapReadOnly(nodesFile);
            MappedByteBuffer mappedQuads = CacheIo.mapReadOnly(quadsFile);
            // FIX (found via a real playtest log: a second `/ev reload` about a minute
            // after the first failed compaction with `AccessDeniedException: quads.bin.tmp
            // -> quads.bin`): LodCacheData used to hold onto these MappedByteBuffers
            // directly (nodesBuffer()/quadsBuffer() returned duplicates that shared the
            // SAME backing mapping, per ByteBuffer.slice()/duplicate() semantics), and
            // LodPipeline.cachedAtStartup keeps that LodCacheData reachable in a field for
            // its entire lifetime - i.e. for as long as this dimension's pipeline stays
            // active, which on Windows keeps nodes.bin/quads.bin locked the whole time
            // (there is no explicit unmap in the public NIO API - only unmapped once GC
            // reclaims the buffer, which "reachable via a live field" prevents entirely,
            // not just delays). CacheCompactor's own javadoc already called this out as a
            // known tension ("unlike LodCacheLoader, which keeps its mapping alive for the
            // GPU upload") back when it only affected the compactor's OWN reads (fixed by
            // not mmap'ing there); this is the other side of the same problem, now that
            // `/ev reload` makes a second compaction of the SAME dimension during a single
            // session an easy, ordinary thing to trigger instead of a rare portal-and-back
            // edge case. Copying out of the mapping here, into fresh non-file-backed direct
            // buffers, means nothing outside this method ever holds a reference to the
            // actual mapping - mappedNodes/mappedQuads go out of scope the moment this
            // method returns, eligible for GC immediately rather than staying artificially
            // reachable for the rest of the session. Costs one extra copy at load time
            // (bounded, one-time, not per-frame) in exchange for actually being correct.
            ByteBuffer nodes = copyToDirectBuffer(mappedNodes);
            ByteBuffer quads = copyToDirectBuffer(mappedQuads);
            LodCacheData data = LodCacheData.of(index, nodes, quads);
            ExceptionalVision.LOGGER.info("Loaded LOD cache for {}: {} nodes, {} quads, {} regions up to date",
                    dimensionId, data.nodeCount(), data.quadCount(), index.regionsProcessed().size());
            return data;
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("Failed to memory-map LOD cache for {} ({}); cache will be rebuilt", dimensionId, e.toString());
            return LodCacheData.empty(modVersion, dimensionId);
        }
    }

    /**
     * Copies a mapping's full contents into a freshly allocated direct buffer that isn't
     * backed by any file, positioned at 0 - so the caller can let {@code mapped} go
     * without anything downstream ending up with a reference to the actual file mapping.
     * See the FIX note in {@link #load} for why this matters.
     */
    private static ByteBuffer copyToDirectBuffer(MappedByteBuffer mapped) {
        mapped.rewind();
        ByteBuffer copy = ByteBuffer.allocateDirect(mapped.remaining());
        copy.put(mapped);
        copy.flip();
        return copy;
    }
}