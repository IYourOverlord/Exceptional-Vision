package dev.ev.api.meshing;

import dev.ev.api.SectionPos;

import java.util.List;

/**
 * Output of the meshing pipeline for one section: a set of meshlets, each capped
 * at MAX_QUADS_PER_MESHLET quads, ready to be uploaded to a GPU buffer.
 * Splitting into fixed-size meshlets (rather than one variable-size mesh per section)
 * allows GPU-side per-meshlet visibility culling instead of only per-section culling.
 */
public record MeshletBatch(SectionPos section, List<Meshlet> meshlets) {

    public static final int MAX_QUADS_PER_MESHLET = 128;

    public int totalQuadCount() {
        return meshlets.stream().mapToInt(m -> m.quads().size()).sum();
    }
}
