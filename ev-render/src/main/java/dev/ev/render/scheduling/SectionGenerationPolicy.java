package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;


/**
 * Decides whether a given section at a given distance should be populated via
 * full block-level voxelization (accurate but expensive) or via
 * {@link dev.ev.storage.coarsegen.CoarseSectionGenerator} (fast heightmap-based
 * approximation, acceptable for distant LODs).
 * <p>
 * <b>MVP policy:</b> sections at LOD level 0 always get full voxelization;
 * LOD level >= {@link #COARSE_THRESHOLD_LEVEL} use coarse generation.
 * The threshold is fixed at 2 for MVP — tune via config or SSE metric
 * after profiling (P0-profiling-checkpoint.md).
 */
public final class SectionGenerationPolicy {

    /**
     * LOD level at which coarse generation is used instead of full voxelization.
     * Level 2 = each voxel covers 4×4×4 blocks, enough that per-block detail is
     * not visually distinguishable at typical viewing distances for that LOD.
     */
    public static final int COARSE_THRESHOLD_LEVEL = 2;

    /**
     * Returns true if the section should use CoarseSectionGenerator (heightmap-based),
     * false if it requires full block-by-block voxelization.
     *
     * @param section the section position (level determines the decision)
     * @return true for coarse generation, false for full voxelization
     */
    public boolean shouldUseCoarse(SectionPos section) {
        return section.level() >= COARSE_THRESHOLD_LEVEL;
    }
}
