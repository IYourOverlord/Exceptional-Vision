package dev.ev.meshing.priority;

import dev.ev.api.SectionPos;

/**
 * Determines the minimum LOD level whose voxel size projects to no more than
 * errorThresholdPx screen pixels at a given distance, per PERFORMANCE_MATH.md
 * section A.3:
 *
 * <pre>
 *   projectedError(voxelSize, distance, fovY, screenHeightPx) =
 *       (voxelSize / distance) * (screenHeightPx / (2 * tan(fovY / 2)))
 * </pre>
 *
 * The {@code (screenHeightPx / (2*tan(fovY/2)))} term is constant per-frame (only changes
 * on window resize / FOV change) and should be precomputed once per frame by the
 * caller and passed in as {@code projectionScale}, NOT recomputed per section (recomputing
 * {@code tan()} per section across thousands of sections per frame is measurably wasteful —
 * see PERFORMANCE_MATH.md A.3).
 */
public final class ScreenSpaceErrorMetric {

    private ScreenSpaceErrorMetric() {}

    /** Precompute once per frame: screenHeightPx / (2 * tan(fovYRadians / 2)). */
    public static float computeProjectionScale(float fovYRadians, int screenHeightPx) {
        return screenHeightPx / (2.0f * (float) Math.tan(fovYRadians / 2.0));
    }

    /**
     * voxelSizeAtLevel: size in blocks of a single voxel at the LOD level being
     * evaluated (NOT the section size — sizeInBlocks()/32, since a section is a
     * 32^3 grid of voxels).
     */
    public static float projectedErrorPx(float voxelSizeAtLevel, float distance, float projectionScale) {
        if (distance <= 0.0001f) {
            distance = 0.0001f; // avoid division blowup at/behind camera
        }
        return (voxelSizeAtLevel / distance) * projectionScale;
    }

    /**
     * Returns the coarsest (highest) LOD level in [0, SectionPos.MAX_LOD_LEVEL] whose
     * projected error is still <= errorThresholdPx at the given distance. Coarser is
     * preferred when multiple levels satisfy the threshold (less geometry = cheaper),
     * consistent with PERFORMANCE_MATH.md A.3's intent of not over-detailing what
     * isn't visually distinguishable.
     *
     * <p>Implemented as a linear scan over the 7 possible levels (0..6) — a binary search
     * would be correct too (since, for fixed distance, {@code voxelSizeAtLevel} grows
     * monotonically with level via {@code 1 << level}, and {@code projectedErrorPx} is
     * monotonically non-decreasing in {@code voxelSizeAtLevel} for fixed distance — see
     * the formula above) but is unnecessary complexity for only 7 candidate values.
     * Iterates from coarsest to finest and returns the first level that satisfies the
     * threshold; if even level 0 (finest) exceeds the threshold, returns 0 — the finest
     * available LOD is the closest this function can get to satisfying the caller's error
     * budget in that case.
     */
    public static int selectLodLevel(float distance, float projectionScale, float errorThresholdPx) {
        for (int level = SectionPos.MAX_LOD_LEVEL; level >= 0; level--) {
            // voxelSizeAtLevel = sizeInBlocks(level) / 32 = (32 << level) / 32 = 1 << level.
            // Computed directly (not via `new SectionPos(level, 0, 0, 0).sizeInBlocks() / 32f`)
            // to avoid an unnecessary allocation in a function that may run per section per frame.
            float voxelSizeAtLevel = 1 << level;
            float error = projectedErrorPx(voxelSizeAtLevel, distance, projectionScale);
            if (error <= errorThresholdPx) {
                return level;
            }
        }
        return 0;
    }
}
