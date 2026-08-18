package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;
import dev.ev.meshing.priority.MeshPriority;
import dev.ev.meshing.queue.MeshTaskQueue;
import dev.ev.render.dirty.DirtySectionTracker;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Called once per tick/frame from EVInstance: drains dirty sections from
 * {@link DirtySectionTracker} and submits them to {@link MeshTaskQueue} with
 * priorities computed via {@link MeshPriority#computeWithNearTierCheck}.
 * <p>
 * Also manages initial section scheduling: when new sections enter the visible range
 * (e.g. on world load or camera movement), they can be submitted via
 * {@link #scheduleSection(SectionPos, float, float, float, float, float, float)}.
 */
public final class MeshSchedulingCoordinator {

    private static final float FACING_THRESHOLD = (float) Math.cos(Math.toRadians(60));

    private final MeshTaskQueue<MeshTask> taskQueue;
    private final DirtySectionTracker dirtyTracker;
    private final AtomicLong insertionSeqCounter = new AtomicLong(0);

    public MeshSchedulingCoordinator(MeshTaskQueue<MeshTask> taskQueue,
                                      DirtySectionTracker dirtyTracker) {
        this.taskQueue = Objects.requireNonNull(taskQueue);
        this.dirtyTracker = Objects.requireNonNull(dirtyTracker);
    }

    /**
     * Drains all dirty sections from the tracker and submits them to the task queue
     * with computed priorities. Should be called once per tick from the main thread.
     *
     * @param cameraX    camera world X
     * @param cameraY    camera world Y
     * @param cameraZ    camera world Z
     * @param viewDirX   normalized camera forward X
     * @param viewDirY   normalized camera forward Y
     * @param viewDirZ   normalized camera forward Z
     */
    public void drainDirtyAndSchedule(float cameraX, float cameraY, float cameraZ,
                                       float viewDirX, float viewDirY, float viewDirZ) {
        if (!dirtyTracker.hasPendingWork()) {
            return;
        }

        Set<SectionPos> dirtySections = dirtyTracker.getDirtySections();
        for (SectionPos section : dirtySections) {
            long priority = MeshPriority.computeWithNearTierCheck(
                section, SectionPos.MAX_LOD_LEVEL, 0,
                cameraX, cameraY, cameraZ,
                viewDirX, viewDirY, viewDirZ,
                FACING_THRESHOLD, insertionSeqCounter.getAndIncrement()
            );
            taskQueue.submit(priority, new MeshTask(section));
            dirtyTracker.clearSection(section);
        }
    }

    /**
     * Schedules a single section for meshing (e.g. initial load, camera moved into new area).
     */
    public void scheduleSection(SectionPos section,
                                 float cameraX, float cameraY, float cameraZ,
                                 float viewDirX, float viewDirY, float viewDirZ) {
        long priority = MeshPriority.computeWithNearTierCheck(
            section, SectionPos.MAX_LOD_LEVEL, 0,
            cameraX, cameraY, cameraZ,
            viewDirX, viewDirY, viewDirZ,
            FACING_THRESHOLD, insertionSeqCounter.getAndIncrement()
        );
        taskQueue.submit(priority, new MeshTask(section));
    }
}
