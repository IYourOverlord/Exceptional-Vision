package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;

/**
 * Unit of work in the meshing queue: identifies which section needs (re)meshing.
 * The worker thread acquires the actual voxel data via SectionCache when it picks
 * up this task — keeping the task lightweight and avoiding retain/release lifecycle
 * complexity in the queue itself.
 */
public record MeshTask(SectionPos section) {}
