package dev.ev.meshing.stage;

import dev.ev.api.meshing.MeshingContext;

/**
 * Simple in-memory {@link MeshingContext} test double for meshing-stage tests.
 * By default the section is treated as surrounded by air on all sides
 * ({@link #getNeighborBoundaryVoxel} always returns palette index 0) and material
 * resolution is the identity function ({@link #resolveMaterialId} returns the
 * paletteIndex unchanged). Tests needing a non-air boundary neighbor can override
 * the lookup via {@link #setBoundaryLookup}. Call counters support fast-path
 * verification (e.g. "an empty section must not touch the context at all").
 */
final class FakeMeshingContext implements MeshingContext {

    /** (faceDirection, a, b) -> boundary voxel palette index. */
    @FunctionalInterface
    interface BoundaryLookup {
        int lookup(int faceDirection, int a, int b);
    }

    private BoundaryLookup boundaryLookup = (faceDirection, a, b) -> 0;
    private int getNeighborBoundaryVoxelCalls = 0;
    private int resolveMaterialIdCalls = 0;

    void setBoundaryLookup(BoundaryLookup boundaryLookup) {
        this.boundaryLookup = boundaryLookup;
    }

    @Override
    public int getNeighborBoundaryVoxel(int faceDirection, int a, int b) {
        getNeighborBoundaryVoxelCalls++;
        return boundaryLookup.lookup(faceDirection, a, b);
    }

    @Override
    public int resolveMaterialId(int paletteIndex) {
        resolveMaterialIdCalls++;
        return paletteIndex;
    }

    int getNeighborBoundaryVoxelCallCount() {
        return getNeighborBoundaryVoxelCalls;
    }

    int resolveMaterialIdCallCount() {
        return resolveMaterialIdCalls;
    }
}
