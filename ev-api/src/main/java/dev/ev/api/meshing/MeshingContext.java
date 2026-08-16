package dev.ev.api.meshing;

/**
 * Per-invocation context passed to mesh building, carrying anything that isn't
 * part of the section's own voxel data but affects mesh generation (e.g. neighbor
 * section handles for greedy-merge across boundaries, material palette lookups).
 * Implementations are provided by the caller (ev-render), consumed by
 * ev-meshing without ev-meshing needing to know where they came from.
 */
public interface MeshingContext {

    /**
     * Returns the palette index of the voxel immediately adjacent to this section's
     * boundary, needed for correct greedy-mesh quad merging across section edges.
     * faceDirection: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z.
     * (a, b) are the two local coordinates spanning that face, each in [0, 31].
     */
    int getNeighborBoundaryVoxel(int faceDirection, int a, int b);

    /** Resolves a palette index to an opaque material identifier used for atlas binning. */
    int resolveMaterialId(int paletteIndex);
}
