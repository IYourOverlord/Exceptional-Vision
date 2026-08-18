package dev.ev.render.scheduling;

import dev.ev.api.meshing.MeshingContext;

/**
 * MVP {@link MeshingContext}: all cross-section-boundary neighbors report air (palette 0),
 * so every boundary face is emitted as visible. This creates redundant geometry at section
 * seams (two abutting opaque sections each emit a face toward the other), but is correct
 * in the sense that no face that SHOULD be visible is ever missed — the opposite of a gap.
 * <p>
 * Material resolution is identity (materialId == paletteIndex), which is sufficient for
 * the MVP since the rendering pipeline does not yet texture by material.
 */
public final class SimpleMeshingContext implements MeshingContext {

    /** Singleton — this context is stateless and safe to share across threads. */
    public static final SimpleMeshingContext INSTANCE = new SimpleMeshingContext();

    private SimpleMeshingContext() {}

    @Override
    public int getNeighborBoundaryVoxel(int faceDirection, int a, int b) {
        // Air = 0 → boundary face is always visible
        return 0;
    }

    @Override
    public int resolveMaterialId(int paletteIndex) {
        return paletteIndex;
    }
}
