package dev.ev.api.metrics;

/**
 * Central registry for lightweight runtime metrics across all EV subsystems.
 * A single instance is created at mod startup and passed to every subsystem that
 * needs to report metrics. Implementations must be safe to call from any thread
 * (worker threads report meshing/storage metrics, the render thread reports GPU
 * pass timings) with minimal overhead — recording a metric must not allocate on
 * the hot path in the common case.
 */
public interface MetricsRegistry {

    void recordQueueDepth(String queueName, int depth);

    void recordCacheAccess(String cacheName, boolean hit);

    void recordGpuPassDuration(String passName, long nanos);

    void recordCounter(String name, long delta);

    /** Updates the current staged import status (see ImportStageStatus). Called
     * periodically (not necessarily on every single task transition — batching
     * updates, e.g. once per tick, is acceptable and preferred to avoid overhead
     * on a potentially very hot path during bulk cold-start import) by whichever
     * subsystem owns the import pipeline (see ticket 15's MeshTaskQueue and
     * ticket 08's SectionCache as likely sources of this data). */
    void recordImportStageStatus(ImportStageStatus status);

    /** Produces an immutable snapshot of all currently tracked metrics, for display/logging. */
    MetricsSnapshot snapshot();
}
