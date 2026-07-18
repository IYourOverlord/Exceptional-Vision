package org.ex.exceptionalvision.cache;

import org.ex.exceptionalvision.ExceptionalVision;

import java.io.IOException;
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
            MappedByteBuffer nodes = CacheIo.mapReadOnly(nodesFile);
            MappedByteBuffer quads = CacheIo.mapReadOnly(quadsFile);
            LodCacheData data = LodCacheData.of(index, nodes, quads);
            ExceptionalVision.LOGGER.info("Loaded LOD cache for {}: {} nodes, {} quads, {} regions up to date",
                    dimensionId, data.nodeCount(), data.quadCount(), index.regionsProcessed().size());
            return data;
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("Failed to memory-map LOD cache for {} ({}); cache will be rebuilt", dimensionId, e.toString());
            return LodCacheData.empty(modVersion, dimensionId);
        }
    }
}
