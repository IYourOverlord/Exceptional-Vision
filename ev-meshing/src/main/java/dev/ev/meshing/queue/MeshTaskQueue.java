package dev.ev.meshing.queue;

import dev.ev.api.metrics.MetricsRegistry;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MVP (Wave 1) implementation: a single JDK {@link PriorityBlockingQueue} ordering tasks by
 * the {@code long} priority value produced by {@code MeshPriority.compute(...)} (ticket 14) —
 * "lower value = higher priority", see that class's Javadoc for the full two-tier bit layout.
 * No work-stealing, no per-worker sharding: every worker thread polls from the same shared
 * queue.
 *
 * <p>See {@code MVP_INDEX.md} for why this simple version comes first: {@code
 * PriorityBlockingQueue} is a well-tested, well-understood JDK primitive. The more complex
 * lock-free work-stealing alternative ({@code 15-meshing-work-stealing-queue-opt.md}) is only
 * introduced if profiling ({@code P0-profiling-checkpoint.md}) actually shows contention on
 * this queue as a bottleneck — not preemptively.
 *
 * <p><b>Compatibility note for a future swap to the opt version:</b> the public contract here
 * (constructor taking a {@link MetricsRegistry}, {@code submit}/{@code poll}/{@code
 * pollNonBlocking}/{@code size}) intentionally matches the method shapes used by the opt
 * version's {@code MeshTaskQueue} as closely as reasonably possible. The one documented
 * difference: this MVP version has no concept of a per-call {@code workerHint} parameter on
 * {@code submit}/{@code poll}, since there is only one shared queue, not per-worker deques —
 * the opt version's {@code submit}/{@code poll} take an additional {@code workerHint} argument
 * that this class's methods do not have. Adapting a call site from this MVP class to the opt
 * class therefore requires adding that argument at each call, not a signature-compatible
 * drop-in replacement.
 *
 * <p>Because the near-tier priority values produced by {@code
 * MeshPriority.computeWithNearTierCheck} are packed as large-magnitude negative {@code long}s
 * (sign bit set) while normal-tier values are non-negative (sign bit clear), and {@link
 * Entry#compareTo} below delegates to standard {@code Long.compare} semantics, near-tier tasks
 * naturally sort ahead of every normal-tier task with zero special-casing here — this is the
 * "free" ordering behavior described in ticket 14's Javadoc, verified by this class's own test
 * suite rather than merely assumed.
 */
public final class MeshTaskQueue<T> {

    private static final String QUEUE_NAME = "mesh-task-queue";

    /**
     * How many {@link #submit}/{@link #poll}/{@link #pollNonBlocking} calls occur between
     * queue-depth metric reports. Metrics are reported periodically rather than on every call
     * per requirement 4 of the ticket, to avoid adding registry-call overhead to the hot path.
     */
    private static final int METRIC_REPORT_INTERVAL = 64;

    private final PriorityBlockingQueue<Entry<T>> queue = new PriorityBlockingQueue<>();
    private final MetricsRegistry metrics;
    private final AtomicInteger opsSinceLastReport = new AtomicInteger();

    public MeshTaskQueue(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    /** @param priority result of {@code MeshPriority.compute(...)} (lower = higher priority) */
    public void submit(long priority, T task) {
        queue.put(new Entry<>(priority, task));
        maybeReportQueueDepth();
    }

    /** Blocks the calling worker thread until a task is available, then returns it. */
    public T poll() {
        try {
            T task = queue.take().task;
            maybeReportQueueDepth();
            return task;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MeshTaskQueue.poll interrupted while waiting for a task", e);
        }
    }

    /** Non-blocking variant: returns {@code null} immediately if the queue is empty. */
    public T pollNonBlocking() {
        Entry<T> entry = queue.poll();
        maybeReportQueueDepth();
        return entry == null ? null : entry.task;
    }

    /** Current queue size, for metrics. */
    public int size() {
        return queue.size();
    }

    private void maybeReportQueueDepth() {
        if (opsSinceLastReport.incrementAndGet() >= METRIC_REPORT_INTERVAL) {
            opsSinceLastReport.set(0);
            metrics.recordQueueDepth(QUEUE_NAME, queue.size());
        }
    }

    /** Simple (priority, task) wrapper, ordered by priority per {@code MeshPriority}'s convention. */
    private static final class Entry<T> implements Comparable<Entry<T>> {
        final long priority;
        final T task;

        Entry(long priority, T task) {
            this.priority = priority;
            this.task = task;
        }

        @Override
        public int compareTo(Entry<T> other) {
            return Long.compare(priority, other.priority);
        }
    }
}
