package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.api.meshing.Quad;
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

    /**
     * Creates and immediately starts worker threads.
     *
     * @param workerCount     number of threads (from EVConfig.workerThreadCount())
     * @param taskQueue       shared queue workers poll from
     * @param sectionCache    used to acquire WorldSectionHandle per task
     * @param pipelineRunner  full meshing pipeline
     * @param deduplicator    deduplication check before accepting result
     * @param meshingContext  MeshingContext used for all tasks
     */
    public MeshWorkerPool(int workerCount,
                          MeshTaskQueue<MeshTask> taskQueue,
                          SectionCache sectionCache,
                          MeshingPipelineRunner pipelineRunner,
                          GeometryChangeDeduplicator deduplicator,
                          MeshingContext meshingContext) {
        this.taskQueue = Objects.requireNonNull(taskQueue);
        this.sectionCache = Objects.requireNonNull(sectionCache);
        this.pipelineRunner = Objects.requireNonNull(pipelineRunner);
        this.deduplicator = Objects.requireNonNull(deduplicator);
        this.meshingContext = Objects.requireNonNull(meshingContext);

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
            if (handle != null) {
                handle.release();
            }
        }
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
