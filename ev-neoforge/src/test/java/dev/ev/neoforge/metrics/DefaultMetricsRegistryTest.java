package dev.ev.neoforge.metrics;

import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No GPU/NeoForge context required — pure JUnit 5, per the general ticket-session convention
 * of testing without a real render context wherever the class under test permits it.
 */
class DefaultMetricsRegistryTest {

    @Test
    @DisplayName("Fresh registry snapshot equals MetricsSnapshot.empty() (no phantom entries)")
    void testFreshSnapshotIsEmpty() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        assertEquals(MetricsSnapshot.empty(), registry.snapshot());
    }

    @Test
    @DisplayName("recordQueueDepth overwrites (gauge semantics), not accumulates")
    void testQueueDepthIsGauge() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        registry.recordQueueDepth("mesh-task-queue", 5);
        registry.recordQueueDepth("mesh-task-queue", 12);
        registry.recordQueueDepth("mesh-task-queue", 3);

        MetricsSnapshot snapshot = registry.snapshot();
        assertEquals(3, snapshot.queueDepths().get("mesh-task-queue"));
        assertEquals(1, snapshot.queueDepths().size());
    }

    @Test
    @DisplayName("recordCacheAccess computes hit rate as hits/total, independent per cache name")
    void testCacheHitRate() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        // 3 hits, 1 miss on "section-cache"
        registry.recordCacheAccess("section-cache", true);
        registry.recordCacheAccess("section-cache", true);
        registry.recordCacheAccess("section-cache", true);
        registry.recordCacheAccess("section-cache", false);
        // 1 miss only on a different cache — must not affect "section-cache"
        registry.recordCacheAccess("other-cache", false);

        MetricsSnapshot snapshot = registry.snapshot();
        assertEquals(0.75, snapshot.cacheHitRates().get("section-cache"), 1e-9);
        assertEquals(0.0, snapshot.cacheHitRates().get("other-cache"), 1e-9);
    }

    @Test
    @DisplayName("recordGpuPassDuration is last-write-wins, not accumulated/averaged")
    void testGpuPassDurationLastWriteWins() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        registry.recordGpuPassDuration("cpu-traversal", 1_000_000L);
        registry.recordGpuPassDuration("cpu-traversal", 2_500_000L);

        assertEquals(2_500_000L, registry.snapshot().gpuPassDurationsNanos().get("cpu-traversal"));
    }

    @Test
    @DisplayName("recordCounter accumulates via delta, supports multiple independent counters")
    void testCounterAccumulates() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        registry.recordCounter("visible-sections", 10);
        registry.recordCounter("visible-sections", 5);
        registry.recordCounter("loaded-sections", 42);

        MetricsSnapshot snapshot = registry.snapshot();
        assertEquals(15L, snapshot.counters().get("visible-sections"));
        assertEquals(42L, snapshot.counters().get("loaded-sections"));
    }

    @Test
    @DisplayName("recordGauge is last-write-wins, NOT accumulated (unlike recordCounter) — "
            + "regression test for the loaded-sections/visible-sections bug where calling "
            + "recordCounter with a current size every frame silently summed into millions")
    void testGaugeIsLastWriteWinsNotAccumulated() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        registry.recordGauge("visible-sections", 100);
        registry.recordGauge("visible-sections", 250);
        registry.recordGauge("loaded-sections", 9000);

        MetricsSnapshot snapshot = registry.snapshot();
        assertEquals(250L, snapshot.gauges().get("visible-sections"),
                "gauge must reflect only the most recent value, not a sum (100 + 250 = 350 would be wrong)");
        assertEquals(9000L, snapshot.gauges().get("loaded-sections"));
    }

    @Test
    @DisplayName("recordImportStageStatus replaces the tracked status wholesale")
    void testImportStageStatusReplaced() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        registry.recordImportStageStatus(new ImportStageStatus(1, 1, 2, 2, 3, 3, 4, 10, 20));
        assertEquals(new ImportStageStatus(1, 1, 2, 2, 3, 3, 4, 10, 20), registry.snapshot().importStageStatus());

        registry.recordImportStageStatus(new ImportStageStatus(0, 0, 0, 0, 0, 0, 0, 10, 10));
        assertEquals(new ImportStageStatus(0, 0, 0, 0, 0, 0, 0, 10, 10), registry.snapshot().importStageStatus());
    }

    @Test
    @DisplayName("recordImportStageStatus rejects null, per interface non-null contract")
    void testImportStageStatusRejectsNull() {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        assertThrows(NullPointerException.class, () -> registry.recordImportStageStatus(null));
    }

    @Test
    @DisplayName("Concurrent recordCounter/recordCacheAccess from many worker threads does not "
            + "throw, lose updates, or corrupt state — reproduces the SectionCache/MeshTaskQueue "
            + "hot-path access pattern (many worker threads, shared registry instance)")
    void testConcurrentAccessDoesNotThrowOrLoseUpdates() throws InterruptedException {
        DefaultMetricsRegistry registry = new DefaultMetricsRegistry();
        int threadCount = 8;
        int opsPerThread = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            registry.recordCounter("meshes-uploaded", 1);
                            registry.recordCacheAccess("section-cache", i % 2 == 0);
                            registry.recordQueueDepth("mesh-task-queue", i % 16);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "worker threads did not finish in time");
        } finally {
            pool.shutdown();
        }

        MetricsSnapshot snapshot = registry.snapshot();
        // No lost updates: LongAdder-backed counter must reflect every single increment exactly.
        assertEquals((long) threadCount * opsPerThread, snapshot.counters().get("meshes-uploaded"));
        // Hit rate must land exactly on 0.5 (evenly split hit/miss across all threads combined).
        assertEquals(0.5, snapshot.cacheHitRates().get("section-cache"), 1e-9);
        // Queue depth is a gauge — any last-written value in [0, 15] is valid, just must be present.
        assertNotNull(snapshot.queueDepths().get("mesh-task-queue"));
    }
}
