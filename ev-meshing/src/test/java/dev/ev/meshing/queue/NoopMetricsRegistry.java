package dev.ev.meshing.queue;

import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.metrics.MetricsSnapshot;

/** No-op {@link MetricsRegistry} fake for tests that don't care about recorded metrics. */
final class NoopMetricsRegistry implements MetricsRegistry {

    @Override
    public void recordQueueDepth(String queueName, int depth) {
        // no-op
    }

    @Override
    public void recordCacheAccess(String cacheName, boolean hit) {
        // no-op
    }

    @Override
    public void recordGpuPassDuration(String passName, long nanos) {
        // no-op
    }

    @Override
    public void recordCounter(String name, long delta) {
        // no-op
    }

    @Override
    public void recordImportStageStatus(ImportStageStatus status) {
        // no-op
    }

    @Override
    public MetricsSnapshot snapshot() {
        return MetricsSnapshot.empty();
    }
}
