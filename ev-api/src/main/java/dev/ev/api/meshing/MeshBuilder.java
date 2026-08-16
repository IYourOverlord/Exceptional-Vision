package dev.ev.api.meshing;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * Builds renderable geometry from a section's voxel data. Pure CPU-side computation,
 * no GPU access — implementations must be safely callable from any worker thread and
 * must not retain references to the WorldSectionHandle beyond the call (caller manages
 * its lifecycle via retain/release).
 */
public interface MeshBuilder {
    MeshletBatch build(WorldSectionHandle section, MeshingContext ctx);
}
