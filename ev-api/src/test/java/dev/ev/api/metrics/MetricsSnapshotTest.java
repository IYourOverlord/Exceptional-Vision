package dev.ev.api.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link MetricsSnapshot#empty()}. */
class MetricsSnapshotTest {

    @Test
    void emptyReturnsNonNullEmptyCollectionsForAllMapFields() {
        MetricsSnapshot snapshot = MetricsSnapshot.empty();

        assertNotNull(snapshot.queueDepths());
        assertTrue(snapshot.queueDepths().isEmpty());

        assertNotNull(snapshot.cacheHitRates());
        assertTrue(snapshot.cacheHitRates().isEmpty());

        assertNotNull(snapshot.gpuPassDurationsNanos());
        assertTrue(snapshot.gpuPassDurationsNanos().isEmpty());

        assertNotNull(snapshot.counters());
        assertTrue(snapshot.counters().isEmpty());
    }

    @Test
    void emptyImportStageStatusEqualsImportStageStatusEmpty() {
        MetricsSnapshot snapshot = MetricsSnapshot.empty();
        assertEquals(ImportStageStatus.empty(), snapshot.importStageStatus());
    }
}
