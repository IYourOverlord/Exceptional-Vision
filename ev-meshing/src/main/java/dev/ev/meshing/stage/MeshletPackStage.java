package dev.ev.meshing.stage;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Meshlet;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.api.meshing.Quad;

import java.util.ArrayList;
import java.util.List;

/**
 * Final stage of the meshing pipeline: packs MaterialBins into MeshletBatch, splitting
 * any bin whose quad count exceeds MeshletBatch.MAX_QUADS_PER_MESHLET into multiple
 * Meshlets, and computing each Meshlet's bounding box from its quads' voxel-boundary
 * coordinates.
 *
 * <p>Splitting strategy: quads within one material bin are consumed in order, filling
 * each Meshlet up to MAX_QUADS_PER_MESHLET before starting a new one — no attempt at
 * spatial clustering beyond the order already produced by greedy meshing (which tends
 * to be reasonably spatially coherent since it processes slices in order). A future
 * ticket may improve this with explicit spatial clustering; out of scope here.
 */
public final class MeshletPackStage {

    private static final int MAX_QUADS_PER_MESHLET = MeshletBatch.MAX_QUADS_PER_MESHLET;

    public MeshletBatch process(SectionPos section, List<MaterialBin> bins) {
        List<Meshlet> meshlets = new ArrayList<>();

        for (MaterialBin bin : bins) {
            List<Quad> quads = bin.quads();
            for (int start = 0; start < quads.size(); start += MAX_QUADS_PER_MESHLET) {
                int end = Math.min(start + MAX_QUADS_PER_MESHLET, quads.size());
                List<Quad> chunk = List.copyOf(quads.subList(start, end));
                meshlets.add(toMeshlet(chunk));
            }
        }

        return new MeshletBatch(section, meshlets);
    }

    private Meshlet toMeshlet(List<Quad> quads) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (Quad quad : quads) {
            float[] bounds = quadWorldBounds(quad);
            minX = Math.min(minX, bounds[0]);
            minY = Math.min(minY, bounds[1]);
            minZ = Math.min(minZ, bounds[2]);
            maxX = Math.max(maxX, bounds[3]);
            maxY = Math.max(maxY, bounds[4]);
            maxZ = Math.max(maxZ, bounds[5]);
        }

        return new Meshlet(quads, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Computes one Quad's spatial extent (in local section coordinates, 0..32) as
     * {@code {minX, minY, minZ, maxX, maxY, maxZ}}.
     *
     * <p><b>This is the single place this logic lives</b> — GPU-side code consumes the
     * already-computed {@link Meshlet} bounding box and must not recompute it.
     *
     * <p>Axis interpretation follows the exact width/height convention fixed by
     * {@code GreedyMeshStage} (ticket 11, requirement 4a), which produces every {@link Quad}
     * and must be interpreted identically here:
     * <pre>
     * faceDirection | normal axis | width axis | height axis
     *       0 (+X)  |      X      |      Y     |      Z
     *       1 (-X)  |      X      |      Y     |      Z
     *       2 (+Y)  |      Y      |      Z     |      X
     *       3 (-Y)  |      Y      |      Z     |      X
     *       4 (+Z)  |      Z      |      X     |      Y
     *       5 (-Z)  |      Z      |      X     |      Y
     * </pre>
     * The normal axis has zero thickness at the quad's own coordinate on that axis
     * ({@code quad.x()}, {@code quad.y()} or {@code quad.z()}, whichever axis is the
     * normal for this faceDirection). The width axis spans
     * {@code [origin, origin + width)} and the height axis spans
     * {@code [origin, origin + height)}, where {@code origin} is the corresponding
     * component of {@code (quad.x(), quad.y(), quad.z())}.
     */
    static float[] quadWorldBounds(Quad quad) {
        float x = quad.x();
        float y = quad.y();
        float z = quad.z();
        float width = quad.width();
        float height = quad.height();

        float minX = x;
        float minY = y;
        float minZ = z;
        float maxX = x;
        float maxY = y;
        float maxZ = z;

        switch (quad.faceDirection()) {
            case 0, 1 -> { // normal = X, width = Y, height = Z
                maxY = y + width;
                maxZ = z + height;
            }
            case 2, 3 -> { // normal = Y, width = Z, height = X
                maxZ = z + width;
                maxX = x + height;
            }
            case 4, 5 -> { // normal = Z, width = X, height = Y
                maxX = x + width;
                maxY = y + height;
            }
            default -> throw new IllegalArgumentException(
                "faceDirection must be 0..5: " + quad.faceDirection());
        }

        return new float[] {minX, minY, minZ, maxX, maxY, maxZ};
    }
}
