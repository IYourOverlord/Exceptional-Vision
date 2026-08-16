package dev.ev.api.meshing;

import java.util.List;

/**
 * A bounded batch of quads sharing spatial locality, with a precomputed bounding box
 * for GPU-side occlusion/frustum culling at sub-section granularity.
 */
public record Meshlet(
    List<Quad> quads,
    float boundsMinX, float boundsMinY, float boundsMinZ,
    float boundsMaxX, float boundsMaxY, float boundsMaxZ
) {
    public Meshlet {
        if (quads.size() > MeshletBatch.MAX_QUADS_PER_MESHLET) {
            throw new IllegalArgumentException(
                "Meshlet exceeds MAX_QUADS_PER_MESHLET: " + quads.size());
        }
    }
}
