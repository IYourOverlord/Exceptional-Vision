package org.ex.exceptionalvision.cache;

import java.nio.file.Path;

/**
 * File layout constants for the LOD disk cache. See {@code 06_disk_cache_format.md}.
 *
 * <pre>
 * &lt;world&gt;/lodcache/&lt;dimension&gt;/nodes.bin
 * &lt;world&gt;/lodcache/&lt;dimension&gt;/quads.bin
 * &lt;world&gt;/lodcache/&lt;dimension&gt;/index.json
 * </pre>
 */
public final class LodCacheFormat {

    /**
     * Bumped whenever the binary layout of {@link org.ex.exceptionalvision.world.lod.NodeData}
     * or {@link org.ex.exceptionalvision.world.lod.PackedQuad} changes between mod versions.
     * A cache whose {@code index.json} reports a different version is discarded and rebuilt
     * from scratch rather than migrated. See "Формат-версионирование" in
     * {@code 06_disk_cache_format.md}.
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    static final String CACHE_DIR_NAME = "lodcache";
    static final String NODES_FILE_NAME = "nodes.bin";
    static final String QUADS_FILE_NAME = "quads.bin";
    static final String INDEX_FILE_NAME = "index.json";

    /** Header of {@code nodes.bin}: a single little-endian {@code uint32} node count. */
    static final int NODES_HEADER_BYTES = 4;

    /** Header of {@code quads.bin}: a single little-endian {@code uint64} quad count. */
    static final int QUADS_HEADER_BYTES = 8;

    private LodCacheFormat() {
    }

    /**
     * Resolves the cache directory for one dimension of one world.
     * <p>
     * Dimension IDs (e.g. {@code "minecraft:overworld"}) contain a {@code :}, which is
     * not a legal path character on Windows, so it is replaced with {@code _} here.
     * This is a filesystem-safety detail only — the human-readable ID is still stored
     * verbatim inside {@code index.json}.
     */
    public static Path dimensionCacheDir(Path worldDir, String dimensionId) {
        return worldDir.resolve(CACHE_DIR_NAME).resolve(sanitize(dimensionId));
    }

    static Path nodesFile(Path dimensionCacheDir) {
        return dimensionCacheDir.resolve(NODES_FILE_NAME);
    }

    static Path quadsFile(Path dimensionCacheDir) {
        return dimensionCacheDir.resolve(QUADS_FILE_NAME);
    }

    static Path indexFile(Path dimensionCacheDir) {
        return dimensionCacheDir.resolve(INDEX_FILE_NAME);
    }

    private static String sanitize(String dimensionId) {
        return dimensionId.replace(':', '_');
    }
}
