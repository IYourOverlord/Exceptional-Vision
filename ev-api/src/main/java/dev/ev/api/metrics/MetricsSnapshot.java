package dev.ev.api.metrics;

import java.util.Map;

/**
 * Immutable point-in-time view of all registered metrics, suitable for rendering
 * in a debug overlay or serializing to a log line.
 */
public record MetricsSnapshot(
    Map<String, Integer> queueDepths,
    Map<String, Double> cacheHitRates,
    Map<String, Long> gpuPassDurationsNanos,
    Map<String, Long> counters,
    Map<String, Long> gauges,
    ImportStageStatus importStageStatus
) {
    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), ImportStageStatus.empty());
    }
}
