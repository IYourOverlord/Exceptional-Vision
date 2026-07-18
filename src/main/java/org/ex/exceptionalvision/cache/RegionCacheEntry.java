package org.ex.exceptionalvision.cache;

/**
 * Bookkeeping for one already-processed region, as stored in {@code index.json}'s
 * {@code regionsProcessed} array. See {@code 06_disk_cache_format.md}.
 *
 * @param x             region X (region-space, matches {@code r.X.Z.mca})
 * @param z             region Z (region-space)
 * @param mtimeMs       {@code .mca} file modification time at the moment it was last
 *                      processed, in epoch milliseconds
 * @param sourceHash    content hash of the {@code .mca} file at the moment it was last
 *                      processed; used as a fallback when {@code mtimeMs} alone is
 *                      unreliable (e.g. a world copied between machines/filesystems that
 *                      don't preserve mtimes, or two writes landing in the same
 *                      millisecond)
 * @param nodeIndexStart index (in {@code nodes.bin}) of this region's first node record —
 *                      {@code LodBuilder.buildRegion} always produces one contiguous run of
 *                      nodes per region, and {@code LodCacheWriter#appendRegion} always
 *                      appends them as one contiguous block, so a start + count fully
 *                      describes the region's slice. Not meaningful (both fields 0) for
 *                      entries written before this field existed — see
 *                      {@code CacheIndex#readFrom}, which defaults missing values to 0.
 * @param nodeCount     number of node records belonging to this region, starting at
 *                      {@code nodeIndexStart}
 */
public record RegionCacheEntry(int x, int z, long mtimeMs, String sourceHash, int nodeIndexStart, int nodeCount) {

    /** Whether this entry is still valid for the given on-disk region file state. */
    public boolean matches(long currentMtimeMs, String currentSourceHash) {
        return mtimeMs == currentMtimeMs || sourceHash.equals(currentSourceHash);
    }

    /** Returns a copy with the same file-change bookkeeping but a (possibly new) node range. */
    public RegionCacheEntry withNodeRange(int nodeIndexStart, int nodeCount) {
        return new RegionCacheEntry(x, z, mtimeMs, sourceHash, nodeIndexStart, nodeCount);
    }
}
