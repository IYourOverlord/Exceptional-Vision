package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.api.meshing.Quad;
import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.meshing.queue.MeshTaskQueue;
import dev.ev.render.dirty.GeometryChangeDeduplicator;
import dev.ev.storage.cache.SectionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the worker thread pool that drains {@link MeshTaskQueue}, runs each task
 * through the {@link MeshingPipelineRunner}, deduplicates via
 * {@link GeometryChangeDeduplicator}, and deposits completed {@link MeshletBatch}
 * results for the render thread to pick up.
 * <p>
 * <b>Worker pool location (INTEGRATION_NOTES.md reference):</b> this is the single
 * place in the codebase where meshing worker threads are created and shut down. The
 * pool size comes from {@code EVConfig.workerThreadCount()}, passed to the constructor.
 * If "sections aren't being meshed", start debugging here.
 */
public final class MeshWorkerPool implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeshWorkerPool.class);

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentLinkedQueue<MeshletBatch> completedBatches = new ConcurrentLinkedQueue<>();

    private final MeshTaskQueue<MeshTask> taskQueue;
    private final SectionCache sectionCache;
    private final MeshingPipelineRunner pipelineRunner;
    private final GeometryChangeDeduplicator deduplicator;
    private final MeshingContext meshingContext;
    private final MetricsRegistry metrics;

    /**
     * Tracks how many sections have ever been submitted to this pool (across its whole
     * lifetime, not just currently in flight) — this is {@code ImportStageStatus.totalKnown()}.
     * Incremented once per {@code processTask} call, i.e. once per dequeue, not once per
     * {@code submit} — {@code MeshTaskQueue} does not currently expose a submit-time hook,
     * so "known" here means "the worker pool has seen and started processing it", which is
     * an accepted approximation for the P0 profiling checkpoint's throughput metric: it
     * slightly understates totalKnown while a large cold-start batch is still queued and not
     * yet dequeued, but never overstates it.
     */
    private final AtomicLong totalKnown = new AtomicLong();

    /** Sections currently inside {@code processTask} (mesh pipeline running), for {@code ImportStageStatus.activelyBuilding()}. */
    private final AtomicInteger activelyBuilding = new AtomicInteger();

    /** Sections that finished {@code processTask} without throwing, for {@code ImportStageStatus.completed()} — counts both accepted and deduplicated-away results, since both represent finished work, not backlog. */
    private final AtomicLong completed = new AtomicLong();

    /**
     * Creates and immediately starts worker threads.
     *
     * @param workerCount     number of threads (from EVConfig.workerThreadCount())
     * @param taskQueue       shared queue workers poll from
     * @param sectionCache    used to acquire WorldSectionHandle per task
     * @param pipelineRunner  full meshing pipeline
     * @param deduplicator    deduplication check before accepting result
     * @param meshingContext  MeshingContext used for all tasks
     * @param metrics         sink for {@link ImportStageStatus} reporting (P0 profiling checkpoint's
     *                        throughput/stage-breakdown metric); must not be null
     */
    public MeshWorkerPool(int workerCount,
                          MeshTaskQueue<MeshTask> taskQueue,
                          SectionCache sectionCache,
                          MeshingPipelineRunner pipelineRunner,
                          GeometryChangeDeduplicator deduplicator,
                          MeshingContext meshingContext,
                          MetricsRegistry metrics) {
        this.taskQueue = Objects.requireNonNull(taskQueue);
        this.sectionCache = Objects.requireNonNull(sectionCache);
        this.pipelineRunner = Objects.requireNonNull(pipelineRunner);
        this.deduplicator = Objects.requireNonNull(deduplicator);
        this.meshingContext = Objects.requireNonNull(meshingContext);
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");

        this.executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "ev-mesh-worker");
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
        LOGGER.info("Started {} EV meshing worker threads", workerCount);
    }

    private void workerLoop() {
        while (running.get()) {
            MeshTask task;
            try {
                task = taskQueue.pollNonBlocking();
                if (task == null) {
                    // Нет работы — короткий sleep вместо blocking poll,
                    // чтобы running.get() check срабатывал при shutdown
                    Thread.sleep(10);
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            processTask(task);
        }
    }

    private void processTask(MeshTask task) {
        SectionPos pos = task.section();
        totalKnown.incrementAndGet();
        activelyBuilding.incrementAndGet();
        WorldSectionHandle handle = null;
        try {
            handle = sectionCache.acquire(pos.encode(), true);
            if (handle == null || handle.isEmpty()) {
                return; // Секция не существует или пуста — мешить нечего
            }

            MeshletBatch batch = pipelineRunner.build(handle, meshingContext);

            // Дедупликация: проверяем, изменилась ли геометрия
            List<Quad> allQuads = new ArrayList<>();
            batch.meshlets().forEach(m -> allQuads.addAll(m.quads()));
            long hash = deduplicator.hashQuads(allQuads);
            if (deduplicator.recordAndCheckChanged(pos, hash)) {
                completedBatches.add(batch);
            }

        } catch (Exception e) {
            LOGGER.warn("Meshing failed for section {}", pos, e);
        } finally {
            activelyBuilding.decrementAndGet();
            completed.incrementAndGet();
            reportImportStageStatus();
            if (handle != null) {
                handle.release();
            }
        }
    }

    /**
     * Reports the current {@link ImportStageStatus} snapshot to {@link #metrics}. Called once
     * per finished task (success or failure) rather than throttled to a fixed interval like
     * {@code MeshTaskQueue}'s queue-depth reporting — {@code ImportStageStatus} construction here
     * is a handful of volatile reads, not a lock acquisition, so per-task overhead is negligible
     * compared to the meshing work itself (see {@code GreedyMeshStage}, which dominates
     * meshing-thread CPU time per {@code PROFILING_RESULTS.md}).
     * <p>
     * {@code retryingAfterFailure} is always reported as 0: this MVP worker pool has no retry
     * logic (a failed task, see the catch block above, is logged and dropped, not requeued) — 0 is
     * a factual statement about current architecture, not a placeholder pending a future feature.
     */
    private void reportImportStageStatus() {
        metrics.recordImportStageStatus(new ImportStageStatus(
                0, // queuedForStorage - currently sync loaded inside meshing
                0, // activelyLoadingStorage
                taskQueue.size(), // queuedForMeshing
                activelyBuilding.get(), // activelyMeshing
                0, // queuedForGpuUpload - tracked externally if needed
                0, // activelyUploading
                0, // retryingAfterFailure — no retry path exists in this MVP worker pool
                (int) Math.min(completed.get(), Integer.MAX_VALUE),
                (int) Math.min(totalKnown.get(), Integer.MAX_VALUE)
        ));
    }

    /**
     * Drains completed batches (called on render thread). Returns null when empty.
     */
    public MeshletBatch pollCompleted() {
        return completedBatches.poll();
    }

    /**
     * Whether there are completed batches waiting to be picked up.
     */
    public boolean hasCompleted() {
        return !completedBatches.isEmpty();
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("Mesh worker pool did not terminate cleanly within 2s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("EV meshing worker pool shut down");
    }
}
