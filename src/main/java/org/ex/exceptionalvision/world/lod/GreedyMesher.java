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
 *       its neighbor — both within a single node's own grid ({@link #meshWallsAlongX}/
 *       {@link #meshWallsAlongZ}) and, since this class only sees {@link ColumnGrid}s
 *       and never a node's world position, across the seam between two adjacent
 *       <em>same-level</em> nodes when the caller supplies both grids
 *       ({@link #meshBoundaryAlongX}/{@link #meshBoundaryAlongZ}; see {@link LodBuilder}
 *       for where that's wired up).</li>
 * </ul>
 * <b>Scope note:</b> {@link #meshBoundaryAlongX}/{@link #meshBoundaryAlongZ} only close
 * seams between two nodes of the <em>same</em> LOD level — a node bordering a
 * differently-leveled neighbor (i.e. a LOD distance-band transition) still has no real
 * geometric stitching there, and neither does a node at the edge of its region (no
 * cross-region grid access here). See "Баг 9" in {@code PROGRESS.md}.
 * <p>
 * {@link #meshSkirts} covers both of those remaining cases, but not by real stitching —
 * every node grows a downward-hanging "skirt" quad along its own outer edge,
 * independent of what (if anything) is known about the neighbor on the other side. This
 * hides a height mismatch at any unstitched seam (LOD-level transition, region edge, or
 * simply a not-yet-generated neighbor) as a shadowed drop rather than a see-through gap,
 * without requiring cross-level or cross-region grid access. It runs unconditionally on
 * every node, including edges {@code stitchLevel} already closes with real geometry —
 * see {@link #meshSkirts}'s own javadoc for why that's harmless.
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

    /** Minimum generatable world Y since 1.18 (see {@code 05_world_data_ingestion.md}); used to clamp skirt geometry. */
    private static final int WORLD_MIN_Y = -64;

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
        meshSkirts(grid, quads);
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
                WallSegment seg = wallBetween(grid, x, z, grid, x + 1, z);
                if (seg == null) {
                    z++;
                    continue;
                }
                int runLength = 1;
                while (z + runLength < ColumnGrid.SIZE) {
                    WallSegment next = wallBetween(grid, x, z + runLength, grid, x + 1, z + runLength);
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
                WallSegment seg = wallBetween(grid, x, z, grid, x, z + 1);
                if (seg == null) {
                    x++;
                    continue;
                }
                int runLength = 1;
                while (x + runLength < ColumnGrid.SIZE) {
                    WallSegment next = wallBetween(grid, x + runLength, z, grid, x + runLength, z + 1);
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

    // ---- cross-node boundary stitching (same LOD level only) --------------------------

    /**
     * Meshes the seam between two horizontally-adjacent, <em>same-level</em> nodes'
     * grids: {@code west}'s column {@code SIZE-1} against {@code east}'s column
     * {@code 0}, for every {@code z}. Mirrors {@link #meshWallsAlongX} exactly, except
     * the two sides of each comparison come from different grids instead of the same
     * one, and the resulting quads are always anchored at local X {@code 0} — i.e. they
     * belong in {@code east}'s coordinate frame (the caller is responsible for
     * appending the result to {@code east}'s own quad list, not {@code west}'s).
     * <p>
     * Only closes seams between nodes of the <em>same</em> LOD level within the same
     * region — a node bordering a differently-leveled neighbor (a LOD band transition)
     * or a neighbor in an adjacent region isn't handled here; both remain open (see
     * {@code PROGRESS.md}, "Баг 9").
     */
    public List<PackedQuad> meshBoundaryAlongX(ColumnGrid west, ColumnGrid east) {
        List<PackedQuad> out = new ArrayList<>();
        int z = 0;
        while (z < ColumnGrid.SIZE) {
            WallSegment seg = wallBetween(west, ColumnGrid.SIZE - 1, z, east, 0, z);
            if (seg == null) {
                z++;
                continue;
            }
            int runLength = 1;
            while (z + runLength < ColumnGrid.SIZE) {
                WallSegment next = wallBetween(west, ColumnGrid.SIZE - 1, z + runLength, east, 0, z + runLength);
                if (next == null || !next.sameFaceAs(seg)) {
                    break;
                }
                runLength++;
            }
            emitWallRun(out, seg, /* runCoordStart */ z, runLength);
            z += runLength;
        }
        return out;
    }

    /**
     * Meshes the seam between two vertically-adjacent (in the Z sense), <em>same-level</em>
     * nodes' grids: {@code south}'s row {@code SIZE-1} against {@code north}'s row
     * {@code 0}, for every {@code x}. See {@link #meshBoundaryAlongX} for the general
     * shape of this — same caveats apply, and the resulting quads belong in
     * {@code north}'s coordinate frame (local Z {@code 0}).
     */
    public List<PackedQuad> meshBoundaryAlongZ(ColumnGrid south, ColumnGrid north) {
        List<PackedQuad> out = new ArrayList<>();
        int x = 0;
        while (x < ColumnGrid.SIZE) {
            WallSegment seg = wallBetween(south, x, ColumnGrid.SIZE - 1, north, x, 0);
            if (seg == null) {
                x++;
                continue;
            }
            int runLength = 1;
            while (x + runLength < ColumnGrid.SIZE) {
                WallSegment next = wallBetween(south, x + runLength, ColumnGrid.SIZE - 1, north, x + runLength, 0);
                if (next == null || !next.sameFaceAs(seg)) {
                    break;
                }
                runLength++;
            }
            emitWallRun(out, seg, /* runCoordStart */ x, runLength);
            x += runLength;
        }
        return out;
    }

    // ---- skirts (mask seams at LOD-level transitions / region edges) -----------------

    /**
     * A skirt's vertical extent, in raw <em>blocks</em> (not grid cells) — deliberately
     * flat across every LOD level so a coarse level-5 node's skirt isn't 32x "deeper"
     * in world space than a level-0 node's for the same nominal drop. Large enough to
     * plausibly cover the worst-case height mismatch against an unknown neighbor at an
     * adjacent, differently-leveled node or across a region boundary, without being so
     * tall it becomes its own visible artifact (a vertical wall jutting out of a
     * hillside) when the seam it's meant to hide isn't actually there.
     * <p>
     * Not derived from any measured worst-case drop — a fixed, generous constant is the
     * standard, simple version of this technique (see e.g. how terrain-LOD "skirts" are
     * commonly described in the voxel/heightfield-rendering literature this project
     * draws general technique names from, per {@code 01_legal_constraints.md} — this is
     * an independent implementation, not derived from any specific engine's source).
     */
    private static final int SKIRT_DEPTH_BLOCKS = 8;

    /**
     * Adds a downward-hanging "skirt" quad along every one of a node's four outer grid
     * edges (local x=0, x=SIZE-1, z=0, z=SIZE-1), one per present boundary cell (merged
     * into runs exactly like {@link #meshWallsAlongX}/{@link #meshWallsAlongZ}).
     * <p>
     * Unlike {@link #meshBoundaryAlongX}/{@link #meshBoundaryAlongZ} (which need the
     * neighbor's actual grid and only cover same-level, same-region neighbors), a skirt
     * needs no information about what's on the other side of the edge at all: it simply
     * extends this node's own boundary geometry straight down by {@link #SKIRT_DEPTH_BLOCKS}
     * blocks. Whatever is drawn on the far side of the seam — a same-level neighbor
     * already stitched by {@code stitchLevel}, a differently-leveled neighbor (a LOD
     * distance-band transition, unhandled by any other stitching path), or a
     * not-yet-generated region edge — the skirt's near-vertical drop makes any height
     * mismatch at the seam read as shadow/occlusion rather than a see-through gap,
     * without this node needing to know which of those cases applies.
     * <p>
     * This runs on <em>every</em> node's own outer edge, including edges that
     * {@code stitchLevel} already closes with real geometry — the skirt there simply
     * ends up fully behind/co-planar with that real wall quad and is not visible
     * (harmless, not wasted: skirts and {@code stitchLevel} target different classes of
     * seam and neither knows which case the other is handling for a given edge).
     */
    private void meshSkirts(ColumnGrid grid, List<PackedQuad> out) {
        // West edge (x=0): faces -X outward.
        meshSkirtRun(grid, out, true, 0, AXIS_NEG_X);
        // East edge (x=SIZE-1): faces +X outward.
        meshSkirtRun(grid, out, true, ColumnGrid.SIZE - 1, AXIS_POS_X);
        // South edge (z=0): faces -Z outward.
        meshSkirtRun(grid, out, false, 0, AXIS_NEG_Z);
        // North edge (z=SIZE-1): faces +Z outward.
        meshSkirtRun(grid, out, false, ColumnGrid.SIZE - 1, AXIS_POS_Z);
    }

    /**
     * Merges runs of present, same-(height, material) cells along one fixed-coordinate
     * edge of the grid into skirt quads, the same greedy run-merge shape as
     * {@link #meshWallsAlongX}/{@link #meshWallsAlongZ}.
     *
     * @param alongX      true for the west/east edges (fixed local X, run direction Z);
     *                    false for the south/north edges (fixed local Z, run direction X)
     * @param fixedCoord  the local x (if {@code alongX}) or z (otherwise) coordinate of
     *                    the edge itself — always 0 or {@code ColumnGrid.SIZE - 1}
     * @param axis        outward-facing wall axis code for this edge, reused from the
     *                    interior-wall convention ({@code wallBetween})
     */
    private void meshSkirtRun(ColumnGrid grid, List<PackedQuad> out, boolean alongX, int fixedCoord, int axis) {
        int runStart = 0;
        while (runStart < ColumnGrid.SIZE) {
            if (!edgePresent(grid, alongX, fixedCoord, runStart)) {
                runStart++;
                continue;
            }
            int height = edgeHeight(grid, alongX, fixedCoord, runStart);
            int material = edgeMaterial(grid, alongX, fixedCoord, runStart);
            int light = edgeLight(grid, alongX, fixedCoord, runStart);

            int runLength = 1;
            while (runStart + runLength < ColumnGrid.SIZE
                    && edgePresent(grid, alongX, fixedCoord, runStart + runLength)
                    && edgeHeight(grid, alongX, fixedCoord, runStart + runLength) == height
                    && edgeMaterial(grid, alongX, fixedCoord, runStart + runLength) == material) {
                runLength++;
            }

            int localX = alongX ? fixedCoord : runStart;
            int localZ = alongX ? runStart : fixedCoord;
            // Skirt hangs down from just below the surface (height - 1) rather than from
            // the surface itself, so its top edge tucks under the top-face quad instead of
            // z-fighting with it at the shared boundary. Clamped to WORLD_MIN_Y: a column
            // already sitting at the world floor (-64) has nowhere lower to hang a skirt
            // from, and height - 1 there would encode as -65, which underflows PackedQuad's
            // unsigned 12-bit y field (wraps to 4095, a wall floating near the sky instead
            // of at bedrock) rather than clamping the way a signed field would.
            int skirtTop = Math.max(height - 1, WORLD_MIN_Y);
            out.add(PackedQuad.of(localX, skirtTop + Y_OFFSET, localZ, axis, runLength, SKIRT_DEPTH_BLOCKS, material, light));

            runStart += runLength;
        }
    }

    private boolean edgePresent(ColumnGrid grid, boolean alongX, int fixedCoord, int runCoord) {
        return grid.present(alongX ? fixedCoord : runCoord, alongX ? runCoord : fixedCoord);
    }

    private int edgeHeight(ColumnGrid grid, boolean alongX, int fixedCoord, int runCoord) {
        return grid.height(alongX ? fixedCoord : runCoord, alongX ? runCoord : fixedCoord);
    }

    private int edgeMaterial(ColumnGrid grid, boolean alongX, int fixedCoord, int runCoord) {
        return grid.material(alongX ? fixedCoord : runCoord, alongX ? runCoord : fixedCoord);
    }

    private int edgeLight(ColumnGrid grid, boolean alongX, int fixedCoord, int runCoord) {
        return grid.light(alongX ? fixedCoord : runCoord, alongX ? runCoord : fixedCoord);
    }

    /**
     * Describes the exposed vertical face (if any) between two adjacent cells, oriented
     * so the taller column's outward-facing side is what gets meshed (the shorter
     * column has nothing to hide behind it, from above).
     * <p>
     * {@code lowGrid}/{@code highGrid} are the same object for interior boundaries
     * (both cells live in this node's own grid) but different objects for cross-node
     * boundary stitching (see {@link #meshBoundaryAlongX} / {@link #meshBoundaryAlongZ}),
     * where {@code lowGrid} is the neighboring node's grid and {@code highGrid} is this
     * node's own grid (or vice versa, per the caller's world-space ordering).
     */
    private WallSegment wallBetween(ColumnGrid lowGrid, int lowSideX, int lowSideZ, ColumnGrid highGrid, int highSideX, int highSideZ) {
        boolean aPresent = lowGrid.present(lowSideX, lowSideZ);
        boolean bPresent = highGrid.present(highSideX, highSideZ);
        // BUGFIX: previously only "both absent" bailed out here, and a lone absent side was
        // given a Integer.MIN_VALUE sentinel height below, letting the wall span from the
        // real (present) column's height all the way down to MIN_VALUE. When the real height
        // is negative (completely normal below-sea-level terrain with the -64 build limit),
        // `realHeight - Integer.MIN_VALUE` does NOT overflow back into a harmless negative -
        // it lands as a legitimate ~2.1 billion-block-tall wall, which emitWallRun then
        // dutifully chops into ~34 million MAX_WALL_QUAD_HEIGHT-tall quads from a single cell
        // boundary. We have no idea what a not-yet-generated neighbor's height actually is,
        // so the right answer is simply not to guess: skip the wall rather than fabricate one
        // against absent data. This applies just as much to a genuinely missing NEIGHBOR node
        // (see meshBoundaryAlongX/Z) as to an absent cell within the same grid.
        if (!aPresent || !bPresent) {
            return null;
        }
        int aHeight = lowGrid.height(lowSideX, lowSideZ);
        int bHeight = highGrid.height(highSideX, highSideZ);
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
        int material = aTaller ? lowGrid.material(lowSideX, lowSideZ) : highGrid.material(highSideX, highSideZ);
        int light = aTaller ? lowGrid.light(lowSideX, lowSideZ) : highGrid.light(highSideX, highSideZ);
        // The boundary plane sits at the same coordinate regardless of which side is taller —
        // anchor it at the higher-index cell consistently, so the shader only needs `axis` to
        // know which way the quad faces.
        int fixed = lowSideZ == highSideZ ? highSideX : highSideZ;

        return new WallSegment(axis, fixed, lowY, highY, material, light);
    }

    /**
     * Generous upper bound on how tall a single wall run could ever legitimately be: the
     * full world height range (-64..320, i.e. 384 blocks) with headroom to spare. Purely a
     * defensive backstop - see the comment on the {@code !aPresent || !bPresent} check in
     * {@link #wallBetween} for the specific bug this guards against; this cap means a similar
     * mistake in the future degrades to "one wall is capped/wrong" instead of "silently
     * generate tens of millions of quads and corrupt the shared GPU buffer for the whole map".
     */
    private static final int MAX_SANE_WALL_HEIGHT = 4096;

    private void emitWallRun(List<PackedQuad> out, WallSegment seg, int runCoordStart, int runLength) {
        int remaining = seg.highY - seg.lowY;
        if (remaining > MAX_SANE_WALL_HEIGHT) {
            remaining = MAX_SANE_WALL_HEIGHT; // clamp rather than risk generating millions of quads
        }
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