package dev.ev.render.culling;

import dev.ev.api.SectionPos;
import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.metrics.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTraversalTest {

    private static final float[] ALWAYS_TRUE_FRUSTUM = new float[24]; // signedDistance will be 0 >= -radius

    @Test
    @DisplayName("Empty loadedSections returns empty list and records timing")
    void testEmptyLoadedSections() {
        FakeMetricsRegistry metrics = new FakeMetricsRegistry();
        SimpleTraversal traversal = new SimpleTraversal(metrics);

        FrustumTester frustum = new FrustumTester(ALWAYS_TRUE_FRUSTUM);
        List<SectionPos> result = traversal.computeVisible(Collections.emptyList(), frustum, pos -> new float[]{0, 0, 0, 1});

        assertTrue(result.isEmpty());
        assertEquals(1, metrics.gpuPassCalls.get());
        assertEquals("cpu-traversal", metrics.lastPassName);
        assertTrue(metrics.lastPassDurationNanos.get() >= 0);
    }

    @Test
    @DisplayName("All sections visible when frustum accepts all")
    void testAllSectionsVisible() {
        FakeMetricsRegistry metrics = new FakeMetricsRegistry();
        SimpleTraversal traversal = new SimpleTraversal(metrics);

        FrustumTester frustum = new FrustumTester(ALWAYS_TRUE_FRUSTUM);
        List<SectionPos> loaded = List.of(
                new SectionPos(0, 0, 0, 0),
                new SectionPos(1, 2, 3, 4),
                new SectionPos(0, -1, 5, -2)
        );

        List<SectionPos> result = traversal.computeVisible(loaded, frustum, pos -> new float[]{0, 0, 0, 100});

        assertEquals(loaded, result);
        assertEquals(1, metrics.gpuPassCalls.get());
    }

    @Test
    @DisplayName("No sections visible when frustum rejects all")
    void testNoSectionsVisible() {
        FakeMetricsRegistry metrics = new FakeMetricsRegistry();
        SimpleTraversal traversal = new SimpleTraversal(metrics);

        // Plane 0: 1*x + 0 + 0 - 100 >= 0 (requires x >= 100)
        float[] rejectingFrustumPlanes = new float[24];
        rejectingFrustumPlanes[0] = 1.0f;
        rejectingFrustumPlanes[3] = -100.0f;
        FrustumTester frustum = new FrustumTester(rejectingFrustumPlanes);

        List<SectionPos> loaded = List.of(
                new SectionPos(0, 0, 0, 0),
                new SectionPos(0, 1, 0, 0)
        );

        List<SectionPos> result = traversal.computeVisible(loaded, frustum, pos -> new float[]{0, 0, 0, 10});

        assertTrue(result.isEmpty());
        assertEquals(1, metrics.gpuPassCalls.get());
    }

    @Test
    @DisplayName("Mixed visibility returns exact visible subset in original order")
    void testMixedVisibilitySubset() {
        FakeMetricsRegistry metrics = new FakeMetricsRegistry();
        SimpleTraversal traversal = new SimpleTraversal(metrics);

        // Plane 0: 1*x - 10 >= 0 (x >= 10)
        float[] planes = new float[24];
        planes[0] = 1.0f;
        planes[3] = -10.0f;
        FrustumTester frustum = new FrustumTester(planes);

        SectionPos posOutside = new SectionPos(0, 0, 0, 0); // x=0 -> outside
        SectionPos posInside = new SectionPos(0, 20, 0, 0); // x=20 -> inside

        List<SectionPos> loaded = List.of(posOutside, posInside);

        List<SectionPos> result = traversal.computeVisible(loaded, frustum, pos -> new float[]{pos.x(), 0, 0, 1});

        assertEquals(List.of(posInside), result);
    }

    @Test
    @DisplayName("Metrics recordGpuPassDuration('cpu-traversal', nanos) is invoked exactly once with non-negative nanos")
    void testMetricsInstrumentationRecordedOnce() {
        FakeMetricsRegistry metrics = new FakeMetricsRegistry();
        SimpleTraversal traversal = new SimpleTraversal(metrics);
        FrustumTester frustum = new FrustumTester(ALWAYS_TRUE_FRUSTUM);

        assertEquals(0, metrics.gpuPassCalls.get());

        traversal.computeVisible(List.of(new SectionPos(0, 0, 0, 0)), frustum, pos -> new float[]{0, 0, 0, 1});

        assertEquals(1, metrics.gpuPassCalls.get());
        assertEquals("cpu-traversal", metrics.lastPassName);
        assertTrue(metrics.lastPassDurationNanos.get() >= 0L);
    }

    private static class FakeMetricsRegistry implements MetricsRegistry {
        final AtomicInteger gpuPassCalls = new AtomicInteger(0);
        volatile String lastPassName = null;
        final AtomicLong lastPassDurationNanos = new AtomicLong(-1);

        @Override
        public void recordQueueDepth(String queueName, int depth) {
        }

        @Override
        public void recordCacheAccess(String cacheName, boolean hit) {
        }

        @Override
        public void recordGpuPassDuration(String passName, long nanos) {
            gpuPassCalls.incrementAndGet();
            lastPassName = passName;
            lastPassDurationNanos.set(nanos);
        }

        @Override
        public void recordCounter(String counterName, long delta) {
        }

        @Override
        public void recordImportStageStatus(ImportStageStatus status) {
        }

        @Override
        public MetricsSnapshot snapshot() {
            return MetricsSnapshot.empty();
        }
    }
}
