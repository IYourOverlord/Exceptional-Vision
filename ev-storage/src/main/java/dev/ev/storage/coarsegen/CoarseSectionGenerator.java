package dev.ev.storage.coarsegen;

import dev.ev.api.SectionPos;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.storage.codec.PaletteCodec;

/**
 * Generates approximate voxel content for a coarse (high-LOD) section directly from
 * a HeightmapSource, without reading individual blocks. Intended for LOD levels where
 * per-block accuracy is not visually distinguishable (see PERFORMANCE_MATH.md section
 * A.2/A.3) — the caller decides the LOD threshold above which this generator is used
 * instead of full voxelization; this class only implements the sampling/aggregation
 * logic, not the threshold policy.
 *
 * <h2>Sampling strategy (O(1) per voxel, not O(area))</h2>
 * For a section at LOD level {@code L} ({@code sizeInBlocks() = 32 << L}), each local
 * voxel-column ({@code localX}, {@code localZ} in {@code 0..31}) covers a
 * {@code sizeInBlocks()/32}-block-wide square of the world. Exactly one heightmap
 * column is sampled per voxel-column, at the center of that square (not a corner —
 * centering avoids a systematic directional bias in the aggregated silhouette). This
 * keeps the whole generation pass at {@code O(1)} work per output voxel-column
 * regardless of LOD level, which is the entire point of using this generator instead of
 * full per-block voxelization on coarse levels.
 *
 * <h2>Dominant-material simplification</h2>
 * The ticket permits (but does not require) trading a small, fixed number of extra
 * samples for a slightly less biased material estimate, as long as the result stays
 * {@code O(1)} per voxel-column (i.e. never proportional to the covered area). This
 * implementation takes the simplest allowed option: the material is read from the same
 * single center sample used for height, via {@link HeightmapSource#surfaceMaterial}.
 * This is an honest single-point estimate, not a majority vote over the covered area —
 * on a coarse level covering e.g. 64x64 blocks per voxel, a small pocket of a different
 * material near the sampled point could flip the material of the whole voxel-column.
 * That inaccuracy is accepted here because the alternative (sampling the area) would be
 * {@code O(area)}, which defeats the purpose of this class; the visual effect is limited
 * to color/texture at silhouette level, not to the presence/absence of geometry, which
 * is driven only by {@code surfaceHeight}.
 */
public final class CoarseSectionGenerator {

    /** Local grid width/height of a section, in voxel-columns per axis (see PaletteCodec.DIM). */
    private static final int DIM = PaletteCodec.DIM;

    /** Palette index used for empty/air voxels, matching the convention documented on PaletteCodec. */
    private static final int AIR = 0;

    private final HeightmapSource source;
    private final MetricsRegistry metrics;

    /**
     * @param source heightmap/material data source; must not depend on Minecraft/NeoForge classes
     */
    public CoarseSectionGenerator(HeightmapSource source) {
        this(source, null);
    }

    /**
     * @param source  heightmap/material data source; must not depend on Minecraft/NeoForge classes
     * @param metrics optional metrics sink; if non-null, {@code recordCounter("coarsegen.uniformSections", 1)}
     *                is reported every time a generated section turns out fully uniform (all-air or
     *                all-material), a cheap and common case worth tracking (see ticket requirement 5).
     *                May be {@code null} to skip metrics entirely.
     */
    public CoarseSectionGenerator(HeightmapSource source, MetricsRegistry metrics) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
        this.metrics = metrics;
    }

    /**
     * Populates the given section handle by sampling one heightmap column per voxel
     * column (32x32 columns per section, regardless of LOD level — each voxel column
     * covers sizeInBlocks()/32 world blocks per axis), taking the dominant surface
     * material and filling voxels from bedrock-relative bottom up to the sampled
     * surface height (quantized to this section's voxel resolution).
     *
     * <h2>Unavailable-data policy</h2>
     * If {@link HeightmapSource#isAvailable} returns {@code false} for a sampled point,
     * this method does not throw. Instead it skips writing that voxel-column entirely,
     * leaving whatever value the target handle already had there (typically air, for a
     * freshly-allocated section), and records the column as "incomplete" in the returned
     * {@link GenerationResult}. This is preferred over silently defaulting to air:
     * defaulting to air would be indistinguishable from "confirmed no terrain here" and
     * could bake a false horizon gap into the coarse LOD that never gets corrected. By
     * surfacing incompleteness in the result, the caller (which owns the retry/scheduling
     * policy — out of scope here) can decide to re-run generation for this section once
     * the underlying chunk data becomes available.
     *
     * @param target section to populate; must be a freshly-relevant handle at the LOD
     *               level the caller wants coarse-generated (this method does not itself
     *               decide whether coarse generation is appropriate for the level).
     * @return a summary of the generation pass, including whether any voxel-columns were
     *         skipped due to unavailable heightmap data.
     */
    public GenerationResult generate(WorldSectionHandle target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }

        SectionPos pos = target.position();
        int sizeInBlocks = pos.sizeInBlocks();
        int voxelSizeInBlocks = sizeInBlocks / DIM;
        long minBlockX = pos.minBlockX();
        long minBlockY = pos.minBlockY();
        long minBlockZ = pos.minBlockZ();

        int[] flat = new int[PaletteCodec.SECTION_SIZE];
        boolean anyIncomplete = false;

        for (int localZ = 0; localZ < DIM; localZ++) {
            long columnMinZ = minBlockZ + (long) localZ * voxelSizeInBlocks;
            int sampleWorldZ = (int) (columnMinZ + voxelSizeInBlocks / 2);

            for (int localX = 0; localX < DIM; localX++) {
                long columnMinX = minBlockX + (long) localX * voxelSizeInBlocks;
                int sampleWorldX = (int) (columnMinX + voxelSizeInBlocks / 2);

                if (!source.isAvailable(sampleWorldX, sampleWorldZ)) {
                    anyIncomplete = true;
                    // Leave this voxel-column untouched in `flat` (defaults to AIR=0) and
                    // in `target` (whatever it already contained) — see policy in the
                    // Javadoc above. We still need *a* value in `flat` for the
                    // uniform-check/encode step below; AIR is the least presumptuous
                    // default and matches a freshly-allocated section's existing state.
                    fillColumnFlat(flat, localX, localZ, -1, AIR); // -1 => whole column air
                    continue;
                }

                int surfaceHeight = source.surfaceHeight(sampleWorldX, sampleWorldZ);
                int material = source.surfaceMaterial(sampleWorldX, sampleWorldZ);

                long quantizedLong = Math.floorDiv(surfaceHeight - minBlockY, voxelSizeInBlocks);
                int quantizedSurfaceLocalY = clamp(quantizedLong, -1, DIM);
                // clamp() intentionally allows one-past-range sentinels (-1 / DIM) so the
                // three cases below ("all air" / "all material" / "split at Y") are handled
                // uniformly without a separate branch.

                fillColumnFlat(flat, localX, localZ, quantizedSurfaceLocalY, material);
            }
        }

        boolean uniform = PaletteCodec.isUniform(flat);
        if (uniform && metrics != null) {
            metrics.recordCounter("coarsegen.uniformSections", 1);
        }

        writeFlatToHandle(target, flat);

        return new GenerationResult(!anyIncomplete, uniform);
    }

    /**
     * Fills one voxel-column of the flat {@code 32x32x32} array (index convention
     * {@code x + y*32 + z*32*32}, matching {@link PaletteCodec}): material from
     * {@code localY = 0} through {@code quantizedSurfaceLocalY} inclusive, air above.
     *
     * @param quantizedSurfaceLocalY may be {@code -1} (whole column air, section entirely
     *                               above the surface) or {@code DIM} (whole column
     *                               material, section entirely below the surface) as
     *                               sentinels, in addition to the normal {@code 0..31} range.
     */
    private static void fillColumnFlat(int[] flat, int localX, int localZ, int quantizedSurfaceLocalY, int material) {
        int base = localX + localZ * DIM * DIM;
        for (int localY = 0; localY < DIM; localY++) {
            int value = (localY <= quantizedSurfaceLocalY) ? material : AIR;
            flat[base + localY * DIM] = value;
        }
    }

    private static void writeFlatToHandle(WorldSectionHandle target, int[] flat) {
        for (int localZ = 0; localZ < DIM; localZ++) {
            for (int localY = 0; localY < DIM; localY++) {
                for (int localX = 0; localX < DIM; localX++) {
                    int value = flat[localX + localY * DIM + localZ * DIM * DIM];
                    target.setVoxel(localX, localY, localZ, value);
                }
            }
        }
    }

    private static int clamp(long value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return (int) value;
    }

    /**
     * Summary of one {@link #generate} call.
     *
     * @param complete true if every sampled voxel-column had available heightmap data;
     *                 false if one or more columns were skipped (see the unavailable-data
     *                 policy documented on {@link #generate}) and the caller may want to
     *                 re-run generation later.
     * @param uniform  true if the generated section turned out fully uniform (single
     *                 repeated palette value across all 32768 voxels), the cheap common
     *                 case flagged in the ticket's requirement 5.
     */
    public record GenerationResult(boolean complete, boolean uniform) {
    }
}
