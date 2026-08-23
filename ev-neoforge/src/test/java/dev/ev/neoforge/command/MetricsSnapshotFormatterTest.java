package dev.ev.neoforge.command;

import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsSnapshotFormatterTest {

    @Test
    @DisplayName("Empty snapshot does not throw, contains section headers, no null/garbage")
    void testEmptySnapshot() {
        MetricsSnapshot empty = MetricsSnapshot.empty();
        String result = MetricsSnapshotFormatter.format(empty);

        assertNotNull(result);
        assertFalse(result.contains("null"));
        assertTrue(result.contains("=== EV Debug Snapshot ==="));
        assertTrue(result.contains("Import Progress:"));
        assertTrue(result.contains("Queue Depths:"));
        assertTrue(result.contains("Cache Hit Rates:"));
        assertTrue(result.contains("GPU Pass Durations:"));
        assertTrue(result.contains("Counters:"));
        assertTrue(result.contains("Gauges:"));
    }

    @Test
    @DisplayName("Snapshot with multiple values in each category, output sorted by key")
    void testMultipleValues() {
        // Insert keys in deliberately unsorted order
        Map<String, Integer> queues = new LinkedHashMap<>();
        queues.put("upload-staging", 56);
        queues.put("mesh-build", 1234);

        Map<String, Double> hitRates = new LinkedHashMap<>();
        hitRates.put("section-cache", 0.873);
        hitRates.put("block-cache", 0.5);

        Map<String, Long> durations = new LinkedHashMap<>();
        durations.put("traversal", 1_200_000L);
        durations.put("hiz-build", 450_000L);

        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("sections-loaded", 98765L);
        counters.put("buffers-uploaded", 1234L);

        Map<String, Long> gauges = new LinkedHashMap<>();
        gauges.put("visible-sections", 4321L);
        gauges.put("loaded-sections", 999L);

        ImportStageStatus status = new ImportStageStatus(3200, 12, 554, 1234, 5000);
        MetricsSnapshot snapshot = new MetricsSnapshot(queues, hitRates, durations, counters, gauges, status);

        String result = MetricsSnapshotFormatter.format(snapshot);

        // Verify sorted order: mesh-build before upload-staging
        int meshIdx = result.indexOf("mesh-build");
        int uploadIdx = result.indexOf("upload-staging");
        assertTrue(meshIdx < uploadIdx, "mesh-build should appear before upload-staging (alphabetical)");

        // Verify sorted order: block-cache before section-cache
        int blockIdx = result.indexOf("block-cache");
        int sectionIdx = result.indexOf("section-cache");
        assertTrue(blockIdx < sectionIdx, "block-cache should appear before section-cache (alphabetical)");

        // Verify sorted order: hiz-build before traversal
        int hizIdx = result.indexOf("hiz-build");
        int travIdx = result.indexOf("traversal");
        assertTrue(hizIdx < travIdx, "hiz-build should appear before traversal (alphabetical)");

        // Verify sorted order: buffers-uploaded before sections-loaded
        int buffIdx = result.indexOf("buffers-uploaded");
        int secIdx = result.indexOf("sections-loaded");
        assertTrue(buffIdx < secIdx, "buffers-uploaded should appear before sections-loaded (alphabetical)");

        // Values present
        assertTrue(result.contains("1234"));
        assertTrue(result.contains("56"));
        assertTrue(result.contains("98765"));

        // Gauges section present with correct values, sorted (loaded-sections before visible-sections)
        int loadedIdx = result.indexOf("loaded-sections");
        int visibleIdx = result.indexOf("visible-sections");
        assertTrue(loadedIdx >= 0 && visibleIdx >= 0, "both gauge entries should be present");
        assertTrue(loadedIdx < visibleIdx, "loaded-sections should appear before visible-sections (alphabetical)");
        assertTrue(result.contains("999"));
        assertTrue(result.contains("4321"));
    }

    @Test
    @DisplayName("gpuPassDurationsNanos converts to milliseconds with reasonable precision")
    void testNanosToMs() {
        Map<String, Long> durations = Map.of("test-pass", 1_200_000L);
        MetricsSnapshot snapshot = new MetricsSnapshot(Map.of(), Map.of(), durations, Map.of(), Map.of(),
                ImportStageStatus.empty());

        String result = MetricsSnapshotFormatter.format(snapshot);
        assertTrue(result.contains("1.20ms"), "1_200_000 nanos should format as 1.20ms, got: " + result);
    }

    @Test
    @DisplayName("cacheHitRates converts fraction to percentage")
    void testHitRatePercent() {
        Map<String, Double> hitRates = Map.of("section-cache", 0.873);
        MetricsSnapshot snapshot = new MetricsSnapshot(Map.of(), hitRates, Map.of(), Map.of(), Map.of(),
                ImportStageStatus.empty());

        String result = MetricsSnapshotFormatter.format(snapshot);
        assertTrue(result.contains("87.3%"), "0.873 should format as 87.3%, got: " + result);
    }

    @Test
    @DisplayName("Non-empty importStageStatus appears FIRST with correct percentage and all stage numbers")
    void testImportProgressFirst() {
        ImportStageStatus status = new ImportStageStatus(3200, 12, 554, 1234, 5000);
        MetricsSnapshot snapshot = new MetricsSnapshot(
                Map.of("q", 1), Map.of(), Map.of(), Map.of(), Map.of(), status);

        String result = MetricsSnapshotFormatter.format(snapshot);

        // Import Progress before Queue Depths
        int importIdx = result.indexOf("Import Progress:");
        int queueIdx = result.indexOf("Queue Depths:");
        assertTrue(importIdx >= 0, "Import Progress section should be present");
        assertTrue(importIdx < queueIdx, "Import Progress should appear before Queue Depths");

        // Correct percentage: 1234/5000 = 24.68% → 24.7%
        assertTrue(result.contains("1234/5000"), "Should show completed/totalKnown");
        assertTrue(result.contains("24.7%"), "Should show 24.7%, got: " + result);

        // All four stage numbers
        assertTrue(result.contains("queued: 3200"));
        assertTrue(result.contains("retrying: 12"));
        assertTrue(result.contains("building: 554"));
        assertTrue(result.contains("done: 1234"));
    }

    @Test
    @DisplayName("Empty importStageStatus (totalKnown=0) shows 0.0%, not NaN or exception")
    void testEmptyImportStatus() {
        ImportStageStatus empty = ImportStageStatus.empty();
        MetricsSnapshot snapshot = new MetricsSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), empty);

        String result = MetricsSnapshotFormatter.format(snapshot);

        assertTrue(result.contains("Import Progress:"), "Import Progress section should be present even when empty");
        assertTrue(result.contains("0/0"), "Should show 0/0");
        assertTrue(result.contains("0.0%"), "Should show 0.0% for empty status, got: " + result);
        assertFalse(result.contains("NaN"), "Should not contain NaN");
    }
}
