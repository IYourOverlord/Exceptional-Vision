package org.ex.exceptionalvision.world.lod;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy-meshes a {@link ColumnGrid} into {@link PackedQuad}s:
 * <ul>
 *   <li>one merged top-face rectangle per contiguous same-(height, material) region
 *       (the classic 2D "expand width, then expand height" greedy algorithm — see
 *       e.g. Mikola Lysenko's "Meshing in a Minecraft Game" for the general technique;
 *       this is an independent implementation against our own {@link ColumnGrid} data,
 *       not derived from any specific mod's source, per {@code 01_legal_constraints.md});</li>
 *   <li>vertical "wall" quads at height discontinuities between neighboring cells, so
 *       the LOD silhouette doesn't have a visible gap where one column is taller than
 *       its neighbor.</li>
 * </ul>
 * <b>Scope note:</b> wall quads are only generated at <em>interior</em> cell boundaries
 * (never at the outer edge of the node's 16x16 grid) — stitching the seam between two
 * sibling LOD nodes needs to know about the neighboring node, which this class doesn't
 * have access to, so that's left to border/fade handling in a later stage
 * (see "стыковка" in {@code 07_forge_integration.md}).
 * <p>
 * <b>packed1 width/height convention</b> (not fully pinned down by
 * {@code 03_data_formats.md}, so documented here for whatever consumes it next — the
 * stage-4 GPU vertex shader must reconstruct geometry using the same convention):
 * <ul>
 *   <li>top faces (axis {@code +Y}): width = extent along local X, height = extent along local Z;</li>
 *   <li>{@code ±X} walls: width = extent along local Z (the merge-run direction), height = vertical extent in blocks;</li>
 *   <li>{@code ±Z} walls: width = extent along local X (the merge-run direction), height = vertical extent in blocks.</li>
 * </ul>
 */
public final class GreedyMesher {

    /** packed1's height field is 6 bits (0..63); a taller wall is split into stacked quads. */
    private static final int MAX_WALL_QUAD_HEIGHT = 63;

    /** Encodes world Y (-64..320) as an unsigned value so it fits PackedQuad's 12-bit y field; see 03_data_formats.md. */
    static final int Y_OFFSET = 64;

    private static final int AXIS_POS_X = 0;
    private static final int AXIS_NEG_X = 1;
    private static final int AXIS_POS_Y = 2;
    private static final int AXIS_POS_Z = 4;
    private static final int AXIS_NEG_Z = 5;

    public List<PackedQuad> mesh(ColumnGrid grid) {
        List<PackedQuad> quads = new ArrayList<>();
        meshTopFaces(grid, quads);
        meshWallsAlongX(grid, quads);
        meshWallsAlongZ(grid, quads);
        return quads;
    }

    // ---- top faces ------------------------------------------------------------------

    private void meshTopFaces(ColumnGrid grid, List<PackedQuad> out) {
        boolean[] merged = new boolean[ColumnGrid.SIZE * ColumnGrid.SIZE];
        for (int z = 0; z < ColumnGrid.SIZE; z++) {
            for (int x = 0; x < ColumnGrid.SIZE; x++) {
                if (merged[ColumnGrid.index(x, z)] || !grid.present(x, z)) {
                    continue;
                }
                int height = grid.height(x, z);
                int material = grid.material(x, z);
                int light = grid.light(x, z);

                int width = 1;
                while (x + width < ColumnGrid.SIZE && canMergeTop(grid, merged, x + width, z, height, material)) {
                    width++;
                }

                int depth = 1;
                expand:
                while (z + depth < ColumnGrid.SIZE) {
                    for (int dx = 0; dx < width; dx++) {
                        if (!canMergeTop(grid, merged, x + dx, z + depth, height, material)) {
                            break expand;
                        }
                    }
                    depth++;
                }

                for (int dz = 0; dz < depth; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        merged[ColumnGrid.index(x + dx, z + dz)] = true;
                    }
                }

                out.add(PackedQuad.of(x, height + Y_OFFSET, z, AXIS_POS_Y, width, depth, material, light));
            }
        }
    }

    private boolean canMergeTop(ColumnGrid grid, boolean[] merged, int x, int z, int height, int material) {
        int idx = ColumnGrid.index(x, z);
        return !merged[idx] && grid.present(x, z) && grid.height(x, z) == height && grid.material(x, z) == material;
    }

    // ---- interior walls ---------------------------------------------------------------

    /** Boundaries between (x, z) and (x + 1, z) — walls facing along the X axis. */
    private void meshWallsAlongX(ColumnGrid grid, List<PackedQuad> out) {
        for (int x = 0; x < ColumnGrid.SIZE - 1; x++) {
            int z = 0;
            while (z < ColumnGrid.SIZE) {
                WallSegment seg = wallBetween(grid, x, z, x + 1, z);
                if (seg == null) {
                    z++;
                    continue;
                }
                int runLength = 1;
                while (z + runLength < ColumnGrid.SIZE) {
                    WallSegment next = wallBetween(grid, x, z + runLength, x + 1, z + runLength);
                    if (next == null || !next.sameFaceAs(seg)) {
                        break;
                    }
                    runLength++;
                }
                emitWallRun(out, seg, /* runCoordStart */ z, runLength);
                z += runLength;
            }
        }
    }

    /** Boundaries between (x, z) and (x, z + 1) — walls facing along the Z axis. */
    private void meshWallsAlongZ(ColumnGrid grid, List<PackedQuad> out) {
        for (int z = 0; z < ColumnGrid.SIZE - 1; z++) {
            int x = 0;
            while (x < ColumnGrid.SIZE) {
                WallSegment seg = wallBetween(grid, x, z, x, z + 1);
                if (seg == null) {
                    x++;
                    continue;
                }
                int runLength = 1;
                while (x + runLength < ColumnGrid.SIZE) {
                    WallSegment next = wallBetween(grid, x + runLength, z, x + runLength, z + 1);
                    if (next == null || !next.sameFaceAs(seg)) {
                        break;
                    }
                    runLength++;
                }
                emitWallRun(out, seg, /* runCoordStart */ x, runLength);
                x += runLength;
            }
        }
    }

    /**
     * Describes the exposed vertical face (if any) between two adjacent cells, oriented
     * so the taller column's outward-facing side is what gets meshed (the shorter
     * column has nothing to hide behind it, from above).
     */
    private WallSegment wallBetween(ColumnGrid grid, int lowSideX, int lowSideZ, int highSideX, int highSideZ) {
        boolean aPresent = grid.present(lowSideX, lowSideZ);
        boolean bPresent = grid.present(highSideX, highSideZ);
        if (!aPresent && !bPresent) {
            return null;
        }
        int aHeight = aPresent ? grid.height(lowSideX, lowSideZ) : Integer.MIN_VALUE;
        int bHeight = bPresent ? grid.height(highSideX, highSideZ) : Integer.MIN_VALUE;
        if (aHeight == bHeight) {
            return null; // flush, nothing to fill
        }

        boolean aTaller = aHeight > bHeight;
        // lowSideZ == highSideZ  -> this pair varies in X (an "along X" boundary), face normal points along X.
        // lowSideX == highSideX  -> this pair varies in Z (an "along Z" boundary), face normal points along Z.
        int axis;
        if (lowSideZ == highSideZ) {
            axis = aTaller ? AXIS_POS_X : AXIS_NEG_X;
        } else {
            axis = aTaller ? AXIS_POS_Z : AXIS_NEG_Z;
        }

        int lowY = Math.min(aHeight, bHeight);
        int highY = Math.max(aHeight, bHeight);
        int material = aTaller ? grid.material(lowSideX, lowSideZ) : grid.material(highSideX, highSideZ);
        int light = aTaller ? grid.light(lowSideX, lowSideZ) : grid.light(highSideX, highSideZ);
        // The boundary plane sits at the same coordinate regardless of which side is taller —
        // anchor it at the higher-index cell consistently, so the shader only needs `axis` to
        // know which way the quad faces.
        int fixed = lowSideZ == highSideZ ? highSideX : highSideZ;

        return new WallSegment(axis, fixed, lowY, highY, material, light);
    }

    private void emitWallRun(List<PackedQuad> out, WallSegment seg, int runCoordStart, int runLength) {
        int remaining = seg.highY - seg.lowY;
        int currentLowY = seg.lowY;
        while (remaining > 0) {
            int chunkHeight = Math.min(remaining, MAX_WALL_QUAD_HEIGHT);
            boolean alongX = seg.axis == AXIS_POS_X || seg.axis == AXIS_NEG_X;
            int localX = alongX ? seg.fixedLocalCoord : runCoordStart;
            int localZ = alongX ? runCoordStart : seg.fixedLocalCoord;
            out.add(PackedQuad.of(localX, currentLowY + Y_OFFSET, localZ, seg.axis, runLength, chunkHeight, seg.material, seg.light));
            currentLowY += chunkHeight;
            remaining -= chunkHeight;
        }
    }

    private static final class WallSegment {
        final int axis;
        final int fixedLocalCoord;
        final int lowY;
        final int highY;
        final int material;
        final int light;

        WallSegment(int axis, int fixedLocalCoord, int lowY, int highY, int material, int light) {
            this.axis = axis;
            this.fixedLocalCoord = fixedLocalCoord;
            this.lowY = lowY;
            this.highY = highY;
            this.material = material;
            this.light = light;
        }

        boolean sameFaceAs(WallSegment o) {
            return axis == o.axis && fixedLocalCoord == o.fixedLocalCoord
                    && lowY == o.lowY && highY == o.highY
                    && material == o.material && light == o.light;
        }
    }
}
