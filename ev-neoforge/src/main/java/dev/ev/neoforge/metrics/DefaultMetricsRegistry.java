package dev.ev.neoforge.metrics;

import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.metrics.MetricsSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Production {@link MetricsRegistry} implementation backing {@code /ev debug}/{@code /ev profile}.
 * <p>
 * Replaces the previous {@code NoopMetricsRegistry} stub, which made every subsystem's
 * {@code recordXxx} calls (cache hits in {@code SectionCache}, queue depth in
 * {@code MeshTaskQueue}, GPU/CPU pass timing in {@code FrameGraphBuilder}/{@code SimpleTraversal})
 * silently discard their data — the pipeline itself could be working correctly while
 * {@code /ev debug} still printed an entirely empty snapshot, because the registry it read
 * from never retained anything in the first place.
 * <p>
 * <b>Thread-safety / overhead contract</b> (see {@link MetricsRegistry} Javadoc): called from
 * worker threads (storage/meshing, potentially several concurrently) as well as the render
 * thread (GPU pass timing, per-frame counters), so every {@code recordXxx} method here is
 * lock-free and allocates nothing on the common (already-tracked-key) path:
 * <ul>
 *   <li>{@link #recordQueueDepth}/{@link #recordCounter} — {@link ConcurrentHashMap} of
 *       {@link AtomicInteger}/{@link LongAdder}, updated in place via
 *       {@code computeIfAbsent} only once per distinct key (subsequent updates hit the
 *       already-present entry directly, no map mutation).</li>
 *   <li>{@link #recordCacheAccess} — a hits/total pair of {@link LongAdder}s per cache name;
 *       {@link LongAdder} is deliberately preferred over {@link java.util.concurrent.atomic.AtomicLong}
 *       here because cache accesses are the highest-frequency call site (every single
 *       {@code SectionCache} lookup from every worker thread) and {@code LongAdder} trades a
 *       (here irrelevant) read-latency cost for much lower contention under concurrent writes.</li>
 *   <li>{@link #recordGpuPassDuration} — last-value-wins via a plain volatile-backed map
 *       entry (see {@link LastValueLong}), not an {@link AtomicReference}; pass timings are a
 *       "most recent duration" gauge, not an accumulator, so last-write-wins is the correct
 *       semantics, unlike counters/queue-depths.</li>
 *   <li>{@link #recordImportStageStatus} — single {@link AtomicReference}, replaced wholesale;
 *       already documented on the interface as intended to be called at low frequency
 *       (e.g. once per tick), so no further batching is attempted here.</li>
 * </ul>
 * {@link #snapshot()} is the only method that allocates — it materializes an immutable
 * point-in-time copy for display/logging (called from {@code /ev debug}, a player-issued
 * command, not a hot path) and is intentionally not synchronized against concurrent
 * {@code recordXxx} calls beyond what the underlying concurrent collections already
 * guarantee: a snapshot may interleave with in-flight updates (some counters reflect a
 * slightly newer or older instant than others), which is acceptable for a debug/diagnostic
 * view and far cheaper than coordinating a globally consistent snapshot across every
 * tracked metric.
 */
public final class DefaultMetricsRegistry implements MetricsRegistry {

    private final ConcurrentHashMap<String, AtomicInteger> queueDepths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheAccessCounters> cacheAccess = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LastValueLong> gpuPassDurationsNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final AtomicReference<ImportStageStatus> importStageStatus =
            new AtomicReference<>(ImportStageStatus.empty());

    @Override
    public void recordQueueDepth(String queueName, int depth) {
        queueDepths.computeIfAbsent(queueName, k -> new AtomicInteger()).set(depth);
    }

    @Override
    public void recordCacheAccess(String cacheName, boolean hit) {
        cacheAccess.computeIfAbsent(cacheName, k -> new CacheAccessCounters()).record(hit);
    }

    @Override
    public void recordGpuPassDuration(String passName, long nanos) {
        gpuPassDurationsNanos.computeIfAbsent(passName, k -> new LastValueLong()).set(nanos);
    }

    @Override
    public void recordCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    @Override
    public void recordImportStageStatus(ImportStageStatus status) {
        importStageStatus.set(java.util.Objects.requireNonNull(status, "status cannot be null"));
    }

    @Override
    public MetricsSnapshot snapshot() {
        Map<String, Integer> queueDepthsCopy = new java.util.TreeMap<>();
        queueDepths.forEach((k, v) -> queueDepthsCopy.put(k, v.get()));

        Map<String, Double> cacheHitRatesCopy = new java.util.TreeMap<>();
        cacheAccess.forEach((k, v) -> cacheHitRatesCopy.put(k, v.hitRate()));

        Map<String, Long> gpuPassDurationsCopy = new java.util.TreeMap<>();
        gpuPassDurationsNanos.forEach((k, v) -> gpuPassDurationsCopy.put(k, v.get()));

        Map<String, Long> countersCopy = new java.util.TreeMap<>();
        counters.forEach((k, v) -> countersCopy.put(k, v.sum()));

        return new MetricsSnapshot(
                Map.copyOf(queueDepthsCopy),
                Map.copyOf(cacheHitRatesCopy),
                Map.copyOf(gpuPassDurationsCopy),
                Map.copyOf(countersCopy),
                importStageStatus.get()
        );
    }

    /**
     * Hits/total pair for one cache name. Two independent {@link LongAdder}s rather than a
     * single compound counter — {@code hitRate()} tolerates reading them a moment apart
     * (see class Javadoc on snapshot consistency), and keeping them separate avoids any
     * read-modify-write coordination between concurrent writers on the hot cache-lookup path.
     */
    private static final class CacheAccessCounters {
        private final LongAdder hits = new LongAdder();
        private final LongAdder total = new LongAdder();

        void record(boolean hit) {
            if (hit) {
                hits.increment();
            }
            total.increment();
        }

        double hitRate() {
            long totalSnapshot = total.sum();
            return totalSnapshot == 0 ? 0.0 : (double) hits.sum() / totalSnapshot;
        }
    }

    /**
     * Plain volatile-backed last-write-wins holder for a single {@code long}. Simpler than
     * {@link java.util.concurrent.atomic.AtomicLong} for this use (no read-modify-write ever
     * needed, only get/set), used instead of a raw boxed {@link Long} in the map value to
     * avoid reallocating a new box on every single pass-duration update (once per frame on
     * the render thread, or more for multiple named passes).
     */
    private static final class LastValueLong {
        private volatile long value;

        void set(long v) {
            value = v;
        }

        long get() {
            return value;
        }
    }
}
