package dev.ev.neoforge.command;

import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsSnapshot;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Formats a {@link MetricsSnapshot} into a human-readable multi-line string for chat/log output.
 * Pure function, no NeoForge dependency, independently testable.
 * <p>
 * All map entries are sorted alphabetically by key for deterministic, reproducible output.
 * Nanosecond durations are converted to milliseconds; cache hit rates are converted to percentages.
 */
public final class MetricsSnapshotFormatter {

    private MetricsSnapshotFormatter() {
    }

    /**
     * Formats the given snapshot into a human-readable multi-line string.
     *
     * @param snapshot the metrics snapshot to format
     * @return formatted string
     */
    public static String format(MetricsSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== EV Debug Snapshot ===\n");

        // Import Progress FIRST (per ticket 29 requirement 2)
        formatImportProgress(sb, snapshot.importStageStatus());

        // Queue Depths
        sb.append("Queue Depths:\n");
        formatIntMap(sb, snapshot.queueDepths());

        // Cache Hit Rates
        sb.append("Cache Hit Rates:\n");
        formatHitRates(sb, snapshot.cacheHitRates());

        // GPU Pass Durations
        sb.append("GPU Pass Durations:\n");
        formatDurationsNanos(sb, snapshot.gpuPassDurationsNanos());

        // Counters
        sb.append("Counters:\n");
        formatLongMap(sb, snapshot.counters());

        return sb.toString();
    }

    private static void formatImportProgress(StringBuilder sb, ImportStageStatus status) {
        double pct = status.completionFraction() * 100.0;
        sb.append(String.format(Locale.US, "Import Progress: %d/%d (%.1f%%)\n",
                status.completed(), status.totalKnown(), pct));
        sb.append(String.format(Locale.US, "  queued: %d  retrying: %d  building: %d  done: %d\n",
                status.queuedForRead(), status.retryingAfterFailure(),
                status.activelyBuilding(), status.completed()));
    }

    private static void formatIntMap(StringBuilder sb, Map<String, Integer> map) {
        TreeMap<String, Integer> sorted = new TreeMap<>(map);
        if (sorted.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            sb.append(String.format(Locale.US, "  %s: %d\n", entry.getKey(), entry.getValue()));
        }
    }

    private static void formatHitRates(StringBuilder sb, Map<String, Double> map) {
        TreeMap<String, Double> sorted = new TreeMap<>(map);
        if (sorted.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            sb.append(String.format(Locale.US, "  %s: %.1f%%\n", entry.getKey(), entry.getValue() * 100.0));
        }
    }

    private static void formatDurationsNanos(StringBuilder sb, Map<String, Long> map) {
        TreeMap<String, Long> sorted = new TreeMap<>(map);
        if (sorted.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Map.Entry<String, Long> entry : sorted.entrySet()) {
            double ms = entry.getValue() / 1_000_000.0;
            sb.append(String.format(Locale.US, "  %s: %.2fms\n", entry.getKey(), ms));
        }
    }

    private static void formatLongMap(StringBuilder sb, Map<String, Long> map) {
        TreeMap<String, Long> sorted = new TreeMap<>(map);
        if (sorted.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Map.Entry<String, Long> entry : sorted.entrySet()) {
            sb.append(String.format(Locale.US, "  %s: %d\n", entry.getKey(), entry.getValue()));
        }
    }
}
