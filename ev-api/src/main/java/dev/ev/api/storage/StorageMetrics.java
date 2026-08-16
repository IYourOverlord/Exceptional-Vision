package dev.ev.api.storage;

/** Immutable snapshot of storage subsystem metrics at a point in time. */
public record StorageMetrics(
    long cacheHits,
    long cacheMisses,
    int activeSectionCount,
    int secondaryCacheSize,
    long bytesOnDisk
) {
    public double hitRate() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 0.0 : (double) cacheHits / total;
    }
}
